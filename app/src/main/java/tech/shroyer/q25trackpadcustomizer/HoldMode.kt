package tech.shroyer.q25trackpadcustomizer

/**
 * Hold-key target mode. "Follow system" is represented by a missing per-app override key.
 */
enum class HoldMode(val prefValue: Int) {
    DISABLED(0),
    MOUSE(1),
    KEYBOARD(2),
    SCROLL_WHEEL(3);

    companion object {
        fun fromPrefValue(value: Int): HoldMode {
            return when (value) {
                0 -> DISABLED
                1 -> MOUSE
                2 -> KEYBOARD
                3 -> SCROLL_WHEEL
                else -> DISABLED
            }
        }
    }
}