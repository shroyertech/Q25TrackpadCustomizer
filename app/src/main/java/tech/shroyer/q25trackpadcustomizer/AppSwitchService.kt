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
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Tracks foreground app changes and key events, applying trackpad mode and scroll behavior.
 * Supports temporary Keyboard override while editing text, plus hold-key temporary switching.
 *
 * Updated:
 *  - Secondary Hold support (separate mode/key/allow-in-text/double-press).
 *  - Optional "require double press + hold" for Primary Hold and Secondary Hold.
 *  - Optional "Secondary Hold is triggered by Primary Hold double-press + hold".
 */
class AppSwitchService : AccessibilityService() {

    private lateinit var prefs: Prefs

    private var lastAppliedPackage: String? = null
    private var lastAppliedMode: Mode? = null

    private var imePackages: Set<String> = emptySet()

    private var lastTextInteractionUptime: Long = 0L

    private val contentCheckHandler = Handler(Looper.getMainLooper())
    private var contentCheckScheduled = false
    private val contentCheckRunnable = Runnable {
        contentCheckScheduled = false
        handleContentChangedCheck()
    }

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

    // ---------- HOLD RUNTIME STATE ----------

    private val holdHandler = Handler(Looper.getMainLooper())

    private data class HoldRuntimeState(
        val id: Int,
        var active: Boolean = false,
        var keyDown: Int? = null,
        var targetMode: Mode? = null,
        var waitingSecondPress: Boolean = false,
        var lastUpUptime: Long = 0L,
        var pendingSingleHoldRunnable: Runnable? = null
    )

    private val hold1 = HoldRuntimeState(id = 1)
    private val hold2 = HoldRuntimeState(id = 2)

    private var lastActivatedHoldSlot: Int = 0

    private val keyInjectExec = Executors.newSingleThreadExecutor()

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
        cancelHoldPendingRunnables()
        keyInjectExec.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return

                if (isImePackage(pkg)) {
                    lastTextInteractionUptime = SystemClock.uptimeMillis()
                    val fg = AppState.currentForegroundPackage
                    if (isAutoKeyboardEnabledForPackage(fg)) {
                        enterTextInputOverrideIfNeeded()
                    }
                    return
                }

                handleForegroundAppChanged(pkg)
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

