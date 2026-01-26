package tech.shroyer.q25trackpadcustomizer

// Set how strong the scroll effect is for each key press
enum class ScrollSensitivity(val prefValue: Int, val steps: Int) {
    SLOW(0, 1),
    MEDIUM(1, 2),
    FAST(2, 3);

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