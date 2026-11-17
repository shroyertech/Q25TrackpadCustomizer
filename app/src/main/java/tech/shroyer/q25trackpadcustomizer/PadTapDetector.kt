package tech.shroyer.q25trackpadcustomizer

import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Runs inside the root helper ([WheelInjectorMain]). Reads the trackpad's raw evdev
 * events (no grab: the system still gets every event) and recognises taps.
 *
 * Keyboard and Scroll modes: the firmware marks a tap with a bare MSC_SCAN 0x07 (no key
 * in the same frame); slides send arrow keys. Those taps are exact.
 *
 * Mouse mode: no tap marker at all, the pad only reports motion.
 * Measured on the device (2026-11-17): a tap is a burst of 1-8 motion reports ~7.5 ms
 * apart, lasting <= 80 ms, moving 0-30 counts; taps in a double/triple tap start
 * 120-240 ms apart; separate gestures are >= 0.7 s apart. Cursor movement is longer
 * bursts. Very fast taps can merge into one longer burst and are then not counted. So a "tap" here = a short, tiny burst after
 * a quiet moment, and a sequence ends after a short pause.
 *
 * Also injects mouse clicks by writing button events to the same device, so clicks
 * land at the real cursor position without needing to know where it is.
 */
class PadTapDetector(private val onTaps: (Int) -> Unit) {

    /** Burst limits for one motion source. */
    private class Limits(
        val burstGap: Long,
        val tapMaxMs: Long,
        val tapMaxReports: Int,
        val tapMaxTravel: Int,
        val tapMaxStep: Int,
        val tapMinGap: Long,
        val tapMaxGap: Long,
        val quietBefore: Long,
        val sequenceEnd: Long
    )

    @Volatile
    var enabled = false

    private val eventSize = if ((System.getProperty("os.arch") ?: "").contains("64")) 24 else 16
    private val lock = Object()

    // Current burst
    private var burstOpen = false
    private var burstStart = 0L
    private var burstLast = 0L
    private var burstReports = 0
    private var burstAbs = 0
    private var quietBefore = 0L
    private var pendingDx = 0
    private var pendingDy = 0
    private var lastReportUptime = 0L

    // Tap sequence
    private var tapCount = 0
    private var lastTapEnd = 0L

    private var burstMaxStep = 0
    private var burstLimits = REL_LIMITS

    // Keyboard / Scroll modes: the kernel driver (bbqX0kbd) logs every raw trackpad report
    // to the kernel log, taps included, while the input device only gets arrow keys. When
    // the hardware is in keyboard mode, taps are recognised from those log lines instead.
    @Volatile
    private var kmsgWorking = false
    @Volatile
    private var hwKeyboard = false
    private var hwModeCheckedUptime = 0L
    private var kmsgOffsetMs = Long.MAX_VALUE

    // Current evdev frame (between SYN_REPORTs)
    private var frameTapScan = false
    private var frameHasKey = false

    @Volatile
    private var lastDevice: String? = null
    private val devices = ArrayList<String>()

    fun start(): String {
        val found = findPointerDevices()
        if (found.isEmpty()) return "no-device"
        devices.addAll(found)
        for (dev in found) {
            Thread({ readLoop(dev) }, "pad-read-${File(dev).name}").apply { isDaemon = true; start() }
        }
        Thread({ checkLoop() }, "pad-tap-check").apply { isDaemon = true; start() }
        Thread({ kmsgLoop() }, "pad-kmsg").apply { isDaemon = true; start() }
        return found.joinToString(",") + ",kmsg"
    }