        if (handleHoldKeyEvents(event, fg)) {
            return false
        }

        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            handleEndOfTextInputCheck()
            return false
        }

        if (shouldRemapMetaDpadToPlain(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                injectPlainDpad(event.keyCode)
            }
            return true
        }

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

    private fun shouldRemapMetaDpadToPlain(event: KeyEvent): Boolean {
        if (!isAnyHoldActive()) return false
        if (AppState.currentMode != Mode.KEYBOARD) return false

        val isDpad = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
        if (!isDpad) return false

        return event.isShiftPressed || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed
    }

    private fun injectPlainDpad(keyCode: Int) {
        keyInjectExec.execute {
            execSu("input keyevent $keyCode")
        }
    }

    private fun execSu(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            try {
                p.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
            }
            try {
                p.errorStream.bufferedReader().readText()
            } catch (_: Exception) {
            }
            p.waitFor() == 0
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            false
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

        if (!isAutoKeyboardEnabledForPackage(fg) || (fg != null && prefs.getExcludedPackages().contains(fg))) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        if (hasFocusedTextInput()) {
            enterTextInputOverrideIfNeeded()
        }
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

        val anyHoldActive = isAnyHoldActive()

        val prevMode = if (anyHoldActive) {
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
            val desiredProc = procValueForMode(Mode.KEYBOARD)
            val currentProc = if (desiredProc != null) TrackpadController.readValue() else null
            if (desiredProc != null && currentProc != null && currentProc != desiredProc) {
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
            }

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

        if (isAnyHoldActive()) {
            clearAllHoldState()
        }

        val desiredProc = procValueForMode(targetMode)
        val currentProc = if (desiredProc != null) TrackpadController.readValue() else null

        val alreadyCorrect = (AppState.currentMode == targetMode) &&
                (desiredProc == null || currentProc == null || currentProc == desiredProc)

        if (!alreadyCorrect) {
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

    // ---------- HOLD-KEY MODE SWITCHING (PRIMARY Hold + SECONDARY Hold) ----------

    private fun handleHoldKeyEvents(event: KeyEvent, fg: String?): Boolean {
        val now = SystemClock.uptimeMillis()

        val hold1KeyCode = prefs.getEffectiveHoldKeyCode(fg)

        val hold2Mode = prefs.getEffectiveHold2Mode(fg)
        val hold2UseHold1Double = prefs.isEffectiveHold2UseHold1DoublePressHold(fg)

        val tieHold2ToHold1Double = hold2UseHold1Double && hold2Mode != HoldMode.DISABLED

        val hold2KeyCode = if (tieHold2ToHold1Double) hold1KeyCode else prefs.getEffectiveHold2KeyCode(fg)

        val matchesHold1Key = (event.keyCode == hold1KeyCode)
        val matchesHold2Key = (!tieHold2ToHold1Double && event.keyCode == hold2KeyCode)

        if (!matchesHold1Key && !matchesHold2Key) return false

        if (!tieHold2ToHold1Double && hold1KeyCode == hold2KeyCode && matchesHold1Key) {
            handleHoldSingleHandler(
                event = event,
                fg = fg,
                state = hold1,
                holdMode = prefs.getEffectiveHoldMode(fg),
                allowInText = prefs.isEffectiveHoldAllowedInTextFields(fg),
                requireDouble = prefs.isEffectiveHoldDoublePressRequired(fg),
                toastPrefix = "Primary Hold"
            )
            return true
        }

        if (tieHold2ToHold1Double && matchesHold1Key) {
            handleHold1KeyWithHold2DoubleGesture(event, fg, now, hold1KeyCode)
            return true
        }

        if (matchesHold1Key) {
            handleHoldSingleHandler(
                event = event,
                fg = fg,
                state = hold1,
                holdMode = prefs.getEffectiveHoldMode(fg),
                allowInText = prefs.isEffectiveHoldAllowedInTextFields(fg),
                requireDouble = prefs.isEffectiveHoldDoublePressRequired(fg),
                toastPrefix = "Primary Hold"
            )
            return true
        }

        if (matchesHold2Key) {
            handleHoldSingleHandler(
                event = event,
                fg = fg,
                state = hold2,
                holdMode = hold2Mode,
                allowInText = prefs.isEffectiveHold2AllowedInTextFields(fg),
                requireDouble = prefs.isEffectiveHold2DoublePressRequired(fg),
                toastPrefix = "Secondary Hold"
            )
            return true
        }

        return false
    }

    private fun handleHoldSingleHandler(
        event: KeyEvent,
        fg: String?,
        state: HoldRuntimeState,
        holdMode: HoldMode,
        allowInText: Boolean,
        requireDouble: Boolean,
        toastPrefix: String
    ) {
        if (holdMode == HoldMode.DISABLED) return
        if (!allowInText && hasFocusedTextInput()) return

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return

        val now = SystemClock.uptimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (state.active && state.keyDown == event.keyCode) return

                if (requireDouble) {
                    val within = state.waitingSecondPress && (now - state.lastUpUptime) <= DOUBLE_PRESS_TIMEOUT_MS
                    if (within) {
                        state.waitingSecondPress = false
                        startHold(state, fg, event.keyCode, holdMode, toastPrefix)
                    } else {
                        state.keyDown = event.keyCode
                        state.waitingSecondPress = false
                        state.lastUpUptime = 0L
                        syncHoldStateToAppState()
                    }
                } else {
                    startHold(state, fg, event.keyCode, holdMode, toastPrefix)
                }
            }

            KeyEvent.ACTION_UP -> {
                if (state.active && state.keyDown == event.keyCode) {
                    endHold(state, fg)
                    return
                }

                if (requireDouble && state.keyDown == event.keyCode) {
                    state.keyDown = null
                    state.waitingSecondPress = true
                    state.lastUpUptime = now
                    syncHoldStateToAppState()
                }
            }
        }
    }

    private fun handleHold1KeyWithHold2DoubleGesture(event: KeyEvent, fg: String?, now: Long, hold1KeyCode: Int) {
        val hold1Mode = prefs.getEffectiveHoldMode(fg)
        val hold2Mode = prefs.getEffectiveHold2Mode(fg)

        val hold1AllowInText = prefs.isEffectiveHoldAllowedInTextFields(fg)
        val hold2AllowInText = prefs.isEffectiveHold2AllowedInTextFields(fg)

        val canRunHold1 = hold1Mode != HoldMode.DISABLED
        val canRunHold2 = hold2Mode != HoldMode.DISABLED

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (canRunHold2) {
                    val within = hold2.waitingSecondPress && (now - hold2.lastUpUptime) <= DOUBLE_PRESS_TIMEOUT_MS
                    if (within) {
                        hold2.waitingSecondPress = false
                        cancelPendingSingleHold(hold1)

                        if (!hold2AllowInText && hasFocusedTextInput()) return
                        startHold(hold2, fg, hold1KeyCode, hold2Mode, "Secondary Hold")
                        return
                    }
                }

                if (canRunHold1) {
                    if (!hold1AllowInText && hasFocusedTextInput()) return
                    scheduleDelayedSingleHold1Start(fg, hold1KeyCode, hold1Mode)
                    hold1.keyDown = hold1KeyCode
                    syncHoldStateToAppState()
                }
            }

            KeyEvent.ACTION_UP -> {
                if (hold2.active && hold2.keyDown == hold1KeyCode) {
                    endHold(hold2, fg)
                    return
                }

                val hold1WasActive = hold1.active && hold1.keyDown == hold1KeyCode
                cancelPendingSingleHold(hold1)

                if (hold1WasActive) {
                    endHold(hold1, fg)
                    return
                }

                if (canRunHold2) {
                    hold2.waitingSecondPress = true
                    hold2.lastUpUptime = now
                }

                hold1.keyDown = null
                syncHoldStateToAppState()
            }
        }
    }

    private fun scheduleDelayedSingleHold1Start(fg: String?, keyCode: Int, holdMode: HoldMode) {
        cancelPendingSingleHold(hold1)

        val r = Runnable {
            if (hold1.active) return@Runnable
            if (hold2.active) return@Runnable
            if (hold1.keyDown != keyCode) return@Runnable
            startHold(hold1, fg, keyCode, holdMode, "Primary Hold")
        }

        hold1.pendingSingleHoldRunnable = r
        holdHandler.postDelayed(r, SINGLE_HOLD_ACTIVATE_DELAY_MS)
    }

    private fun cancelPendingSingleHold(state: HoldRuntimeState) {
        state.pendingSingleHoldRunnable?.let { holdHandler.removeCallbacks(it) }
        state.pendingSingleHoldRunnable = null
    }

    private fun cancelHoldPendingRunnables() {
        cancelPendingSingleHold(hold1)
        cancelPendingSingleHold(hold2)
    }

    private fun isAnyHoldActive(): Boolean {
        return hold1.active || hold2.active
    }

    private fun clearAllHoldState() {
        hold1.active = false
        hold1.keyDown = null
        hold1.targetMode = null
        hold1.waitingSecondPress = false
        hold1.lastUpUptime = 0L
        cancelPendingSingleHold(hold1)

        hold2.active = false
        hold2.keyDown = null
        hold2.targetMode = null
        hold2.waitingSecondPress = false
        hold2.lastUpUptime = 0L
        cancelPendingSingleHold(hold2)

        AppState.activeHoldSlot = 0
        AppState.hold1Active = false
        AppState.hold1KeyCodeDown = null
        AppState.modeBeforeHold1 = null
        AppState.hold2Active = false
        AppState.hold2KeyCodeDown = null
        AppState.modeBeforeHold2 = null
        AppState.hold1DoublePressWaiting = false
        AppState.hold1FirstTapUpUptime = 0L
        AppState.hold2DoublePressWaiting = false
        AppState.hold2FirstTapUpUptime = 0L
    }

    private fun syncHoldStateToAppState() {
        AppState.activeHoldSlot = when {
            hold1.active && hold2.active -> lastActivatedHoldSlot.coerceIn(1, 2)
            hold2.active -> 2
            hold1.active -> 1
            else -> 0
        }

        AppState.hold1Active = hold1.active
        AppState.hold1KeyCodeDown = hold1.keyDown
        AppState.hold2Active = hold2.active
        AppState.hold2KeyCodeDown = hold2.keyDown

        AppState.hold1DoublePressWaiting = hold1.waitingSecondPress
        AppState.hold1FirstTapUpUptime = hold1.lastUpUptime
        AppState.hold2DoublePressWaiting = hold2.waitingSecondPress
        AppState.hold2FirstTapUpUptime = hold2.lastUpUptime
    }

    private fun procValueForMode(mode: Mode): String? {
        return when (mode) {
            Mode.MOUSE -> "0"
            Mode.KEYBOARD, Mode.SCROLL_WHEEL -> "1"
            Mode.FOLLOW_SYSTEM -> null
        }
    }

    private fun startHold(state: HoldRuntimeState, fg: String?, keyCode: Int, holdMode: HoldMode, toastPrefix: String) {
        val baseMode =
            AppState.currentMode
                ?: prefs.getLastKnownMode()
                ?: getEffectiveModeForPackage(fg)

        val target = computeHoldTargetMode(baseMode, holdMode) ?: return

        val hadAnyHoldBefore = (hold1.active || hold2.active)
        if (!hadAnyHoldBefore) {
            AppState.modeBeforeHoldKey = baseMode
        }

        state.active = true
        state.keyDown = keyCode
        state.targetMode = target

        lastActivatedHoldSlot = state.id
        if (state.id == 1) AppState.modeBeforeHold1 = baseMode else AppState.modeBeforeHold2 = baseMode

        syncHoldStateToAppState()

        if (AppState.currentMode == target) {
            val desiredProc = procValueForMode(target)
            val currentProc = if (desiredProc != null) TrackpadController.readValue() else null
            if (desiredProc == null || currentProc == null || currentProc == desiredProc) {
                if (prefs.isToastHoldKeyEnabled()) {
                    ToastHelper.show(this, "$toastPrefix: ${modeLabel(target)}")
                }
                return
            }
        }

        val ok = TrackpadController.setModeValue(target)
        if (!ok) {
            showRootError()

            state.active = false
            state.keyDown = null
            state.targetMode = null

            if (!(hold1.active || hold2.active)) {
                AppState.modeBeforeHoldKey = null
                AppState.modeBeforeHold1 = null
                AppState.modeBeforeHold2 = null
                lastActivatedHoldSlot = 0
            }

            syncHoldStateToAppState()
            return
        }

        NotificationHelper.clearRootError(this)
        AppState.currentMode = target

        if (prefs.isToastHoldKeyEnabled()) {
            ToastHelper.show(this, "$toastPrefix: ${modeLabel(target)}")
        }
    }

    private fun endHold(state: HoldRuntimeState, fg: String?) {
        val releasedKey = state.keyDown

        state.active = false
        state.keyDown = null
        state.targetMode = null

        if (lastActivatedHoldSlot == state.id) {
            lastActivatedHoldSlot = when {
                hold2.active -> 2
                hold1.active -> 1
                else -> 0
            }
        }

        val otherActiveTarget: Mode? = when {
            hold1.active -> hold1.targetMode
            hold2.active -> hold2.targetMode
            else -> null
        }

        val restoreTarget = when {
            otherActiveTarget != null -> otherActiveTarget
            AppState.textInputOverrideActive -> Mode.KEYBOARD
            else -> AppState.modeBeforeHoldKey ?: getEffectiveModeForPackage(fg)
        }

        if (!(hold1.active || hold2.active)) {
            AppState.modeBeforeHoldKey = null
            AppState.modeBeforeHold1 = null
            AppState.modeBeforeHold2 = null
        }

        if (!hold1.active && !hold2.active) {
            if (AppState.hold1KeyCodeDown == releasedKey) AppState.hold1KeyCodeDown = null
            if (AppState.hold2KeyCodeDown == releasedKey) AppState.hold2KeyCodeDown = null
        }

        syncHoldStateToAppState()

        if (AppState.currentMode == restoreTarget) {
            val desiredProc = procValueForMode(restoreTarget)
            val currentProc = if (desiredProc != null) TrackpadController.readValue() else null
            if (desiredProc == null || currentProc == null || currentProc == desiredProc) {
                return
            }
        }

        val ok = TrackpadController.setModeValue(restoreTarget)
        if (!ok) {
            showRootError()
            return
        }

        NotificationHelper.clearRootError(this)
        AppState.currentMode = restoreTarget
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

        if (AppState.textInputOverrideActive && prevFg != null && prevFg != packageName) {
            AppState.textInputOverrideActive = false
            AppState.modeBeforeTextInput = null
            stopTextOverrideMonitor()
        }

        if ((hold1.active || hold2.active) && prevFg != null && prevFg != packageName) {
            clearAllHoldState()
        }

        if (AppState.manualOverrideForPackage == packageName) return
        if (AppState.textInputOverrideActive || hold1.active || hold2.active) return

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
        private const val DOUBLE_PRESS_TIMEOUT_MS = 320L
        private const val SINGLE_HOLD_ACTIVATE_DELAY_MS = 120L
    }
}