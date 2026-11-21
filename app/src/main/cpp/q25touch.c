/*
 * q25touch: Touch scroll for the Q25 trackpad (Scroll wheel mode, "Touch" method).
 *
 * Runs as root. The trackpad hardware is kept in mouse mode, so it reports fine relative
 * motion (REL_X/REL_Y about every 7.5 ms). This program takes exclusive access to the
 * trackpad input device (EVIOCGRAB) and turns that motion into a virtual finger dragging
 * on a virtual touchscreen, moved in proportion to the finger on the pad. Apps then
 * scroll and fling with their own touch physics.
 *
 * The same input device also carries the keyboard, so every key is passed on through a
 * virtual keyboard with the same name and ids (so the same key layout files apply). A press
 * of the pad itself (BTN_MOUSE) is passed on as ENTER, like in keyboard mode.
 *
 * Usage: libq25touch.so /dev/input/eventN width height scale horizontal invertV invertH
 *
 * Motion and finger events are reported on stdout ("r dx dy", "g <event> x y", "b code value")
 * for the scroll practice log. Output is non-blocking and dropped if the reader falls
 * behind, so it can never stall input.
 * Exits (releasing the grab and removing the virtual devices) on SIGTERM/SIGINT/SIGHUP or
 * when stdin closes, i.e. when the parent helper goes away.
 */
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define TAG "Q25Touch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define BITS_PER_LONG_ (sizeof(long) * 8)
#define NLONGS(x) (((x) + BITS_PER_LONG_ - 1) / BITS_PER_LONG_)
#define TEST_BIT(bit, arr) (((arr)[(bit) / BITS_PER_LONG_] >> ((bit) % BITS_PER_LONG_)) & 1UL)

/* Finger goes down only after this much movement, so taps never become screen touches. */
#define ACTIVATION_PX 40
/* No motion for this long = finger left the pad: lift, and the app flings. */
#define IDLE_LIFT_MS 45
/* Keep the virtual finger this far from the screen edges; re-grab in the middle. */
#define EDGE_MARGIN_RATIO 0.12f
/* Opposite-direction travel needed before the finger turns around. Single reports the
 * other way are pad noise; reacting to each one restarted the drag on every report and the
 * app never got past its touch slop, so scrolling back stopped working. */
#define REVERSE_PX 36
/* Ignore motion right after start (the grab can catch the tail of a gesture). */
#define STARTUP_IGNORE_MS 150

static volatile sig_atomic_t g_running = 1;
static int g_in = -1, g_kbd = -1, g_touch = -1;

static int W = 720, H = 720;
static float SCALE = 6.0f;
static int HORIZ = 1, INV_V = 0, INV_H = 0;

/* Touch state */
static int t_active = 0, t_x = 0, t_y = 0, t_id = 1;
static long t_last_motion = 0;
static int pend_x = 0, pend_y = 0;   /* motion before activation (scaled px) */
static float rem_x = 0, rem_y = 0;   /* sub-pixel remainders */
static int last_sign_y = 0, last_sign_x = 0;
static int rev_x = 0, rev_y = 0;     /* opposite-direction travel so far (scaled px) */

static void on_signal(int s) { (void)s; g_running = 0; }

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void emit(int fd, int type, int code, int value) {
    if (fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) { /* ignore */ }
}

static void out(const char *fmt, int a, int b) {
    char buf[48];
    int n = snprintf(buf, sizeof(buf), fmt, a, b);
    if (n > 0 && write(STDOUT_FILENO, buf, n) < 0) { /* dropped when the pipe is full */ }
}

static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

