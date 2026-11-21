package tech.shroyer.q25trackpadcustomizer

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs inside the root helper ([WheelInjectorMain]): starts and stops the native
 * touch-scroll program (libq25touch.so), which grabs the trackpad and turns its motion into
 * a virtual finger.
 */
class TouchScrollProcess(
    private val onStatus: (String) -> Unit
) {
    private var process: Process? = null

    /** Pass motion/finger lines on as "ev ..." (for the scroll practice log). */
    @Volatile
    var verbose = false
    private var args: List<String>? = null

    /** [cmd] = helper path, device, width, height, scale, horizontal, invertV, invertH. */
    @Synchronized
    fun start(cmd: List<String>) {
        if (process != null && args == cmd) return
        // Only speed / direction changed: tell the running program instead of restarting it,
        // so the trackpad is never released in between.
        val p0 = process
        val a0 = args
        if (p0 != null && a0 != null && a0.size == cmd.size && cmd.size >= 8 && a0.subList(0, 4) == cmd.subList(0, 4)) {
            try {
                p0.outputStream.write("s ${cmd[4]} ${cmd[5]} ${cmd[6]} ${cmd[7]}\n".toByteArray())
                p0.outputStream.flush()
                args = cmd
                return
            } catch (_: Throwable) {
                // fall through to a restart
            }
        }
        stopLocked()
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            onStatus("err start ${t.javaClass.simpleName}")
            return
        }
        process = p
        args = cmd
        Thread({ readLoop(p) }, "touch-scroll-out").apply { isDaemon = true; start() }
        Thread({
            try {
                p.errorStream.bufferedReader().forEachLine { System.err.println("q25touch: $it") }
            } catch (_: Throwable) {
            }
        }, "touch-scroll-err").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val p = process ?: return
        process = null
        args = null
        try {
            p.outputStream.close() // stdin EOF: the program releases the trackpad and exits
        } catch (_: Throwable) {
        }
        try {
            if (!p.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy()
        } catch (_: Throwable) {
            p.destroy()
        }
        onStatus("stopped")
    }

    private fun readLoop(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val parts = line.split(' ')
                    when (parts.firstOrNull()) {
                        "r", "g", "b" -> if (verbose) onStatus("ev $line")
                        "ok" -> onStatus("ok")
                        "e" -> onStatus("err ${parts.getOrNull(1) ?: "?"} ${parts.getOrNull(2) ?: ""}".trim())
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val code = try {
            p.waitFor()
        } catch (_: Throwable) {
            -1
        }
        synchronized(this) {
            if (process === p) {
                process = null
                args = null
                onStatus("exited $code")
            }
        }
    }
}
