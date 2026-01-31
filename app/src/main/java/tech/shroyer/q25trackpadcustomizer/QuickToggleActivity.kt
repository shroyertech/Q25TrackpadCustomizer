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

        val baseMode = getEffectiveModeForPackage(prefs, targetPackage)

        val currentLogical =
            AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: baseMode

        val quickToggleModes = prefs.getEffectiveQuickToggleModes(targetPackage)
        val singleMatchFallback = prefs.getGlobalQuickToggleSingleMatchFallbackMode()

        val nextMode = computeNextQuickToggleMode(
            baseMode = baseMode,
            currentMode = currentLogical,
            selectedModes = quickToggleModes,
            singleMatchFallback = singleMatchFallback
        )

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

    private fun computeNextQuickToggleMode(
        baseMode: Mode,
        currentMode: Mode,
        selectedModes: Set<Mode>,
        singleMatchFallback: Mode
    ): Mode {
        val validModes = linkedSetOf(Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL)

        // Sanitize prefs (ignore FOLLOW_SYSTEM)
        val sanitized = selectedModes.filter { it in validModes }.toSet().ifEmpty {
            validModes // safe default if prefs are missing/corrupt
        }

        // Treat FOLLOW_SYSTEM as base for "current"
        val current = if (currentMode == Mode.FOLLOW_SYSTEM) baseMode else currentMode

        // Build cycle list in a stable order, excluding base (base is always the "return to app default" state)
        val order = listOf(Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL)
        var cycle = order.filter { it in sanitized && it != baseMode }

        // If user only selected the base mode (or ends up with no cycle modes), pick a fallback.
        if (cycle.isEmpty()) {
            val fallback = if (sanitized.size == 1 && sanitized.contains(baseMode)) {
                // Global "single match" fallback is only meaningful here
                if (singleMatchFallback != baseMode && singleMatchFallback in validModes) {
                    singleMatchFallback
                } else {
                    defaultOtherMode(baseMode)
                }
            } else {
                defaultOtherMode(baseMode)
            }
            cycle = listOf(fallback)
        }

        return when {
            current == baseMode -> {
                cycle.first()
            }
            current in cycle -> {
                val idx = cycle.indexOf(current)
                if (idx >= 0 && idx < cycle.lastIndex) cycle[idx + 1] else baseMode
            }
            else -> {
                // If we're in some unexpected state, go to the first cycle mode
                cycle.first()
            }
        }
    }

    private fun defaultOtherMode(baseMode: Mode): Mode {
        return when (baseMode) {
            Mode.MOUSE -> Mode.KEYBOARD
            Mode.KEYBOARD -> Mode.MOUSE
            Mode.SCROLL_WHEEL -> Mode.MOUSE
            Mode.FOLLOW_SYSTEM -> Mode.MOUSE
        }
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