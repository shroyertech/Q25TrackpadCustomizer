package tech.shroyer.q25trackpadcustomizer

import android.app.Activity
import android.os.Bundle

/**
 * Quick toggle entry point for shortcuts/keymappers. No UI is shown.
 */
class QuickToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)

        val targetPackage = AppState.currentForegroundPackage
        if (!targetPackage.isNullOrEmpty()) {
            AppState.manualOverrideForPackage = targetPackage
        }

        val currentLogical =
            AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(prefs, targetPackage)

        val nextMode = when (currentLogical) {
            Mode.MOUSE -> Mode.KEYBOARD
            Mode.KEYBOARD -> Mode.SCROLL_WHEEL
            Mode.SCROLL_WHEEL -> Mode.MOUSE
            Mode.FOLLOW_SYSTEM -> Mode.MOUSE
        }

        val success = TrackpadController.setModeValue(nextMode)

        if (!success) {
            NotificationHelper.showRootError(
                this,
                "Trackpad toggle failed (root error). Please grant root access."
            )
        } else {
            NotificationHelper.clearRootError(this)

            AppState.currentMode = nextMode
            prefs.setLastKnownMode(nextMode)

            if (prefs.isToastQuickToggleEnabled()) {
                ToastHelper.show(this, "Toggled to ${modeLabel(nextMode)} Mode!")
            }
        }

        finish()
    }

    private fun getEffectiveModeForPackage(prefs: Prefs, packageName: String?): Mode {
        if (packageName.isNullOrEmpty()) return prefs.getSystemDefaultMode()
        return when (val appMode = prefs.getAppMode(packageName)) {
            Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> appMode
            Mode.FOLLOW_SYSTEM, null -> prefs.getSystemDefaultMode()
        }
    }

    private fun modeLabel(mode: Mode): String {
        return when (mode) {
            Mode.MOUSE -> "Mouse"
            Mode.KEYBOARD -> "Keyboard"
            Mode.SCROLL_WHEEL -> "Scroll wheel"
            Mode.FOLLOW_SYSTEM -> "System"
        }
    }
}