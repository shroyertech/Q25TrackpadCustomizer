package tech.shroyer.q25trackpadcustomizer

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads and writes /proc/q20_switch_key_mouse (0 = Mouse, 1 = Keyboard).
 *
 * [requestMode] is the entry point: it never blocks the caller, coalesces rapid requests
 * (only the latest target is applied), and skips the write when the hardware is already
 * in the requested state. [readValue] is blocking and must not be called on the main thread.
 *
 * Root commands go through [RootShell] (persistent shell with a one-shot fallback).
 * If the /proc node happens to be directly writable, root is skipped entirely.
 */
object TrackpadController {

    private const val PROC_PATH = "/proc/q20_switch_key_mouse"

    private val procFile = File(PROC_PATH)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "q25-trackpad-io").apply { isDaemon = true }
    }

    private class Request(val procValue: String, val onResult: ((Boolean) -> Unit)?)

    private val pending = AtomicReference<Request?>(null)

    /**
     * Asynchronously make the hardware match [mode]. If another request arrives before
     * this one runs, this one is dropped (its callback is not invoked) and the newer one wins.
     * [onResult] runs on the main thread with whether the hardware now matches.
     */
    /**
     * Optional hardware value override, set by the service: Scroll wheel mode with the Touch
     * method keeps the hardware in mouse mode ("0") so the pad reports fine motion.
     */
    @Volatile
    var procValueOverride: ((Mode) -> String?)? = null

    fun requestMode(mode: Mode, onResult: ((Boolean) -> Unit)? = null) {
        val procValue = procValueOverride?.invoke(mode) ?: mode.procValue
        if (procValue == null) {
            onResult?.let { cb -> mainHandler.post { cb(false) } }
            return
        }

        val previous = pending.getAndSet(Request(procValue, onResult))
        if (previous == null) {
            try {
                worker.execute(::drainPending)
            } catch (_: Exception) {
                pending.set(null)
                onResult?.let { cb -> mainHandler.post { cb(false) } }
            }
        }
    }

    /** Start the root shell in the background so the first real switch is fast. */
    fun warmUp() {
        try {
            worker.execute { readValue() }
        } catch (_: Exception) {
        }
    }

    private fun drainPending() {
        while (true) {
            val req = pending.getAndSet(null) ?: return
            val ok = try {
                ensureProcValue(req.procValue)
            } catch (_: Exception) {
                false
            }
            req.onResult?.let { cb ->
                // Only report if nothing newer has been queued; a newer request supersedes this result.
                if (pending.get() == null) mainHandler.post { cb(ok) }
            }
        }
    }

    /**
     * Read the current value; write only if it differs. If the read fails, write anyway.
     */
    private fun ensureProcValue(value: String): Boolean {
        val current = readValue()
        if (current == value) return true
        return writeValue(value)
    }

    /** Read the current value ("0" or "1"). Blocking. */
    fun readValue(): String? {
        val raw = readDirect() ?: RootShell.run("cat $PROC_PATH").takeIf { it.ok }?.output
        val trimmed = raw?.trim()
        return if (trimmed == "0" || trimmed == "1") trimmed else null
    }

    private fun writeValue(value: String): Boolean {
        return writeDirect(value) || RootShell.run("echo $value > $PROC_PATH").ok
    }

    // Some ROMs expose the node with permissive permissions; use it without root if we can.
    private fun readDirect(): String? {
        return try {
            if (procFile.canRead()) procFile.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDirect(value: String): Boolean {
        return try {
            if (!procFile.canWrite()) return false
            procFile.writeText(value)
            true
        } catch (_: Exception) {
            false
        }
    }
}