static void touch_down(int x, int y) {
    t_x = x; t_y = y; t_active = 1;
    t_id = (t_id % 60000) + 1;
    emit(g_touch, EV_ABS, ABS_MT_SLOT, 0);
    emit(g_touch, EV_ABS, ABS_MT_TRACKING_ID, t_id);
    emit(g_touch, EV_ABS, ABS_MT_POSITION_X, x);
    emit(g_touch, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(g_touch, EV_ABS, ABS_MT_TOUCH_MAJOR, 4);
    emit(g_touch, EV_KEY, BTN_TOUCH, 1);
    emit(g_touch, EV_SYN, SYN_REPORT, 0);
    out("g down %d %d\n", x, y);
}

static void touch_move(int x, int y) {
    t_x = x; t_y = y;
    emit(g_touch, EV_ABS, ABS_MT_SLOT, 0);
    emit(g_touch, EV_ABS, ABS_MT_POSITION_X, x);
    emit(g_touch, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(g_touch, EV_SYN, SYN_REPORT, 0);
}

static void touch_up(void) {
    if (t_active) {
        emit(g_touch, EV_ABS, ABS_MT_SLOT, 0);
        emit(g_touch, EV_ABS, ABS_MT_TRACKING_ID, -1);
        emit(g_touch, EV_KEY, BTN_TOUCH, 0);
        emit(g_touch, EV_SYN, SYN_REPORT, 0);
        out("g up %d %d\n", t_x, t_y);
    }
    t_active = 0;
    pend_x = pend_y = 0;
    rem_x = rem_y = 0;
    last_sign_x = last_sign_y = 0;
    rev_x = rev_y = 0;
}

/* Where a new drag starts: away from the side it is heading to, so it has room to travel. */
static void start_point(int dx, int dy, int *sx, int *sy) {
    int mx = (int)(W * EDGE_MARGIN_RATIO), my = (int)(H * EDGE_MARGIN_RATIO);
    *sx = W / 2;
    *sy = H / 2;
    if (abs(dy) >= abs(dx)) {
        if (dy < 0) *sy = H - my - 1; else if (dy > 0) *sy = my;
    } else {
        if (dx < 0) *sx = W - mx - 1; else if (dx > 0) *sx = mx;
    }
}

/* dx, dy are pad counts; the finger moves the same way the content should move. */
static void on_motion(int dx, int dy) {
    if (!HORIZ) dx = 0;
    if (INV_H) dx = -dx;
    if (INV_V) dy = -dy;
    if (dx == 0 && dy == 0) return;

    float fx = dx * SCALE + rem_x, fy = dy * SCALE + rem_y;
    int mx = (int)fx, my = (int)fy;
    rem_x = fx - mx;
    rem_y = fy - my;

    int marginx = (int)(W * EDGE_MARGIN_RATIO), marginy = (int)(H * EDGE_MARGIN_RATIO);
    long now = now_ms();
    t_last_motion = now;

    if (!t_active) {
        pend_x += mx;
        pend_y += my;
        if (abs(pend_x) < ACTIVATION_PX && abs(pend_y) < ACTIVATION_PX) return;
        int sx, sy;
        start_point(pend_x, pend_y, &sx, &sy);
        touch_down(sx, sy);
        /* Move by the accumulated amount (capped) so the app passes its touch slop at once. */
        mx = clampi(pend_x, -ACTIVATION_PX * 2, ACTIVATION_PX * 2);
        my = clampi(pend_y, -ACTIVATION_PX * 2, ACTIVATION_PX * 2);
        pend_x = pend_y = 0;
    }

    int sgn_y = my > 0 ? 1 : (my < 0 ? -1 : 0);
    int sgn_x = mx > 0 ? 1 : (mx < 0 ? -1 : 0);
    int rev_v = sgn_y && last_sign_y && sgn_y != last_sign_y && abs(my) >= abs(mx);
    int rev_h = sgn_x && last_sign_x && sgn_x != last_sign_x && abs(mx) > abs(my);
    if (rev_v || rev_h) {
        /* Moving the other way: hold the finger still until it is clearly a turn-around,
         * then start a fresh drag (no zig-zag fling) that moves by the whole amount at once,
         * so the app passes its touch slop right away. */
        rev_x += mx;
        rev_y += my;
        if (abs(rev_y) < REVERSE_PX && abs(rev_x) < REVERSE_PX) {
            out("g hold %d %d\n", rev_x, rev_y);
            return;
        }
        int ax = rev_v ? 0 : rev_x, ay = rev_v ? rev_y : 0;
        if (!HORIZ) ax = 0;
        touch_up();
        int sx, sy;
        start_point(ax, ay, &sx, &sy);
        touch_down(sx, sy);
        out("g turn %d %d\n", ax, ay);
        mx = clampi(ax, -ACTIVATION_PX * 2, ACTIVATION_PX * 2);
        my = clampi(ay, -ACTIVATION_PX * 2, ACTIVATION_PX * 2);
        sgn_y = my > 0 ? 1 : (my < 0 ? -1 : 0);
        sgn_x = mx > 0 ? 1 : (mx < 0 ? -1 : 0);
    } else {
        /* Back to the same direction (it was noise): forget the opposite travel. */
        if (sgn_y && sgn_y == last_sign_y) rev_y = 0;
        if (sgn_x && sgn_x == last_sign_x) rev_x = 0;
        if (!last_sign_y && !last_sign_x) rev_x = rev_y = 0;
    }
    if (sgn_y) last_sign_y = sgn_y;
    if (sgn_x) last_sign_x = sgn_x;

    int nx = clampi(t_x + mx, marginx, W - marginx - 1);
    int ny = clampi(t_y + my, marginy, H - marginy - 1);
    if (nx != t_x || ny != t_y) touch_move(nx, ny);

    /* Ran out of room: lift and put the finger back down where it has room again. The
     * lift lets the content glide; the new touch catches it, like re-gripping a page. */
    int need_x = mx != 0 && (nx <= marginx || nx >= W - marginx - 1);
    int need_y = my != 0 && (ny <= marginy || ny >= H - marginy - 1);
    if (need_x || need_y) {
        int keep_x = last_sign_x, keep_y = last_sign_y;
        touch_up();
        int sx, sy;
        start_point(mx, my, &sx, &sy);
        out("g regrip %d %d\n", mx, my);
        touch_down(sx, sy);
        last_sign_x = keep_x;
        last_sign_y = keep_y;
    }
}

static int setup_abs(int fd, int code, int min, int max) {
    struct uinput_abs_setup s;
    memset(&s, 0, sizeof(s));
    s.code = code;
    s.absinfo.minimum = min;
    s.absinfo.maximum = max;
    return ioctl(fd, UI_ABS_SETUP, &s);
}

static int create_touch(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { LOGE("uinput open (touch): %s", strerror(errno)); return -1; }
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    /* A one-slot range (0..0) is treated as invalid by some ROMs; use 0..1. */
    if (setup_abs(fd, ABS_MT_SLOT, 0, 1) || setup_abs(fd, ABS_MT_TRACKING_ID, 0, 65535) ||
        setup_abs(fd, ABS_MT_POSITION_X, 0, W - 1) || setup_abs(fd, ABS_MT_POSITION_Y, 0, H - 1) ||
        setup_abs(fd, ABS_MT_TOUCH_MAJOR, 0, 15)) {
        LOGE("touch abs setup: %s", strerror(errno));
        close(fd);
        return -1;
    }
    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "Q25 Trackpad Touch Scroll");
    us.id.bustype = BUS_VIRTUAL;
    us.id.vendor = 0x5132;  /* "Q2" */
    us.id.product = 0x3554; /* "5T" */
    us.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &us) || ioctl(fd, UI_DEV_CREATE)) {
        LOGE("touch create: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

/* Virtual keyboard mirroring the real device's keys, name and ids. */
static int create_keyboard(int src) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { LOGE("uinput open (kbd): %s", strerror(errno)); return -1; }
    unsigned long keys[NLONGS(KEY_CNT)];
    memset(keys, 0, sizeof(keys));
    ioctl(src, EVIOCGBIT(EV_KEY, sizeof(keys)), keys);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_MSC);
    ioctl(fd, UI_SET_MSCBIT, MSC_SCAN);
    for (int k = 0; k < KEY_CNT; k++) {
        /* Mouse buttons stay off the keyboard so Android doesn't see a mouse. */
        if (k >= BTN_MOUSE && k < BTN_JOYSTICK) continue;
        if (TEST_BIT(k, keys)) ioctl(fd, UI_SET_KEYBIT, k);
    }
    ioctl(fd, UI_SET_KEYBIT, KEY_ENTER);
    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    struct input_id id;
    memset(&id, 0, sizeof(id));
    ioctl(src, EVIOCGID, &id);
    char name[UINPUT_MAX_NAME_SIZE];
    memset(name, 0, sizeof(name));
    if (ioctl(src, EVIOCGNAME(sizeof(name) - 1), name) < 0 || !name[0]) strcpy(name, "Q25_keyboard");
    snprintf(us.name, UINPUT_MAX_NAME_SIZE, "%s", name);
    us.id = id;
    if (us.id.bustype == 0) us.id.bustype = BUS_VIRTUAL;
    if (ioctl(fd, UI_DEV_SETUP, &us) || ioctl(fd, UI_DEV_CREATE)) {
        LOGE("kbd create: %s", strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

static void destroy(int *fd) {
    if (*fd >= 0) {
        ioctl(*fd, UI_DEV_DESTROY);
        close(*fd);
        *fd = -1;
    }
}

int main(int argc, char **argv) {
    if (argc < 8) {
        fprintf(stderr, "usage: %s /dev/input/eventN width height scale horizontal invertV invertH\n", argv[0]);
        return 1;
    }
    W = atoi(argv[2]);
    H = atoi(argv[3]);
    SCALE = (float)atof(argv[4]);
    HORIZ = atoi(argv[5]) != 0;
    INV_V = atoi(argv[6]) != 0;
    INV_H = atoi(argv[7]) != 0;
    if (W < 100 || H < 100 || SCALE <= 0.f) return 1;

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGHUP, on_signal);
    signal(SIGPIPE, SIG_IGN);
    fcntl(STDOUT_FILENO, F_SETFL, fcntl(STDOUT_FILENO, F_GETFL) | O_NONBLOCK);

    g_in = open(argv[1], O_RDONLY | O_NONBLOCK);
    if (g_in < 0) { LOGE("open %s: %s", argv[1], strerror(errno)); out("e open %d %d\n", errno, 0); return 2; }

    g_touch = create_touch();
    g_kbd = create_keyboard(g_in);
    if (g_touch < 0 || g_kbd < 0) {
        destroy(&g_touch);
        destroy(&g_kbd);
        out("e uinput %d %d\n", errno, 0);
        return 3;
    }
    usleep(150000); /* let Android pick up the new devices before grabbing */
    if (ioctl(g_in, EVIOCGRAB, 1) != 0) {
        LOGE("grab: %s", strerror(errno));
        destroy(&g_touch);
        destroy(&g_kbd);
        out("e grab %d %d\n", errno, 0);
        return 4;
    }
    LOGI("running on %s %dx%d scale %.2f", argv[1], W, H, SCALE);
    out("ok %d %d\n", W, H);

    long ignore_until = now_ms() + STARTUP_IGNORE_MS;
    int pdx = 0, pdy = 0, scan = -1;
    struct pollfd pf[2];
    pf[0].fd = g_in;
    pf[0].events = POLLIN;
    pf[1].fd = STDIN_FILENO;
    pf[1].events = POLLIN;

    while (g_running) {
        int timeout = t_active ? 10 : 500;
        int r = poll(pf, 2, timeout);
        if (r < 0 && errno != EINTR) break;
        if (pf[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            char b[64];
            ssize_t n = read(STDIN_FILENO, b, sizeof(b));
            if (n <= 0) break; /* parent went away */
        }
        if (pf[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (pf[0].revents & POLLIN) {
            struct input_event ev[64];
            ssize_t n;
            while ((n = read(g_in, ev, sizeof(ev))) > 0) {
                int cnt = (int)(n / sizeof(struct input_event));
                for (int i = 0; i < cnt; i++) {
                    struct input_event *e = &ev[i];
                    if (e->type == EV_REL) {
                        if (e->code == REL_X) pdx += e->value;
                        else if (e->code == REL_Y) pdy += e->value;
                    } else if (e->type == EV_MSC && e->code == MSC_SCAN) {
                        scan = e->value;
                    } else if (e->type == EV_KEY) {
                        if (e->code == BTN_MOUSE || e->code == BTN_RIGHT || e->code == BTN_MIDDLE) {
                            /* Pad press: ENTER, as in keyboard mode. Any touch in progress ends. */
                            touch_up();
                            out("b %d %d\n", e->code, e->value);
                            if (e->code == BTN_MOUSE) {
                                emit(g_kbd, EV_KEY, KEY_ENTER, e->value);
                                emit(g_kbd, EV_SYN, SYN_REPORT, 0);
                            }
                        } else {
                            if (scan >= 0) emit(g_kbd, EV_MSC, MSC_SCAN, scan);
                            emit(g_kbd, EV_KEY, e->code, e->value);
                            emit(g_kbd, EV_SYN, SYN_REPORT, 0);
                            out("b %d %d\n", e->code, e->value);
                        }
                        scan = -1;
                    } else if (e->type == EV_SYN && e->code == SYN_DROPPED) {
                        /* Part of the stream was lost: state can't be trusted. */
                        touch_up();
                        pdx = pdy = 0;
                        scan = -1;
                    } else if (e->type == EV_SYN && e->code == SYN_REPORT) {
                        if (pdx || pdy) {
                            out("r %d %d\n", pdx, pdy);
                            if (now_ms() >= ignore_until) on_motion(pdx, pdy);
                            pdx = pdy = 0;
                        }
                        scan = -1;
                    }
                }
            }
        }
        if (t_active && now_ms() - t_last_motion >= IDLE_LIFT_MS) touch_up();
        else if (!t_active && (pend_x || pend_y) && now_ms() - t_last_motion >= IDLE_LIFT_MS * 3) {
            pend_x = pend_y = 0; /* a tap or a tiny nudge: no touch at all */
        }
    }

    touch_up();
    ioctl(g_in, EVIOCGRAB, 0);
    close(g_in);
    destroy(&g_touch);
    destroy(&g_kbd);
    LOGI("stopped");
    return 0;
}