    /** Hardware mode from the driver's proc switch (0 = mouse, 1 = keyboard), cached briefly. */
    private fun hardwareIsKeyboard(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - hwModeCheckedUptime < HW_MODE_CACHE_MS) return hwKeyboard
        hwModeCheckedUptime = now
        hwKeyboard = try {
            File(PROC_SWITCH).readText().trim() == "1"
        } catch (_: Exception) {
            false
        }
        return hwKeyboard
    }

    private fun kmsgLoop() {
        val regex = Regex("X Reg: (-?\\d+) Y Reg: (-?\\d+)")
        try {
            FileInputStream("/dev/kmsg").use { input ->
                val buf = ByteArray(8192)
                val startUs = System.nanoTime() / 1000 - KMSG_SKIP_OLDER_US
                while (true) {
                    val n = input.read(buf)   // one record per read
                    if (n <= 0) break
                    val rec = String(buf, 0, n)
                    if (!rec.contains("X Reg:")) continue
                    val semi = rec.indexOf(';')
                    if (semi < 0) continue
                    val usec = rec.substring(0, semi).split(',').getOrNull(2)?.toLongOrNull() ?: continue
                    if (usec < startUs) continue // backlog from before we started
                    kmsgWorking = true
                    if (!enabled) continue
                    val m = regex.find(rec, semi) ?: continue
                    val x = m.groupValues[1].toIntOrNull() ?: continue
                    val y = m.groupValues[2].toIntOrNull() ?: continue
                    // Map kernel time to uptime; the smallest offset seen has the least read lag.
                    val kMs = usec / 1000
                    val off = SystemClock.uptimeMillis() - kMs
                    if (off < kmsgOffsetMs) kmsgOffsetMs = off
                    if (!hardwareIsKeyboard()) continue // Mouse mode: evdev motion is used
                    report(abs(x) + abs(y), maxOf(abs(x), abs(y)), kMs + kmsgOffsetMs, KMSG_LIMITS)
                }
            }
        } catch (t: Throwable) {
            System.err.println("kmsg: $t")
        }
        kmsgWorking = false
    }

    /** Devices that can report relative X/Y motion (the trackpad, incl. virtual copies). */
    private fun findPointerDevices(): List<String> {
        val out = ArrayList<String>()
        val dir = File("/sys/class/input")
        val names = dir.list() ?: return out
        for (n in names.sorted()) {
            if (!n.startsWith("event")) continue
            val rel = try {
                File(dir, "$n/device/capabilities/rel").readText().trim()
            } catch (_: Exception) {
                continue
            }
            val bits = rel.split(' ').lastOrNull()?.toLongOrNull(16) ?: 0L
            if ((bits and 0x3L) == 0x3L) out.add("/dev/input/$n")
        }
        return out
    }

    private fun readLoop(dev: String) {
        try {
            FileInputStream(dev).use { input ->
                val buf = ByteArray(eventSize * 32)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    var n = input.read(buf)
                    if (n <= 0) break
                    n -= n % eventSize
                    var off = 0
                    while (off < n) {
                        val base = off + eventSize - 8
                        val type = bb.getShort(base).toInt() and 0xffff
                        val code = bb.getShort(base + 2).toInt() and 0xffff
                        val value = bb.getInt(base + 4)
                        onEvent(dev, type, code, value)
                        off += eventSize
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun onEvent(dev: String, type: Int, code: Int, value: Int) {
        if (!enabled) return
        when (type) {
            EV_REL -> {
                if (code == REL_X) pendingDx += value
                else if (code == REL_Y) pendingDy += value
                lastDevice = dev
            }
            EV_MSC -> if (code == MSC_SCAN && value == TAP_SCAN_CODE) frameTapScan = true
            EV_SYN -> {
                // The firmware's tap marker is only used when the kernel log isn't readable.
                if (frameTapScan && !frameHasKey && !kmsgWorking) markerTap()
                frameTapScan = false
                frameHasKey = false
                if (pendingDx != 0 || pendingDy != 0) {
                    report(
                        abs(pendingDx) + abs(pendingDy), maxOf(abs(pendingDx), abs(pendingDy)),
                        SystemClock.uptimeMillis(), REL_LIMITS
                    )
                    pendingDx = 0
                    pendingDy = 0
                }
            }
            EV_KEY -> {
                frameHasKey = true
                // In keyboard mode tap jitter sometimes leaks out as a stray arrow key; slides
                // are recognised from their motion instead, so arrows don't cancel a sequence.
                if (kmsgWorking && code in ARROW_KEYS) return
                synchronized(lock) {
                    // A real click, key or slide cancels any tap sequence.
                    tapCount = 0
                    burstOpen = false
                }
            }
        }
    }

    /** Exact tap reported by the firmware (Keyboard / Scroll modes). */
    private fun markerTap() {
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            burstOpen = false
            tapCount = if (tapCount > 0 && now - lastTapEnd <= REL_LIMITS.tapMaxGap) tapCount + 1 else 1
            burstLimits = REL_LIMITS
            lastTapEnd = now
            lock.notifyAll()
        }
    }

    private fun report(magnitude: Int, step: Int, now: Long, limits: Limits) {
        synchronized(lock) {
            if (burstOpen && (limits !== burstLimits || now - burstLast > burstLimits.burstGap)) finishBurst()
            if (!burstOpen) {
                burstOpen = true
                burstLimits = limits
                burstStart = now
                burstReports = 0
                burstAbs = 0
                burstMaxStep = 0
                quietBefore = if (lastReportUptime == 0L) Long.MAX_VALUE else now - lastReportUptime
            }
            burstLast = now
            burstReports++
            burstAbs += magnitude
            if (step > burstMaxStep) burstMaxStep = step
            lastReportUptime = now
            lock.notifyAll()
        }
    }

    // Caller holds lock.
    private fun finishBurst() {
        burstOpen = false
        val l = burstLimits
        val duration = burstLast - burstStart
        val isTap = duration <= l.tapMaxMs && burstReports <= l.tapMaxReports &&
                burstAbs <= l.tapMaxTravel && burstMaxStep <= l.tapMaxStep
        if (!isTap) {
            tapCount = 0
            return
        }
        val sinceLastTap = burstStart - lastTapEnd
        tapCount = when {
            tapCount > 0 && sinceLastTap in l.tapMinGap..l.tapMaxGap -> tapCount + 1
            quietBefore >= l.quietBefore -> 1
            else -> 0
        }
        lastTapEnd = burstLast
    }

    private fun checkLoop() {
        while (true) {
            var fire = 0
            synchronized(lock) {
                while (!burstOpen && tapCount == 0) lock.wait()
                val now = SystemClock.uptimeMillis()
                if (burstOpen && now - burstLast > burstLimits.burstGap) finishBurst()
                if (!burstOpen && tapCount > 0 && now - lastTapEnd > burstLimits.sequenceEnd) {
                    fire = tapCount
                    tapCount = 0
                }
            }
            if (fire >= 2 && enabled) {
                try {
                    onTaps(fire)
                } catch (_: Throwable) {
                }
            }
            Thread.sleep(CHECK_MS)
        }
    }

    /** Click [count] times with the left button, or once with the right (count 0). */
    fun click(count: Int) {
        val dev = lastDevice ?: devices.firstOrNull() ?: return
        try {
            FileOutputStream(dev).use { out ->
                val btn = if (count == 0) BTN_RIGHT else BTN_LEFT
                val times = if (count == 0) 1 else count
                repeat(times) { i ->
                    out.write(event(EV_KEY, btn, 1))
                    out.write(event(EV_SYN, 0, 0))
                    out.flush()
                    Thread.sleep(CLICK_HOLD_MS)
                    out.write(event(EV_KEY, btn, 0))
                    out.write(event(EV_SYN, 0, 0))
                    out.flush()
                    if (i < times - 1) Thread.sleep(CLICK_GAP_MS)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun event(type: Int, code: Int, value: Int): ByteArray {
        val b = ByteBuffer.allocate(eventSize).order(ByteOrder.LITTLE_ENDIAN)
        b.position(eventSize - 8)
        b.putShort(type.toShort())
        b.putShort(code.toShort())
        b.putInt(value)
        return b.array()
    }

    companion object {
        private const val EV_SYN = 0
        private const val EV_KEY = 1
        private const val EV_REL = 2
        private const val EV_MSC = 4
        private const val MSC_SCAN = 4
        private const val TAP_SCAN_CODE = 7
        private const val REL_X = 0
        private const val REL_Y = 1
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111

        private val ARROW_KEYS = setOf(103, 105, 106, 108)
        private const val PROC_SWITCH = "/proc/q20_switch_key_mouse"
        private const val HW_MODE_CACHE_MS = 400L
        private const val KMSG_SKIP_OLDER_US = 2_000_000L

        // Mouse mode, evdev REL reports (~7.5 ms apart).
        private val REL_LIMITS = Limits(
            burstGap = 35L, tapMaxMs = 90L, tapMaxReports = 9, tapMaxTravel = 40, tapMaxStep = Int.MAX_VALUE,
            tapMinGap = 50L, tapMaxGap = 330L, quietBefore = 200L, sequenceEnd = 340L
        )

        // Keyboard mode, raw driver reports from the kernel log (X/Y Reg deltas, ~7-8 ms
        // apart). Measured 2026-11-17: one tap = 1-7 reports, often split into 2-3 mini
        // bursts 38-61 ms apart, sum |x|+|y| usually < 25 (max ~60), largest step <= 11.
        // Slides = 8-20 reports per burst, 55-200 travel, steps 10-22, bursts ~0.3 s apart.
        private val KMSG_LIMITS = Limits(
            burstGap = 80L, tapMaxMs = 130L, tapMaxReports = 9, tapMaxTravel = 60, tapMaxStep = 12,
            tapMinGap = 60L, tapMaxGap = 330L, quietBefore = 200L, sequenceEnd = 380L
        )
        private const val CHECK_MS = 20L
        private const val CLICK_HOLD_MS = 25L
        private const val CLICK_GAP_MS = 60L
    }
}
