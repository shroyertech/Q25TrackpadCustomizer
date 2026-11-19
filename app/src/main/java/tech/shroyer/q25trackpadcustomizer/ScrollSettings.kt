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

// How each wheel tick is spread out over time (real wheel scrolling only).
// ease = fraction of the remaining queued scroll released per frame (higher = snappier).
enum class ScrollSmoothness(val prefValue: Int, val ease: Float) {
    SNAPPY(0, 0.55f),
    NORMAL(1, 0.30f),
    SMOOTH(2, 0.15f);

    companion object {
        fun fromPrefValue(value: Int): ScrollSmoothness {
            return values().firstOrNull { it.prefValue == value } ?: NORMAL
        }
    }
}

// Glide after a quick flick stops, like a touch fling (both scroll methods).
// level is sent to the wheel helper (0 = none ... 3 = longest).
enum class ScrollInertia(val prefValue: Int, val level: Int) {
    OFF(0, 0),
    LOW(1, 1),
    MEDIUM(2, 2),
    HIGH(3, 3);

    companion object {
        fun fromPrefValue(value: Int): ScrollInertia {
            return values().firstOrNull { it.prefValue == value } ?: MEDIUM
        }
    }
}

// How scroll-wheel mode moves content.
// CLASSIC: the original behaviour: each trackpad tick sends one fixed touch swipe from the
//          middle of the screen (a new tick replaces the swipe in progress), and the app's
//          own fling physics do the rest. Consistent, and needs no root.
// WHEEL: real mouse-wheel events through the root helper (falls back to DRAG without root).
// DRAG: simulated finger drags. Apps see touch swipes, so swipe gestures (e.g. a launcher's
//       swipe-up app drawer) work, but lists can hit edge gestures.
enum class ScrollMethod(val prefValue: Int) {
    WHEEL(0),
    DRAG(1),
    CLASSIC(2);

    companion object {
        fun fromPrefValue(value: Int): ScrollMethod {
            return values().firstOrNull { it.prefValue == value } ?: CLASSIC
        }
    }
}

// Bundle scroll-related options together
data class ScrollSettings(
    val sensitivity: ScrollSensitivity,
    val horizontalEnabled: Boolean,
    val invertVertical: Boolean,
    val invertHorizontal: Boolean,
    val method: ScrollMethod = ScrollMethod.CLASSIC
)