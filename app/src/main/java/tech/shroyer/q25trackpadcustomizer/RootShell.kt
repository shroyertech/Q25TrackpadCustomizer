package tech.shroyer.q25trackpadcustomizer

import android.os.SystemClock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A single long-lived `su` shell that commands are piped into.
 *
 * Spawning `su -c ...` for every mode change costs a process fork, a root-manager
 * round trip (and often a "granted superuser" toast/log entry) each time, which is
 * where most of the app-switch latency came from. Keeping one shell open makes each
 * command a pipe write + read (a few ms).
 *
 * Failsafes:
 *  - Every command has a watchdog; a hung shell is killed and respawned on next use.
 *  - If the persistent shell can't be used, callers fall back to a one-shot `su -c`
 *    (also with a timeout), so behavior never gets worse than before.
 *  - After a root failure, attempts back off briefly so a denied/missing root
 *    doesn't fork `su` on every accessibility event.
 *
 * All methods are blocking: never call them on the main thread.
 */
object RootShell {

    data class Result(val ok: Boolean, val output: String)

    private const val FIRST_COMMAND_TIMEOUT_MS = 20_000L // room for a root-grant prompt
    private const val COMMAND_TIMEOUT_MS = 3_000L
    private const val ONE_SHOT_TIMEOUT_MS = 5_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MIN_BACKOFF_MS = 1_000L

    private val lock = Any()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "q25-root-watchdog").apply { isDaemon = true }
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var commandsRun = 0L
    private var seq = 0L

    @Volatile
    private var backoffUntilUptime = 0L
    private var backoffMs = MIN_BACKOFF_MS

    /** Run [cmd] as root. Tries the persistent shell first, then a one-shot `su -c`. */
    fun run(cmd: String): Result {
        if (SystemClock.uptimeMillis() < backoffUntilUptime) return Result(false, "")

        val persistent = runPersistent(cmd)
        if (persistent != null) {
            onSuccess()
            return persistent
        }

        val oneShot = runOneShot(cmd)
        if (oneShot.ok) onSuccess() else onFailure()
        return oneShot
    }

    /** Clear any backoff, e.g. when the user explicitly asks for an action. */
    fun resetBackoff() {
        backoffUntilUptime = 0L
        backoffMs = MIN_BACKOFF_MS
    }

    /** Close the persistent shell. It is reopened lazily on the next command. */
    fun close() {
        synchronized(lock) { destroyLocked() }
    }

    private fun onSuccess() {
        backoffMs = MIN_BACKOFF_MS
        backoffUntilUptime = 0L
    }

    private fun onFailure() {
        backoffUntilUptime = SystemClock.uptimeMillis() + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Returns null when the persistent shell couldn't run the command at all
     * (so the caller should fall back), or a Result with the command's exit status.
     */
    private fun runPersistent(cmd: String): Result? {
        synchronized(lock) {
            return runPersistentLocked(cmd)
        }
    }

    private fun runPersistentLocked(cmd: String): Result? {
        if (!ensureShellLocked()) return null

        val p = process ?: return null
        val w = writer ?: return null
        val r = reader ?: return null

        val marker = "__Q25_DONE_${++seq}__"
        val timeout = if (commandsRun == 0L) FIRST_COMMAND_TIMEOUT_MS else COMMAND_TIMEOUT_MS
        val killer: ScheduledFuture<*> = watchdog.schedule({ p.destroy() }, timeout, TimeUnit.MILLISECONDS)

        try {
            // stdin is closed for the command so nothing can block waiting on it. The exit code
            // is captured before the newline echo (which guarantees the marker starts a line).
            w.write("$cmd </dev/null 2>/dev/null\n__q25_rc=\$?\necho \"\"\necho \"$marker \$__q25_rc\"\n")
            w.flush()

            val out = StringBuilder()
            var line = r.readLine()
            while (line != null) {
                if (line.startsWith(marker)) {
                    val code = line.substring(marker.length).trim().toIntOrNull() ?: -1
                    commandsRun++
                    return Result(code == 0, out.toString().trim())
                }
                out.append(line).append('\n')
                line = r.readLine()
            }
            // Shell exited (or was killed by the watchdog) before finishing the command.
            destroyLocked()
            return null
        } catch (_: Exception) {
            destroyLocked()
            return null
        } finally {
            killer.cancel(false)
        }
    }

    private fun ensureShellLocked(): Boolean {
        val existing = process
        if (existing != null && isAlive(existing)) return true
        destroyLocked()

        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            reader = BufferedReader(InputStreamReader(p.inputStream))
            commandsRun = 0L
            true
        } catch (_: Exception) {
            destroyLocked()
            false
        }
    }

    private fun destroyLocked() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        writer = null
        reader = null
        process = null
    }

    private fun isAlive(p: Process): Boolean {
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun runOneShot(cmd: String): Result {
        var p: Process? = null
        val killer: ScheduledFuture<*>?
        return try {
            p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val proc = p
            killer = watchdog.schedule({ proc.destroy() }, ONE_SHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try {
                try { proc.outputStream.close() } catch (_: Exception) {}
                val output = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                Result(exit == 0, output.trim())
            } finally {
                killer.cancel(false)
            }
        } catch (_: Exception) {
            try { p?.destroy() } catch (_: Exception) {}
            Result(false, "")
        }
    }
}
