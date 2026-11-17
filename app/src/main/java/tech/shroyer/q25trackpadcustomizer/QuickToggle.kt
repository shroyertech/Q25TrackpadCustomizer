package tech.shroyer.q25trackpadcustomizer

import android.content.Context

/**
 * Quick Toggle: cycle the trackpad to the next mode in this app's Quick Toggle set.
 * Used by [QuickToggleActivity] (shortcuts / key mapper apps) and by the double-tap
 * trackpad gesture in [AppSwitchService].
 */
object QuickToggle {

    /** Switch to the next mode. Returns the mode switched to. Never blocks on root. */
    fun perform(context: Context): Mode {
        val prefs = Prefs(context)

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

        // Update logical state right away and write the hardware in the background, so this
        // (invisible) activity never blocks on root. Explicit user action: retry root now even
        // if a previous attempt failed recently.
        val previousMode = AppState.currentMode
        android.util.Log.i("Q25Mode", "quickToggle $currentLogical -> $nextMode fg=$targetPackage")
        AppState.currentMode = nextMode
        prefs.setLastKnownMode(nextMode)

        val appContext = context.applicationContext ?: context
        val showToast = prefs.isToastQuickToggleEnabled()
        RootShell.resetBackoff()
        TrackpadController.requestMode(nextMode) { ok ->
            if (ok) {
                NotificationHelper.clearRootError(appContext)
                if (showToast) {
                    ToastHelper.show(appContext, "Toggled to ${modeLabel(nextMode)} Mode!")
                }
            } else {
                if (AppState.currentMode == nextMode) {
                    AppState.currentMode = previousMode
                    prefs.setLastKnownMode(previousMode)
                }
                NotificationHelper.showRootError(
                    appContext,
                    "Trackpad toggle failed (root error). Please grant root access."
                )
            }
        }

        return nextMode
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
