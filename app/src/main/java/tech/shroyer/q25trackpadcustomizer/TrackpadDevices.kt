package tech.shroyer.q25trackpadcustomizer

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs inside the root helper ([WheelInjectorMain]): finds the trackpad's input device and
 * can click through it (so the click lands at the real cursor position).
 */
object TrackpadDevices {

    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val BTN_LEFT = 0x110
    private const val BTN_RIGHT = 0x111
    private const val CLICK_HOLD_MS = 25L
    private const val CLICK_GAP_MS = 60L
    private val eventSize = if ((System.getProperty("os.arch") ?: "").contains("64")) 24 else 16

    /** Click [count] times with the left button, or once with the right (count 0). */
    fun click(count: Int) {
        val dev = touchDevice() ?: return
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

    /** The device the touch-scroll helper should grab: the one that really carries the pad. */
    fun touchDevice(): String? {
        val found = findPointerDevices()
        // On some builds a virtual device (under /sys/devices/virtual) carries all trackpad and
        // keyboard events while the physical one stays silent: prefer the virtual one.
        return found.firstOrNull { dev ->
            try {
                File("/sys/class/input/${File(dev).name}").canonicalPath.contains("/virtual/")
            } catch (_: Exception) {
                false
            }
        } ?: found.lastOrNull()
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
}
