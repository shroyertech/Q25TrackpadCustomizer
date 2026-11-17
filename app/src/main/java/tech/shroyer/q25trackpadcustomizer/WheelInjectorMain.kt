package tech.shroyer.q25trackpadcustomizer

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Root helper process that injects real mouse-wheel (ACTION_SCROLL) events.
 *
 * Started by [WheelInjector] as:
 *   CLASSPATH=<this apk> app_process /system/bin tech.shroyer.q25trackpadcustomizer.WheelInjectorMain
 * inside a root shell, so it may call InputManager.injectInputEvent.
 *
 * Protocol (stdin, one command per line):
 *   c <pxPerNotchV> <pxPerNotchH> <frameMs>
 *                       screen pixels one wheel notch scrolls (from ViewConfiguration) and
 *                       the display frame period; used to emit whole pixels per frame
 *   s <x> <y> <v> <h> [ease] [inertia]
 *                       add v / h wheel notches at screen point (x, y);
 *                       ease (0..1) = share of the queue released per 60 Hz frame;
 *                       inertia 0..3 = glide after the input stops (0 = none)
 *   t <0|1>             trackpad tap detection off/on; prints "tap <n>" for n >= 2 taps
 *   k <n>               click at the cursor: n = 1..3 left clicks, 0 = right click
 *   e <keycode>         inject a key press (down + up) from a virtual keyboard
 *   q                   quit
 * Prints "ready" once injection is available, or "error <reason>" and exits.
 *
 * Smoothness:
 *  - Output is paced by the display's vsync (Choreographer) when available, so each
 *    frame gets exactly one scroll event instead of a timer that drifts against the
 *    display (two events in one frame, none in the next = judder). Falls back to a
 *    drift-free timer loop.
 *  - Amounts are released by elapsed time, not per loop iteration, so speed is the same
 *    at any refresh rate or when a frame is late.
 *  - Apps turn wheel notches into pixels and truncate to whole pixels per event. Without
 *    care, the fractional part is lost on every frame (uneven steps, a tail that never
 *    lands). Here the remainder is carried over, so every event is a whole number of pixels.
 *  - Inertia: when the trackpad input stops while it was moving quickly, scrolling keeps
 *    gliding and slows down with friction, like a touch fling. A direction change stops it.
 */
object WheelInjectorMain {

    private const val REF_FRAME_MS = 16.667f
    private const val DEFAULT_EASE = 0.30f
    private const val MIN_RELEASE = 0.04f        // notches per reference frame
    private const val FLING_IDLE_MS = 70L        // no input for this long = the flick ended
    private const val RATE_RESET_MS = 160L       // gaps longer than this start a new burst
    private const val FLING_MIN_RATE = 6f        // notches/s below which there is no glide
    private const val FLING_MAX_VELOCITY = 60f   // notches/s
    private const val FLING_STOP_VELOCITY = 0.4f // notches/s

    // Per inertia level: share of the input speed carried into the glide, and the
    // friction time constant (seconds) of the glide.
    private val FLING_GAIN = floatArrayOf(0f, 0.35f, 0.5f, 0.7f)
    private val FLING_TAU = floatArrayOf(0f, 0.12f, 0.22f, 0.38f)

    private class AxisState {
        var pending = 0f        // notches still to deliver from input
        var flingVel = 0f       // notches per second
        var rate = 0f           // recent input speed, notches per second
        var lastInputUptime = 0L
        var pixelRemainder = 0f // sub-pixel carry, in pixels
        var pxPerNotch = 0f

        val idle: Boolean get() = pending == 0f && flingVel == 0f

        fun input(amount: Float, now: Long) {
            if (amount == 0f) return
            // A direction change drops whatever was queued or gliding the other way.
            val opposite = (pending != 0f && (amount > 0) != (pending > 0)) ||
                    (flingVel != 0f && (amount > 0) != (flingVel > 0))
            if (opposite) {
                pending = 0f
                flingVel = 0f
                rate = 0f
                pixelRemainder = 0f
            }
            val dt = now - lastInputUptime
            rate = if (lastInputUptime == 0L || dt > RATE_RESET_MS || opposite) {
                0f
            } else {
                val inst = amount / (dt.coerceAtLeast(8L) / 1000f)
                if (rate == 0f) inst else rate * 0.5f + inst * 0.5f
            }
            lastInputUptime = now
            pending += amount
        }

