package tech.shroyer.q25trackpadcustomizer

// Theme preference for the app UI
enum class ThemePref(val prefValue: Int) {
    FOLLOW_SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromPrefValue(value: Int): ThemePref {
            return when (value) {
                1 -> LIGHT
                2 -> DARK
                else -> FOLLOW_SYSTEM
            }
        }
    }
}