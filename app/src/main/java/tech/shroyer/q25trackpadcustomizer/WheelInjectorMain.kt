package tech.shroyer.q25trackpadcustomizer

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * Root helper process that injects real mouse-wheel (ACTION_SCROLL) events.
 *
 * Started by [WheelInjector] as:
 *   CLASSPATH=<this apk> app_process /system/bin tech.shroyer.q25trackpadcustomizer.WheelInjectorMain
 * inside a root shell, so it may call InputManager.injectInputEvent.
 *
 * Protocol (stdin, one command per line):
 *   s <x> <y> <v> <h> [ease]   add v / h wheel notches at screen point (x, y);
 *                              ease (0..1) = share of the queue released per frame
 *   q                   quit
 * Prints "ready" once injection is available, or "error <reason>" and exits.
 *
 * Notches are not injected all at once: queued amounts are released over successive
 * ~60 Hz frames with an easing curve, so a tick (or a burst of ticks) glides instead
 * of jumping, and apps scroll with their native wheel handling. Wheel events never
 * trigger edge/drawer/shade gestures, unlike simulated touch drags.
 */
object WheelInjectorMain {

    private const val FRAME_MS = 16L
    // Default fraction of the remaining queued scroll released each frame (higher = snappier).
    private const val DEFAULT_EASE = 0.30f
    private const val MIN_CHUNK = 0.04f

    private val lock = Object()
    private var pendingV = 0f
    private var pendingH = 0f
    private var x = 0f
    private var y = 0f
    private var ease = DEFAULT_EASE

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

        val pump = Thread({ pumpLoop(injector) }, "wheel-pump")
        pump.isDaemon = true
        pump.start()

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
                "s" -> if (parts.size >= 5) {
                    val px = parts[1].toFloatOrNull() ?: continue
                    val py = parts[2].toFloatOrNull() ?: continue
                    val v = parts[3].toFloatOrNull() ?: 0f
                    val h = parts[4].toFloatOrNull() ?: 0f
                    val e = parts.getOrNull(5)?.toFloatOrNull()?.coerceIn(0.05f, 1f)
                    synchronized(lock) {
                        x = px
                        y = py
                        if (e != null) ease = e
                        // A direction change drops whatever was still queued the other way.
                        if (v != 0f && pendingV != 0f && (v > 0) != (pendingV > 0)) pendingV = 0f
                        if (h != 0f && pendingH != 0f && (h > 0) != (pendingH > 0)) pendingH = 0f
                        pendingV += v
                        pendingH += h
                        lock.notifyAll()
                    }
                }
            }
        }
        System.exit(0)
    }

    private fun pumpLoop(injector: Injector) {
        while (true) {
            var v: Float
            var h: Float
            var px: Float
            var py: Float
            synchronized(lock) {
                while (pendingV == 0f && pendingH == 0f) lock.wait()
                v = chunk(pendingV, ease)
                h = chunk(pendingH, ease)
                pendingV -= v
                pendingH -= h
                px = x
                py = y
            }
            try {
                injector.scroll(px, py, v, h)
            } catch (_: Throwable) {
            }
            Thread.sleep(FRAME_MS)
        }
    }

    private fun chunk(pending: Float, ease: Float): Float {
        if (pending == 0f) return 0f
        val abs = kotlin.math.abs(pending)
        val c = maxOf(abs * ease, MIN_CHUNK).coerceAtMost(abs)
        return if (pending > 0) c else -c
    }

    /** Finds a working injectInputEvent across Android versions (all hidden APIs). */
    private class Injector(private val target: Any, private val method: Method) {

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
