package tech.shroyer.q25trackpadcustomizer

// Set how strong the scroll effect is for each key press.
// steps: used by the touch-gesture fallback (no root).
// wheelNotches: mouse-wheel notches per key press when real wheel events are used.
enum class ScrollSensitivity(val prefValue: Int, val steps: Int, val wheelNotches: Float) {
    SLOW(0, 1, 0.6f),
    MEDIUM(1, 2, 1.0f),
    FAST(2, 3, 1.6f);

    companion object {
        fun fromPrefValue(value: Int): ScrollSensitivity {
            return values().firstOrNull { it.prefValue == value } ?: MEDIUM
        }
    }
}

// Bundle scroll-related options together
data class ScrollSettings(
    val sensitivity: ScrollSensitivity,
    val horizontalEnabled: Boolean,
    val invertVertical: Boolean,
    val invertHorizontal: Boolean
)