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

    private val apkPath: String = context.applicationInfo.sourceDir
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
     */
    fun scroll(x: Float, y: Float, v: Float, h: Float, ease: Float): Boolean {
        if (!isReady) {
            ensureStarted()
            return false
        }
        val line = "s $x $y $v $h $ease\n"
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
                process = p
                writer = w
                isReady = true
                failures = 0
                // Drain any later output so the pipe never fills; mark dead on exit.
                Thread {
                    try {
                        while (r.readLine() != null) { /* discard */ }
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
