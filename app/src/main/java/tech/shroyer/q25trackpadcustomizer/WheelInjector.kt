package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.os.SystemClock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * App-side controller for the root wheel-injection helper ([WheelInjectorMain]).
 *
 * The helper runs as root via `app_process` and stays alive, so each scroll tick is just
 * a line written to its stdin. All process work happens on a background thread; the
 * main thread only enqueues. [scroll] returns false when the helper isn't ready, so the
 * caller can fall back to simulated touch gestures (no root, helper failed, etc.).
 */
class WheelInjector(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val apkPath: String = context.applicationInfo.sourceDir
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "q25-wheel-io").apply { isDaemon = true }
    }

    @Volatile
    var isReady = false
        private set

    @Volatile
    private var starting = false

    @Volatile
    private var nextStartAllowedUptime = 0L
    private var failures = 0

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    /** Start the helper in the background if it isn't running (rate-limited after failures). */
    fun ensureStarted() {
        if (isReady || starting) return
        if (SystemClock.uptimeMillis() < nextStartAllowedUptime) return
        starting = true
        try {
            io.execute { startBlocking() }
        } catch (_: Exception) {
            starting = false
        }
    }

    /**
     * Queue [v]/[h] wheel notches at screen point ([x], [y]).
     * Positive v scrolls up (toward the top), positive h scrolls right.
     * [inertia] 0..3: how long scrolling glides on after a quick flick (0 = none).
     */
    fun scroll(x: Float, y: Float, v: Float, h: Float, ease: Float, inertia: Int = 0): Boolean {
        if (!isReady) {
            ensureStarted()
            return false
        }
        val line = "s $x $y $v $h $ease $inertia\n"
        try {
            io.execute {
                val w = writer
                if (w == null) {
                    isReady = false
                    return@execute
                }
                try {
                    w.write(line)
                    w.flush()
                } catch (_: Exception) {
                    onDied()
                }
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    /** Lines the helper prints after start ("tap 2", "pad ..."), delivered on the main thread. */
    @Volatile
    var onHelperLine: ((String) -> Unit)? = null

    @Volatile
    private var tapDetection = false

    /** Turn trackpad tap detection on/off (remembered across helper restarts). */
    fun setTapDetection(on: Boolean) {
        tapDetection = on
        if (on) ensureStarted()
        send("t ${if (on) 1 else 0}\n")
    }

    /** Click at the cursor: 1..3 left clicks, 0 = right click. */
    fun click(count: Int): Boolean = send("k $count\n")

    /** Inject a key press (e.g. for key mapper apps). */
    fun key(keyCode: Int): Boolean = send("e $keyCode\n")

    private fun send(line: String): Boolean {
        if (!isReady) {
            ensureStarted()
            return false
        }
        try {
            io.execute {
                val w = writer ?: return@execute
                try {
                    w.write(line)
                    w.flush()
                } catch (_: Exception) {
                    onDied()
                }
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    fun stop() {
        try {
            io.execute {
                try {
                    writer?.write("q\n")
                    writer?.flush()
                } catch (_: Exception) {
                }
                destroy()
            }
        } catch (_: Exception) {
        }
    }

    private fun startBlocking() {
        try {
            destroy()
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            val w = BufferedWriter(OutputStreamWriter(p.outputStream))
            val r = BufferedReader(InputStreamReader(p.inputStream))

            val cls = WheelInjectorMain::class.java.name
            w.write("CLASSPATH='$apkPath' exec app_process /system/bin $cls\n")
            w.flush()

            // Wait (bounded) for the helper to report in.
            val killer = Thread {
                try {
                    Thread.sleep(START_TIMEOUT_MS)
                    if (!isReady) p.destroy()
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }

            var line = r.readLine()
            while (line != null && line != "ready" && !line.startsWith("error")) {
                line = r.readLine()
            }
            killer.interrupt()

            if (line == "ready") {
                try {
                    w.write(configLine())
                    if (tapDetection) w.write("t 1\n")
                    w.flush()
                } catch (_: Exception) {
                }
                process = p
                writer = w
                isReady = true
                failures = 0
                // Drain any later output so the pipe never fills; mark dead on exit.
                Thread {
                    try {
                        while (true) {
                            val out = r.readLine() ?: break
                            val cb = onHelperLine ?: continue
                            mainHandler.post { cb(out) }
                        }
                    } catch (_: Exception) {
                    }
                    onDied()
                }.apply { isDaemon = true; start() }
            } else {
                try { p.destroy() } catch (_: Exception) {}
                onStartFailed()
            }
        } catch (_: Exception) {
            onStartFailed()
        } finally {
            starting = false
        }
    }

    /** Pixels per wheel notch and the display frame period, so the helper emits whole pixels. */
    private fun configLine(): String {
        val dm = appContext.resources.displayMetrics
        var pxV = 64f * dm.density
        var pxH = 64f * dm.density
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val vc = android.view.ViewConfiguration.get(appContext)
                pxV = vc.scaledVerticalScrollFactor
                pxH = vc.scaledHorizontalScrollFactor
            } catch (_: Exception) {
            }
        }
        var frameMs = 16.667f
        try {
            @Suppress("DEPRECATION")
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            val hz = wm.defaultDisplay.refreshRate
            if (hz in 20f..240f) frameMs = 1000f / hz
        } catch (_: Exception) {
        }
        return "c $pxV $pxH $frameMs\n"
    }

    private fun onStartFailed() {
        isReady = false
        failures++
        val backoff = (RETRY_BASE_MS shl (failures - 1).coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)
        nextStartAllowedUptime = SystemClock.uptimeMillis() + backoff
    }

    private fun onDied() {
        isReady = false
        writer = null
        process = null
    }

    private fun destroy() {
        isReady = false
        try { writer?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        writer = null
        process = null
    }

    companion object {
        private const val START_TIMEOUT_MS = 8_000L
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
    }
}
