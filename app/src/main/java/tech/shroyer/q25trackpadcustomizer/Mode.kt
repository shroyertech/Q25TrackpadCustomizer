package tech.shroyer.q25trackpadcustomizer

/**
 * Logical trackpad mode.
 * Scroll Wheel is layered on top of Keyboard hardware mode.
 */
enum class Mode(val prefValue: Int, val procValue: String?) {
    MOUSE(0, "0"),
    KEYBOARD(1, "1"),
    FOLLOW_SYSTEM(2, null),
    SCROLL_WHEEL(3, "1");

    companion object {

        fun fromPrefValue(value: Int): Mode {
            return when (value) {
                0 -> MOUSE
                1 -> KEYBOARD
                2 -> FOLLOW_SYSTEM
                3 -> SCROLL_WHEEL
                else -> MOUSE
            }
        }

        /**
         * /proc does not distinguish Keyboard vs Scroll Wheel, because Scroll Wheel is not a built in option on Q25.
         * Returns KEYBOARD for "1".
         */
        fun fromProcValue(value: String?): Mode? {
            return when (value) {
                "0" -> MOUSE
                "1" -> KEYBOARD
                else -> null
            }
        }
    }
}