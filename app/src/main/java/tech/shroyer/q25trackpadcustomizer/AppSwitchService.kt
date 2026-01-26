package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

/**
 * Tracks foreground app changes and key events, applying trackpad mode and scroll behavior.
 * Supports temporary Keyboard override while editing text, plus hold-key temporary switching.
 */
class AppSwitchService : AccessibilityService() {

    private lateinit var prefs: Prefs

    private var lastAppliedPackage: String? = null
    private var lastAppliedMode: Mode? = null

    private var imePackages: Set<String> = emptySet()

    // Helps avoid false negatives when focus briefly changes during IME transitions.
    private var lastTextInteractionUptime: Long = 0L

    // Debounced “content changed” checker (avoid heavy scans for every event)
    private val contentCheckHandler = Handler(Looper.getMainLooper())
    private var contentCheckScheduled = false
    private val contentCheckRunnable = Runnable {
        contentCheckScheduled = false
        handleContentChangedCheck()
    }

    // Text-input override monitoring
    private val textOverrideHandler = Handler(Looper.getMainLooper())
    private var textOverrideMisses = 0
    private val textOverrideCheckRunnable = object : Runnable {
        override fun run() {
            if (!AppState.textInputOverrideActive) {
                textOverrideMisses = 0
                return
            }

            val fg = AppState.currentForegroundPackage
            if (!isAutoKeyboardEnabledForPackage(fg)) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
                return
            }

            // Grace window: some apps briefly drop focus/structure during IME transitions.
            val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < 1200L
            val stillEditing = hasFocusedTextInput() || recent

            if (stillEditing) {
                textOverrideMisses = 0
            } else {
                textOverrideMisses++
                if (textOverrideMisses >= 2) {
                    restoreModeAfterTextInput()
                    stopTextOverrideMonitor()
                    return
                }
            }

            textOverrideHandler.postDelayed(this, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        imePackages = loadImePackages()
    }

    override fun onDestroy() {
        stopTextOverrideMonitor()
        contentCheckHandler.removeCallbacks(contentCheckRunnable)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return

                // IME windows indicate likely active text input in the current foreground app.
                if (isImePackage(pkg)) {
                    lastTextInteractionUptime = SystemClock.uptimeMillis()
                    val fg = AppState.currentForegroundPackage
                    if (isAutoKeyboardEnabledForPackage(fg)) {
                        enterTextInputOverrideIfNeeded()
                    }
                    return
                }

                handleForegroundAppChanged(pkg)
                // Some apps don’t emit nice focused events; content check helps.
                scheduleContentCheck()
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleViewFocusedForAutoKeyboard(event)
                scheduleContentCheck()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                handleTextInteraction(event)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleContentCheck()
            }
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val fg = AppState.currentForegroundPackage

        // Hold-key switching (never consumes the key).
        val holdKeyCode = prefs.getEffectiveHoldKeyCode(fg)
        if (event.keyCode == holdKeyCode) {
            handleHoldKeyEvent(event)
            return false
        }

        // Back/Enter can help detect the end of text editing.
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            handleEndOfTextInputCheck()
            return false
        }

