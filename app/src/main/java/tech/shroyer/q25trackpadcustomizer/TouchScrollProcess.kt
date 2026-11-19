package tech.shroyer.q25trackpadcustomizer

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs inside the root helper ([WheelInjectorMain]): starts and stops the native
 * touch-scroll program (libq25touch.so), which grabs the trackpad and turns its motion into
 * a virtual finger. Its motion reports are passed on for tap detection.
 */
class TouchScrollProcess(
    private val onRel: (Int, Int) -> Unit,
    private val onKey: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var process: Process? = null
    private var args: List<String>? = null

    /** [cmd] = helper path, device, width, height, scale, horizontal, invertV, invertH. */
    @Synchronized
    fun start(cmd: List<String>) {
        if (process != null && args == cmd) return
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
                        "r" -> {
                            val dx = parts.getOrNull(1)?.toIntOrNull() ?: continue
                            val dy = parts.getOrNull(2)?.toIntOrNull() ?: continue
                            onRel(dx, dy)
                        }
                        "b" -> onKey()
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
