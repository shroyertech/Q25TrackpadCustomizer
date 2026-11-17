package tech.shroyer.q25trackpadcustomizer

import android.view.KeyEvent

/** What a double or triple tap on the trackpad does (experimental, Mouse mode only). */
enum class PadTapAction(val prefValue: Int, val label: String, val keyCode: Int = 0) {
    NONE(0, "Do nothing"),
    QUICK_TOGGLE(1, "Quick Toggle (switch mode)"),
    CLICK(2, "Click"),
    DOUBLE_CLICK(3, "Double click (select word)"),
    TRIPLE_CLICK(4, "Triple click (select line/paragraph)"),
    RIGHT_CLICK(5, "Right click (context menu)"),
    BACK(6, "Back"),
    HOME(7, "Home"),
    RECENTS(8, "Recent apps"),
    NOTIFICATIONS(9, "Notifications"),
    SCREENSHOT(10, "Screenshot"),
    KEY_F9(11, "Send F9 (for key mapper apps)", KeyEvent.KEYCODE_F9),
    KEY_F10(12, "Send F10 (for key mapper apps)", KeyEvent.KEYCODE_F10),
    KEY_F11(13, "Send F11 (for key mapper apps)", KeyEvent.KEYCODE_F11),
    KEY_F12(14, "Send F12 (for key mapper apps)", KeyEvent.KEYCODE_F12);

    companion object {
        fun fromPrefValue(value: Int, fallback: PadTapAction): PadTapAction =
            values().firstOrNull { it.prefValue == value } ?: fallback

        /** Broadcast sent for every recognised tap gesture (extra "taps" = 2 or 3). */
        const val BROADCAST_ACTION = "tech.shroyer.q25trackpadcustomizer.PAD_TAP"
        const val EXTRA_TAPS = "taps"
        const val EXTRA_PACKAGE = "package"
    }
}

data class PadGestureSettings(
    val enabled: Boolean,
    val doubleTap: PadTapAction,
    val tripleTap: PadTapAction
)