        // Scroll wheel mode key transformation.
        if (AppState.currentMode != Mode.SCROLL_WHEEL) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    handleScrollKey(event)
                }
                true
            }
            else -> false
        }
    }

    private fun handleScrollKey(event: KeyEvent) {
        val pkg = AppState.currentForegroundPackage
        val settings = prefs.getEffectiveScrollSettings(pkg)
        val steps = settings.sensitivity.steps

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val scrollUp = !settings.invertVertical
                scrollVertical(scrollUp, steps)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val scrollUp = settings.invertVertical
                scrollVertical(scrollUp, steps)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (settings.horizontalEnabled) {
                    val scrollLeft = !settings.invertHorizontal
                    scrollHorizontal(scrollLeft, steps)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (settings.horizontalEnabled) {
                    val scrollLeft = settings.invertHorizontal
                    scrollHorizontal(scrollLeft, steps)
                }
            }
        }
    }

    // ---------- IME / AUTO KEYBOARD FOR TEXT INPUT ----------

    private fun loadImePackages(): Set<String> {
        return try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val enabled = imm.enabledInputMethodList
            HashSet<String>().apply {
                for (ime in enabled) add(ime.packageName)
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (imePackages.contains(pkg)) return true

        // Heuristic fallback: common keyboard naming patterns
        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains("keyboard", ignoreCase = true)) return true
        if (pkg.contains("ime", ignoreCase = true)) return true

        return false
    }

    private fun isAutoKeyboardEnabledForPackage(packageName: String?): Boolean {
        return prefs.isEffectiveAutoKeyboardForTextEnabled(packageName)
    }

    private fun handleTextInteraction(event: AccessibilityEvent) {
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return

        if (fg != null && prefs.getExcludedPackages().contains(fg)) return

        lastTextInteractionUptime = SystemClock.uptimeMillis()

        val src = event.source
        val isText = if (src != null) {
            val res = isTextInputNode(src)
            src.recycle()
            res
        } else false

        if (isText) {
            enterTextInputOverrideIfNeeded()
        } else {
            // Some apps don’t provide a reliable source; follow up with a debounced check.
            scheduleContentCheck()
        }
    }

    private fun scheduleContentCheck() {
        if (contentCheckScheduled) return
        contentCheckScheduled = true
        contentCheckHandler.removeCallbacks(contentCheckRunnable)
        contentCheckHandler.postDelayed(contentCheckRunnable, 120L)
    }

    private fun handleContentChangedCheck() {
        val fg = AppState.currentForegroundPackage

        // If disabled/excluded while override is active, exit override cleanly.
        if (!isAutoKeyboardEnabledForPackage(fg) || (fg != null && prefs.getExcludedPackages().contains(fg))) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        // If we can confirm a focused text input, ensure we’re in keyboard override.
        if (hasFocusedTextInput()) {
            enterTextInputOverrideIfNeeded()
        }
        // If not, do nothing here — the override monitor will back out after misses.
    }

    private fun handleViewFocusedForAutoKeyboard(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()

        if (pkg == "android" || pkg == "com.android.systemui") return

        val excluded = prefs.getExcludedPackages()
        if (pkg.isNotEmpty() && excluded.contains(pkg)) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        if (!isAutoKeyboardEnabledForPackage(pkg)) return

        val src = event.source ?: return
        val isText = isTextInputNode(src)
        src.recycle()

        if (isText) {
            lastTextInteractionUptime = SystemClock.uptimeMillis()
            enterTextInputOverrideIfNeeded()
        }
    }

    private fun isTextInputNode(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true)) return true
        if (cls.contains("TextInput", ignoreCase = true)) return true
        if (cls.contains("AutoCompleteTextView", ignoreCase = true)) return true
        if (node.isEditable) return true

        // Some custom inputs aren’t marked editable but do support SET_TEXT.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
            }
        } catch (_: Exception) {
        }

        return false
    }

    private fun enterTextInputOverrideIfNeeded() {
        if (AppState.textInputOverrideActive) return

        val fg = AppState.currentForegroundPackage
        if (fg != null && prefs.getExcludedPackages().contains(fg)) return

        val prevMode = if (AppState.holdKeyActive) {
            // If hold-key is active, the current mode may be the temporary hold target.
            // Restore to the real base mode after text input ends.
            AppState.modeBeforeHoldKey
                ?: AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(fg)
        } else {
            AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(fg)
        }

        AppState.modeBeforeTextInput = prevMode

        if (prevMode == Mode.KEYBOARD) {
            AppState.textInputOverrideActive = true
            startTextOverrideMonitor()
            return
        }

        val ok = TrackpadController.setModeValue(Mode.KEYBOARD)
        if (!ok) {
            showRootError()
            AppState.modeBeforeTextInput = null
            AppState.textInputOverrideActive = false
            stopTextOverrideMonitor()
            return
        }

        NotificationHelper.clearRootError(this)
        AppState.currentMode = Mode.KEYBOARD
        AppState.textInputOverrideActive = true
        startTextOverrideMonitor()
    }

    private fun restoreModeAfterTextInput() {
        val fg = AppState.currentForegroundPackage
        val targetMode = AppState.modeBeforeTextInput ?: getEffectiveModeForPackage(fg)

        // IMEs can swallow modifier KEY_UP events (Shift, etc), leaving hold mode "stuck".
        // When text input ends, force-clear hold state so we can return to the app/base mode.
        if (AppState.holdKeyActive) {
            AppState.holdKeyActive = false
            AppState.holdKeyCodeDown = null
            AppState.modeBeforeHoldKey = null
        }

        if (AppState.currentMode != targetMode) {
            val ok = TrackpadController.setModeValue(targetMode)
            if (!ok) {
                showRootError()
            } else {
                NotificationHelper.clearRootError(this)
                AppState.currentMode = targetMode
            }
        }

        AppState.modeBeforeTextInput = null
        AppState.textInputOverrideActive = false
    }

    private fun handleEndOfTextInputCheck() {
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return
        if (!AppState.textInputOverrideActive) return

        val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < 1200L
        if (!hasFocusedTextInput() && !recent) {
            restoreModeAfterTextInput()
            stopTextOverrideMonitor()
        }
    }

    private fun startTextOverrideMonitor() {
        textOverrideMisses = 0
        textOverrideHandler.removeCallbacks(textOverrideCheckRunnable)
        textOverrideHandler.postDelayed(textOverrideCheckRunnable, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
    }

    private fun stopTextOverrideMonitor() {
        textOverrideHandler.removeCallbacks(textOverrideCheckRunnable)
        textOverrideMisses = 0
    }

    private fun hasFocusedTextInput(): Boolean {
        val root = rootInActiveWindow ?: return false

        // Fast path: input focus (best signal when available)
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }

        if (focused != null) {
            val res = isTextInputNode(focused)
            focused.recycle()
            root.recycle()
            return res
        }

        // Fallback: BFS scan for focused editable/text-like nodes
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val focusedNow = node.isFocused || node.isAccessibilityFocused
            if (focusedNow && isTextInputNode(node)) {
                node.recycle()
                recycleQueue(queue)
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            node.recycle()
        }

        return false
    }

    private fun recycleQueue(queue: ArrayDeque<AccessibilityNodeInfo>) {
        while (queue.isNotEmpty()) {
            queue.removeFirst().recycle()
        }
    }

    // ---------- HOLD-KEY MODE SWITCHING ----------

    private fun handleHoldKeyEvent(event: KeyEvent) {
        val fg = AppState.currentForegroundPackage

        val holdMode = prefs.getEffectiveHoldMode(fg)
        if (holdMode == HoldMode.DISABLED) return

        val allowInText = prefs.isEffectiveHoldAllowedInTextFields(fg)
        if (!allowInText && hasFocusedTextInput()) return

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (AppState.holdKeyActive && AppState.holdKeyCodeDown == event.keyCode) return

                val baseMode =
                    AppState.currentMode
                        ?: prefs.getLastKnownMode()
                        ?: getEffectiveModeForPackage(fg)

                val target = computeHoldTargetMode(baseMode, holdMode) ?: return

                AppState.modeBeforeHoldKey = baseMode
                AppState.holdKeyActive = true
                AppState.holdKeyCodeDown = event.keyCode

                if (AppState.currentMode == target) return

                val ok = TrackpadController.setModeValue(target)
                if (!ok) {
                    showRootError()
                    AppState.holdKeyActive = false
                    AppState.holdKeyCodeDown = null
                    AppState.modeBeforeHoldKey = null
                    return
                }

                NotificationHelper.clearRootError(this)
                AppState.currentMode = target

                if (prefs.isToastHoldKeyEnabled()) {
                    ToastHelper.show(this, "Hold: ${modeLabel(target)}")
                }
            }

            KeyEvent.ACTION_UP -> {
                if (!AppState.holdKeyActive || AppState.holdKeyCodeDown != event.keyCode) return

                AppState.holdKeyActive = false
                AppState.holdKeyCodeDown = null

                val target = if (AppState.textInputOverrideActive) {
                    Mode.KEYBOARD
                } else {
                    AppState.modeBeforeHoldKey ?: getEffectiveModeForPackage(fg)
                }

                AppState.modeBeforeHoldKey = null

                if (AppState.currentMode == target) return

                val ok = TrackpadController.setModeValue(target)
                if (!ok) {
                    showRootError()
                    return
                }

                NotificationHelper.clearRootError(this)
                AppState.currentMode = target
            }
        }
    }

    private fun computeHoldTargetMode(base: Mode, holdMode: HoldMode): Mode? {
        val desired = when (holdMode) {
            HoldMode.DISABLED -> null
            HoldMode.MOUSE -> Mode.MOUSE
            HoldMode.KEYBOARD -> Mode.KEYBOARD
            HoldMode.SCROLL_WHEEL -> Mode.SCROLL_WHEEL
        } ?: return null

        if (desired != base) return desired

        return when (base) {
            Mode.MOUSE -> Mode.KEYBOARD
            Mode.KEYBOARD -> Mode.MOUSE
            Mode.SCROLL_WHEEL -> Mode.KEYBOARD
            Mode.FOLLOW_SYSTEM -> Mode.KEYBOARD
        }
    }

    // ---------- SCROLL HELPERS ----------

    private fun scrollVertical(up: Boolean, steps: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val ok = performVerticalGesture(up, steps)
            if (!ok) scrollVerticalNodeBased(up, steps)
        } else {
            scrollVerticalNodeBased(up, steps)
        }
    }

    private fun scrollHorizontal(left: Boolean, steps: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val ok = performHorizontalGesture(left, steps)
            if (!ok) scrollHorizontalNodeBased(left, steps)
        } else {
            scrollHorizontalNodeBased(left, steps)
        }
    }

    private fun buildGestureStroke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): GestureDescription {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun performVerticalGesture(up: Boolean, steps: Int): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        val baseDistance = height * 0.15f
        val distance = baseDistance * steps
        val baseDuration = 120L
        val duration = baseDuration * steps

        val centerX = width / 2f
        val centerY = height / 2f

        val (startY, endY) = if (up) {
            (centerY + distance / 2f) to (centerY - distance / 2f)
        } else {
            (centerY - distance / 2f) to (centerY + distance / 2f)
        }

        val gesture = buildGestureStroke(centerX, startY, centerX, endY, duration)
        return dispatchGesture(gesture, null, null)
    }

    private fun performHorizontalGesture(left: Boolean, steps: Int): Boolean {
        val dm = resources.displayMetrics
        val width = dm.widthPixels.toFloat()

        val baseDistance = width * 0.15f
        val distance = baseDistance * steps
        val baseDuration = 120L
        val duration = baseDuration * steps

        val centerX = width / 2f
        val centerY = resources.displayMetrics.heightPixels.toFloat() / 2f

        val (startX, endX) = if (left) {
            (centerX + distance / 2f) to (centerX - distance / 2f)
        } else {
            (centerX - distance / 2f) to (centerX + distance / 2f)
        }

        val gesture = buildGestureStroke(startX, centerY, endX, centerY, duration)
        return dispatchGesture(gesture, null, null)
    }

    private fun scrollVerticalNodeBased(up: Boolean, steps: Int) {
        val node = findScrollableNode() ?: return
        val action = if (up) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        repeat(steps) { node.performAction(action) }
        node.recycle()
    }

    private fun scrollHorizontalNodeBased(left: Boolean, steps: Int) {
        val node = findScrollableNode() ?: return
        val action = if (left) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        repeat(steps) { node.performAction(action) }
        node.recycle()
    }

    private fun findScrollableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            if (node.isScrollable) {
                recycleQueue(queue)
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            node.recycle()
        }

        return null
    }

    // ---------- APP SWITCH / MODE LOGIC ----------

    private fun handleForegroundAppChanged(packageName: String) {
        if (packageName == this.packageName) return
        if (packageName == "android" || packageName == "com.android.systemui") return
        if (isImePackage(packageName)) return

        val excluded = prefs.getExcludedPackages()
        if (excluded.contains(packageName)) return

        val prevFg = AppState.currentForegroundPackage
        AppState.currentForegroundPackage = packageName

        val overridePkg = AppState.manualOverrideForPackage
        if (overridePkg != null && overridePkg != packageName) {
            AppState.manualOverrideForPackage = null
        }

        // Clear text-input override when switching apps.
        if (AppState.textInputOverrideActive && prevFg != null && prevFg != packageName) {
            AppState.textInputOverrideActive = false
            AppState.modeBeforeTextInput = null
            stopTextOverrideMonitor()
        }

        // Clear hold-key override when switching apps.
        if (AppState.holdKeyActive && prevFg != null && prevFg != packageName) {
            AppState.holdKeyActive = false
            AppState.holdKeyCodeDown = null
            AppState.modeBeforeHoldKey = null
        }

        // Keep manual override active while staying in the same app.
        if (AppState.manualOverrideForPackage == packageName) return

        // Avoid stomping temporary overrides on extra window events within the same app.
        if (AppState.textInputOverrideActive || AppState.holdKeyActive) return

        val (effectiveMode, isDefault) = when (val appMode = prefs.getAppMode(packageName)) {
            Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> appMode to false
            Mode.FOLLOW_SYSTEM, null -> prefs.getSystemDefaultMode() to true
        }

        val currentMode = AppState.currentMode
        if (isDefault && currentMode != null && currentMode == effectiveMode) {
            lastAppliedPackage = packageName
            lastAppliedMode = effectiveMode
            return
        }

        if (lastAppliedPackage == packageName && lastAppliedMode == effectiveMode) return

        val ok = TrackpadController.setModeValue(effectiveMode)
        val appLabel = getAppLabel(packageName)

        if (!ok) {
            showRootError()
            return
        }

        NotificationHelper.clearRootError(this)

        val showPerAppToast = prefs.isToastPerAppEnabled()
        val showDefaultToast = prefs.isToastDefaultModeEnabled()

        val message: String? = when {
            isDefault && showDefaultToast -> "Switching to default mode! (${modeLabel(effectiveMode)})"
            !isDefault && showPerAppToast -> {
                if (appLabel != null) "Switching to ${modeLabel(effectiveMode)} Mode for $appLabel!"
                else "Switching to ${modeLabel(effectiveMode)} Mode!"
            }
            else -> null
        }

        if (message != null) {
            ToastHelper.show(this, message)
        }

        lastAppliedPackage = packageName
        lastAppliedMode = effectiveMode
        AppState.currentMode = effectiveMode

        // Persist logical mode for reliable restore (not used for temporary overrides).
        prefs.setLastKnownMode(effectiveMode)
    }

    private fun getEffectiveModeForPackage(packageName: String?): Mode {
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

    private fun getAppLabel(packageName: String): String? {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun showRootError() {
        NotificationHelper.showRootError(
            this,
            "Root access failed. Please grant root to Q25 Trackpad Customizer."
        )
    }

    companion object {
        private const val TEXT_OVERRIDE_CHECK_INTERVAL_MS = 350L
    }
}