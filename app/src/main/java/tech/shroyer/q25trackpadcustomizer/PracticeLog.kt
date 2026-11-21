package tech.shroyer.q25trackpadcustomizer

import android.os.SystemClock

/**
 * Event log for the scroll practice page: trackpad motion from the touch-scroll helper,
 * Classic scroll keys, and what the board did. Kept in memory only, newest lines win.
 */
object PracticeLog {

    private const val MAX_LINES = 4000
    private val lines = ArrayDeque<String>()
    private var startUptime = SystemClock.uptimeMillis()

    @Synchronized
    fun clear() {
        lines.clear()
        startUptime = SystemClock.uptimeMillis()
    }

    fun add(line: String) {
        if (!AppState.practiceActive) return
        val t = SystemClock.uptimeMillis()
        synchronized(this) {
            lines.addLast("${t - startUptime} $line")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