        /** Notches to emit for a frame of [dtMs], whole pixels when the pixel size is known. */
        fun step(dtMs: Float, now: Long, ease: Float, inertia: Int): Float {
            var out = 0f

            if (pending != 0f) {
                val frames = dtMs / REF_FRAME_MS
                val share = 1f - (1f - ease).pow(frames)
                val a = abs(pending)
                val r = maxOf(a * share, MIN_RELEASE * frames).coerceAtMost(a)
                val signed = if (pending > 0) r else -r
                pending -= signed
                if (abs(pending) < 1e-4f) pending = 0f
                out += signed
            }

            // Start a glide once the input has stopped after a quick flick.
            if (inertia > 0 && flingVel == 0f && lastInputUptime != 0L &&
                now - lastInputUptime >= FLING_IDLE_MS && abs(rate) >= FLING_MIN_RATE
            ) {
                flingVel = (rate * FLING_GAIN[inertia]).coerceIn(-FLING_MAX_VELOCITY, FLING_MAX_VELOCITY)
                rate = 0f
            }
            if (inertia == 0) flingVel = 0f

            if (flingVel != 0f) {
                val dtS = dtMs / 1000f
                out += flingVel * dtS
                flingVel *= exp(-dtS / FLING_TAU[inertia])
                if (abs(flingVel) < FLING_STOP_VELOCITY) flingVel = 0f
            }

            if (out == 0f) return 0f
            if (pxPerNotch <= 0f) return out

            val px = out * pxPerNotch + pixelRemainder
            val whole = px.toInt().toFloat() // truncate toward zero, like the apps do
            pixelRemainder = px - whole
            if (idle && abs(pixelRemainder) < 1f) {
                // Nothing more is coming: round the last fraction instead of dropping it.
                val rounded = Math.round(px).toFloat()
                pixelRemainder = 0f
                return rounded / pxPerNotch
            }
            return whole / pxPerNotch
        }
    }

    private val lock = Object()
    private val vAxis = AxisState()
    private val hAxis = AxisState()
    private var x = 0f
    private var y = 0f
    private var ease = DEFAULT_EASE
    private var inertia = 0
    private var frameMs = REF_FRAME_MS

    @Volatile
    private var kick: (() -> Unit)? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val injector = try {
            Injector.create()
        } catch (t: Throwable) {
            println("error ${t.javaClass.simpleName}: ${t.message}")
            System.out.flush()
            return
        }

        println("ready")
        System.out.flush()

        startPump(injector)

        val out = Object()
        val pad = PadTapDetector { n ->
            synchronized(out) {
                println("tap $n")
                System.out.flush()
            }
        }
        var padStarted = false
        val clicker = java.util.concurrent.Executors.newSingleThreadExecutor()

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: Exception) {
                null
            } ?: break

            val parts = line.trim().split(' ')
            when (parts.firstOrNull()) {
                "q" -> break
                "t" -> {
                    val on = parts.getOrNull(1) == "1"
                    if (on && !padStarted) {
                        padStarted = true
                        val devs = pad.start()
                        synchronized(out) {
                            println("pad $devs")
                            System.out.flush()
                        }
                    }
                    pad.enabled = on
                }
                "e" -> {
                    val code = parts.getOrNull(1)?.toIntOrNull()
                    if (code != null) clicker.execute {
                        try {
                            injector.key(code)
                        } catch (_: Throwable) {
                        }
                    }
                }
                "k" -> {
                    val n = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 3) ?: 1
                    clicker.execute { pad.click(n) }
                }
                "c" -> if (parts.size >= 4) {
                    synchronized(lock) {
                        vAxis.pxPerNotch = parts[1].toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                        hAxis.pxPerNotch = parts[2].toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                        frameMs = parts[3].toFloatOrNull()?.coerceIn(4f, 50f) ?: REF_FRAME_MS
                    }
                }
                "s" -> if (parts.size >= 5) {
                    val px = parts[1].toFloatOrNull() ?: continue
                    val py = parts[2].toFloatOrNull() ?: continue
                    val v = parts[3].toFloatOrNull() ?: 0f
                    val h = parts[4].toFloatOrNull() ?: 0f
                    val e = parts.getOrNull(5)?.toFloatOrNull()?.coerceIn(0.05f, 1f)
                    val i = parts.getOrNull(6)?.toIntOrNull()?.coerceIn(0, 3)
                    val now = SystemClock.uptimeMillis()
                    synchronized(lock) {
                        x = px
                        y = py
                        if (e != null) ease = e
                        if (i != null) inertia = i
                        vAxis.input(v, now)
                        hAxis.input(h, now)
                        lock.notifyAll()
                    }
                    kick?.invoke()
                }
            }
        }
        System.exit(0)
    }

    private class Frame(val v: Float, val h: Float, val x: Float, val y: Float, val more: Boolean)

    /** Advance the model by [dtMs]; null when there is nothing to do. */
    private fun nextFrame(dtMs: Float): Frame? {
        synchronized(lock) {
            if (vAxis.idle && hAxis.idle && !flingPossible(vAxis) && !flingPossible(hAxis)) return null
            val now = SystemClock.uptimeMillis()
            val v = vAxis.step(dtMs, now, ease, inertia)
            val h = hAxis.step(dtMs, now, ease, inertia)
            val more = !(vAxis.idle && hAxis.idle) || flingPossible(vAxis) || flingPossible(hAxis)
            return Frame(v, h, x, y, more)
        }
    }

    // After the queue drains, keep ticking until a glide either starts or is ruled out.
    private fun flingPossible(a: AxisState): Boolean =
        inertia > 0 && a.flingVel == 0f && abs(a.rate) >= FLING_MIN_RATE

    private fun startPump(injector: Injector) {
        if (!startVsyncPump(injector)) startTimerPump(injector)
    }

    /** Vsync-paced pump. Returns false if Choreographer isn't usable in this process. */
    private fun startVsyncPump(injector: Injector): Boolean {
        val thread = HandlerThread("wheel-vsync").apply { start() }
        val handler = Handler(thread.looper)
        val result = Object()
        var choreographer: Choreographer? = null
        var done = false
        handler.post {
            choreographer = try {
                Choreographer.getInstance()
            } catch (_: Throwable) {
                null
            }
            synchronized(result) {
                done = true
                result.notifyAll()
            }
        }
        synchronized(result) {
            val deadline = SystemClock.uptimeMillis() + 2000
            while (!done && SystemClock.uptimeMillis() < deadline) result.wait(200)
        }
        val ch = choreographer
        if (ch == null) {
            thread.quitSafely()
            return false
        }

        var scheduled = false
        var lastFrameNanos = 0L
        var anyFrame = false
        var abandoned = false
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (abandoned) return
                anyFrame = true
                scheduled = false
                val dtMs = if (lastFrameNanos == 0L) frameMs
                else ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceIn(1f, 50f)
                lastFrameNanos = frameTimeNanos
                val f = nextFrame(dtMs)
                if (f == null) {
                    lastFrameNanos = 0L
                    return
                }
                if (f.v != 0f || f.h != 0f) {
                    try {
                        injector.scroll(f.x, f.y, f.v, f.h)
                    } catch (_: Throwable) {
                    }
                }
                if (f.more) {
                    scheduled = true
                    ch.postFrameCallback(this)
                } else {
                    lastFrameNanos = 0L
                }
            }
        }
        kick = {
            handler.post {
                if (!scheduled && !abandoned) {
                    scheduled = true
                    ch.postFrameCallback(callback)
                    if (!anyFrame) {
                        // Safety net: if vsync never arrives in this process, use the timer.
                        handler.postDelayed({
                            if (!anyFrame && !abandoned) {
                                abandoned = true
                                startTimerPump(injector)
                                synchronized(lock) { lock.notifyAll() }
                            }
                        }, 300L)
                    }
                }
            }
        }
        return true
    }

    /** Timer-paced fallback: fixed schedule (no drift), amounts by real elapsed time. */
    private fun startTimerPump(injector: Injector) {
        val pump = Thread({
            var last = 0L
            while (true) {
                synchronized(lock) {
                    while (vAxis.idle && hAxis.idle && !flingPossible(vAxis) && !flingPossible(hAxis)) {
                        last = 0L
                        lock.wait()
                    }
                }
                val now = System.nanoTime()
                val period = (frameMs * 1_000_000).toLong()
                val dtMs = if (last == 0L) frameMs else ((now - last) / 1_000_000f).coerceIn(1f, 50f)
                last = now
                val f = nextFrame(dtMs)
                if (f != null && (f.v != 0f || f.h != 0f)) {
                    try {
                        injector.scroll(f.x, f.y, f.v, f.h)
                    } catch (_: Throwable) {
                    }
                }
                val sleepNs = period - (System.nanoTime() - now)
                if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
            }
        }, "wheel-pump")
        pump.isDaemon = true
        pump.start()
        kick = null
    }

    /** Finds a working injectInputEvent across Android versions (all hidden APIs). */
    private class Injector(private val target: Any, private val method: Method) {

        fun key(code: Int) {
            val now = SystemClock.uptimeMillis()
            for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                val ev = android.view.KeyEvent(
                    now, SystemClock.uptimeMillis(), action, code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
                )
                method.invoke(target, ev, 0)
            }
        }

        fun scroll(px: Float, py: Float, v: Float, h: Float) {
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = px
                y = py
                setAxisValue(MotionEvent.AXIS_VSCROLL, v)
                setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            })
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
            try {
                method.invoke(target, event, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
            } finally {
                event.recycle()
            }
        }

        companion object {
            fun create(): Injector {
                val errors = StringBuilder()

                // Android 14+: InputManagerGlobal
                try {
                    val cls = Class.forName("android.hardware.input.InputManagerGlobal")
                    val inst = cls.getMethod("getInstance").invoke(null)!!
                    val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    return Injector(inst, m)
                } catch (t: Throwable) {
                    errors.append("global:").append(t.javaClass.simpleName).append(' ')
                }

                // Older: InputManager.getInstance()
                try {
                    val cls = Class.forName("android.hardware.input.InputManager")
                    val inst = cls.getMethod("getInstance").invoke(null)!!
                    val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    return Injector(inst, m)
                } catch (t: Throwable) {
                    errors.append("im:").append(t.javaClass.simpleName).append(' ')
                }

                // Last resort: the binder interface directly.
                try {
                    val sm = Class.forName("android.os.ServiceManager")
                    val binder = sm.getMethod("getService", String::class.java).invoke(null, "input")
                    val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                    val iface = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
                    val m = iface.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    return Injector(iface, m)
                } catch (t: Throwable) {
                    errors.append("binder:").append(t.javaClass.simpleName)
                }

                throw IllegalStateException(errors.toString())
            }
        }
    }
}
