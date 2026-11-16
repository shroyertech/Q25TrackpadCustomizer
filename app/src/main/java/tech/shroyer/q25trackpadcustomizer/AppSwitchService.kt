package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.database.ContentObserver
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
 *  - Secondary Hold support (separate mode/key/allow-in-text/double-press).
 *  - Optional "require double press + hold" for Primary Hold and Secondary Hold.
 *  - Optional "Secondary Hold is triggered by Primary Hold double-press + hold".
 *
 * Threading: everything here runs on the main thread, which is also where key events
 * are filtered. Root/proc I/O is never done here directly; it goes through
 * [TrackpadController.requestMode], which runs on a background thread and calls back.
 * Logical state (AppState.currentMode, toasts) is updated immediately so the UI and key
 * handling react without waiting for the hardware write.
 */
class AppSwitchService : AccessibilityService() {

    private lateinit var prefs: Prefs

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastAppliedPackage: String? = null
    private var lastAppliedMode: Mode? = null

    @Volatile
    private var imePackages: Set<String> = emptySet()
    private var imeObserver: ContentObserver? = null

    private var lastTextInteractionUptime: Long = 0L
    private var lastWindowStateChangeUptime: Long = 0L
    private var lastScrollKeyUptime: Long = 0L

    // Preferred: real mouse-wheel events through a root helper (native, smooth, no edge gestures).
    private val wheelInjector: WheelInjector by lazy { WheelInjector(this) }

    // Fallback when the wheel helper isn't available: continuous-drag scrolling (API 26+);
    // older versions use one swipe per key.
    private val scrollEngine: ScrollGestureEngine? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ScrollGestureEngine(this) else null
    }

    private val appLabelCache = HashMap<String, String?>()

    private var contentCheckScheduled = false
    private val contentCheckRunnable = Runnable {
        contentCheckScheduled = false
        handleContentChangedCheck()
    }

    // Fallback foreground detection: if a TYPE_WINDOW_STATE_CHANGED event is missed or
    // filtered out, the active window is checked after window-list changes.
    private val foregroundVerifyRunnable = Runnable { verifyForegroundFromActiveWindow() }

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

            val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < TEXT_RECENT_MS
            val stillEditing = recent || hasFocusedTextInput()

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

            mainHandler.postDelayed(this, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
        }
    }

    // ---------- HOLD RUNTIME STATE ----------

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
        registerImeObserver()

        // Open the root shell now so the first real switch doesn't pay for it.
        TrackpadController.warmUp()

        // Apply the right mode for whatever is already on screen (e.g. after the service
        // was restarted), instead of waiting for the next app switch.
        mainHandler.postDelayed(foregroundVerifyRunnable, 300L)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) scrollEngine?.cancel()
        wheelInjector.stop()
        stopTextOverrideMonitor()
        mainHandler.removeCallbacks(contentCheckRunnable)
        mainHandler.removeCallbacks(foregroundVerifyRunnable)
        cancelHoldPendingRunnables()
        unregisterImeObserver()
        keyInjectExec.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return

                if (isImeWindowEvent(pkg, event.className)) {
                    lastTextInteractionUptime = SystemClock.uptimeMillis()
                    val fg = AppState.currentForegroundPackage
                    if (isAutoKeyboardEnabledForPackage(fg)) {
                        enterTextInputOverrideIfNeeded()
                    }
                    return
                }

                lastWindowStateChangeUptime = SystemClock.uptimeMillis()
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

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (isActiveWindowChange(event)) scheduleForegroundVerify()
                scheduleContentCheck()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
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
        try {
            keyInjectExec.execute {
                execSu("input keyevent $keyCode")
            }
        } catch (_: Exception) {
            // Executor shut down (service being destroyed).
        }
    }

    // Kept as a separate one-shot su so slow `input` commands never queue in front of
    // trackpad mode writes on the shared root shell.
    private fun execSu(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            try {
                p.inputStream.bufferedReader().readText()
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
        lastScrollKeyUptime = SystemClock.uptimeMillis()
        val pkg = AppState.currentForegroundPackage
        val settings = prefs.getEffectiveScrollSettings(pkg)
        val steps = settings.sensitivity.steps
        val notches = settings.sensitivity.wheelNotches

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val scrollUp = !settings.invertVertical
                scrollVertical(scrollUp, steps, notches)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val scrollUp = settings.invertVertical
                scrollVertical(scrollUp, steps, notches)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (settings.horizontalEnabled) {
                    val scrollLeft = !settings.invertHorizontal
                    scrollHorizontal(scrollLeft, steps, notches)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (settings.horizontalEnabled) {
                    val scrollLeft = settings.invertHorizontal
                    scrollHorizontal(scrollLeft, steps, notches)
                }
            }
        }
    }

    // ---------- MODE APPLICATION ----------

    /**
     * Update the logical mode immediately and push it to hardware in the background.
     * Callbacks run on the main thread once the write finishes. If a newer request
     * supersedes this one before it runs, neither callback is invoked.
     */
    private fun applyMode(
        target: Mode,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        AppState.currentMode = target
        if (target == Mode.SCROLL_WHEEL) {
            // Get the wheel helper running before the first scroll tick.
            wheelInjector.ensureStarted()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scrollEngine?.cancel()
        }
        TrackpadController.requestMode(target) { ok ->
            if (ok) {
                NotificationHelper.clearRootError(this)
                onSuccess?.invoke()
            } else {
                showRootError()
                onFailure?.invoke()
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

    // Keep the IME list current when the user enables/disables a keyboard, instead of
    // only reading it once when the service connects.
    private fun registerImeObserver() {
        if (imeObserver != null) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                imePackages = loadImePackages()
            }
        }
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
                false,
                observer
            )
            imeObserver = observer
        } catch (_: Exception) {
        }
    }

    private fun unregisterImeObserver() {
        val observer = imeObserver ?: return
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {
        }
        imeObserver = null
    }

    /**
     * IME detection. Uses the enabled-IME list, the IME window class, and the
     * conventional ".inputmethod" package segment.
     *
     * Previously any package containing "ime" or "keyboard" was treated as a keyboard,
     * which caught ordinary apps (anything with "time", "prime", "anime", ...). Those
     * apps never got their per-app mode applied and could trigger the text-input override.
     */
    private fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (imePackages.contains(pkg)) return true
        return pkg.contains(".inputmethod", ignoreCase = true)
    }

    private fun isImeWindowEvent(pkg: String, className: CharSequence?): Boolean {
        if (isImePackage(pkg)) return true
        return className?.toString() == "android.inputmethodservice.SoftInputWindow"
    }

    private fun isAutoKeyboardEnabledForPackage(packageName: String?): Boolean {
        return prefs.isEffectiveAutoKeyboardForTextEnabled(packageName)
    }

    private fun handleTextInteraction(event: AccessibilityEvent) {
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return
        if (prefs.isExcluded(fg)) return

        lastTextInteractionUptime = SystemClock.uptimeMillis()

        // Already in the override: the monitor keeps it alive; no need to inspect the node.
        if (AppState.textInputOverrideActive) return

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

    // Throttle: at most one check per CONTENT_CHECK_DELAY_MS, however many events arrive.
    private fun scheduleContentCheck() {
        if (contentCheckScheduled) return
        // While scroll-wheel scrolling is underway the list fires content-changed events
        // nonstop; walking the view tree for each would compete with key handling on the
        // main thread and make scrolling stutter. Text-field detection resumes right after.
        if (AppState.currentMode == Mode.SCROLL_WHEEL &&
            SystemClock.uptimeMillis() - lastScrollKeyUptime < SCROLL_QUIET_MS
        ) return
        contentCheckScheduled = true
        mainHandler.postDelayed(contentCheckRunnable, CONTENT_CHECK_DELAY_MS)
    }

    private fun handleContentChangedCheck() {
        val fg = AppState.currentForegroundPackage

        if (!isAutoKeyboardEnabledForPackage(fg) || prefs.isExcluded(fg)) {
            if (AppState.textInputOverrideActive) {
                restoreModeAfterTextInput()
                stopTextOverrideMonitor()
            }
            return
        }

        // While the override is active, the monitor decides when to end it; skip the tree walk.
        if (AppState.textInputOverrideActive) return

        if (hasFocusedTextInput()) {
            enterTextInputOverrideIfNeeded()
        }
    }

    private fun handleViewFocusedForAutoKeyboard(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()

        if (pkg == "android" || pkg == "com.android.systemui") return

        if (pkg.isNotEmpty() && prefs.isExcluded(pkg)) {
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
        if (node.isEditable) return true

        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true)) return true
        if (cls.contains("TextInput", ignoreCase = true)) return true
        if (cls.contains("AutoCompleteTextView", ignoreCase = true)) return true

        try {
            if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
        } catch (_: Exception) {
        }

        return false
    }

    private fun enterTextInputOverrideIfNeeded() {
        if (AppState.textInputOverrideActive) return

        val fg = AppState.currentForegroundPackage
        if (prefs.isExcluded(fg)) return

        val prevMode = if (isAnyHoldActive()) {
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
        AppState.textInputOverrideActive = true
        startTextOverrideMonitor()

        // requestMode skips the write if the hardware is already in keyboard mode.
        applyMode(Mode.KEYBOARD, onFailure = {
            // Root failed: back out of the override so we don't think we're in Keyboard.
            if (AppState.textInputOverrideActive) {
                AppState.textInputOverrideActive = false
                AppState.modeBeforeTextInput = null
                AppState.currentMode = prevMode
                stopTextOverrideMonitor()
            }
        })
    }

    private fun restoreModeAfterTextInput() {
        val fg = AppState.currentForegroundPackage
        val targetMode = AppState.modeBeforeTextInput ?: getEffectiveModeForPackage(fg)

        if (isAnyHoldActive()) {
            clearAllHoldState()
        }

        AppState.modeBeforeTextInput = null
        AppState.textInputOverrideActive = false

        applyMode(targetMode)
    }

    private fun handleEndOfTextInputCheck() {
        if (!AppState.textInputOverrideActive) return
        val fg = AppState.currentForegroundPackage
        if (!isAutoKeyboardEnabledForPackage(fg)) return

        val recent = (SystemClock.uptimeMillis() - lastTextInteractionUptime) < TEXT_RECENT_MS
        if (!recent && !hasFocusedTextInput()) {
            restoreModeAfterTextInput()
            stopTextOverrideMonitor()
        }
    }

    private fun startTextOverrideMonitor() {
        textOverrideMisses = 0
        mainHandler.removeCallbacks(textOverrideCheckRunnable)
        mainHandler.postDelayed(textOverrideCheckRunnable, TEXT_OVERRIDE_CHECK_INTERVAL_MS)
    }

    private fun stopTextOverrideMonitor() {
        mainHandler.removeCallbacks(textOverrideCheckRunnable)
        textOverrideMisses = 0
    }

    private fun hasFocusedTextInput(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return false

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

        // Fallback walk, bounded so a huge view tree can't stall the main thread.
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            visited++

            val focusedNow = node.isFocused || node.isAccessibilityFocused
            if (focusedNow && isTextInputNode(node)) {
                node.recycle()
                recycleQueue(queue)
                return true
            }

            if (visited < MAX_TREE_NODES) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
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
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return

        val now = SystemClock.uptimeMillis()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (holdMode == HoldMode.DISABLED) return
                if (state.active && state.keyDown == event.keyCode) return
                // Text-field gating only applies to *starting* a hold. It used to run on key-up
                // too, so if focus moved into a text field mid-hold the release was ignored and
                // the hold got stuck.
                if (!allowInText && hasFocusedTextInput()) return

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
        mainHandler.postDelayed(r, SINGLE_HOLD_ACTIVATE_DELAY_MS)
    }

    private fun cancelPendingSingleHold(state: HoldRuntimeState) {
        state.pendingSingleHoldRunnable?.let { mainHandler.removeCallbacks(it) }
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

        val showToast = prefs.isToastHoldKeyEnabled()
        applyMode(target, onSuccess = {
            if (showToast && state.active && state.targetMode == target) {
                ToastHelper.show(this, "$toastPrefix: ${modeLabel(target)}")
            }
        }, onFailure = {
            // Root failed: undo this hold if it's still the one we started.
            if (!state.active || state.targetMode != target) return@applyMode

            state.active = false
            state.keyDown = null
            state.targetMode = null

            if (!(hold1.active || hold2.active)) {
                AppState.modeBeforeHoldKey = null
                AppState.modeBeforeHold1 = null
                AppState.modeBeforeHold2 = null
                lastActivatedHoldSlot = 0
            }

            AppState.currentMode = baseMode
            syncHoldStateToAppState()
        })
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

            if (AppState.hold1KeyCodeDown == releasedKey) AppState.hold1KeyCodeDown = null
            if (AppState.hold2KeyCodeDown == releasedKey) AppState.hold2KeyCodeDown = null
        }

        syncHoldStateToAppState()

        applyMode(restoreTarget)
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

    private fun scrollVertical(up: Boolean, steps: Int, notches: Float) {
        // "up" moves the content like a finger dragged up, i.e. reveals what's below:
        // that's a wheel scroll down (negative VSCROLL).
        if (wheelScroll(v = if (up) -notches else notches, h = 0f)) return
        scrollEngine?.let {
            it.scrollVertical(up, steps)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val ok = performVerticalGesture(up, steps)
            if (!ok) scrollVerticalNodeBased(up, steps)
        } else {
            scrollVerticalNodeBased(up, steps)
        }
    }

    private fun scrollHorizontal(left: Boolean, steps: Int, notches: Float) {
        // "left" reveals content to the right: positive HSCROLL.
        if (wheelScroll(v = 0f, h = if (left) notches else -notches)) return
        scrollEngine?.let {
            it.scrollHorizontal(left, steps)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val ok = performHorizontalGesture(left, steps)
            if (!ok) scrollHorizontalNodeBased(left, steps)
        } else {
            scrollHorizontalNodeBased(left, steps)
        }
    }

    // ---- Wheel scrolling: acceleration + target point ----

    private var lastWheelTickUptime = 0L
    private var lastWheelDirection = 0  // sign of (v + h)
    private var wheelAccel = 1f
    private var wheelTargetX = 0f
    private var wheelTargetY = 0f

    private fun wheelScroll(v: Float, h: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = now - lastWheelTickUptime
        val dir = if (v + h > 0) 1 else -1
        val newBurst = dt > WHEEL_BURST_GAP_MS || dir != lastWheelDirection

        // Acceleration: ticks that follow each other quickly in the same direction scroll
        // progressively further; any pause or direction change resets to 1x.
        wheelAccel = when {
            !prefs.isScrollAccelerationEnabled() -> 1f
            !newBurst && dt < WHEEL_ACCEL_WINDOW_MS -> (wheelAccel * WHEEL_ACCEL_GROWTH).coerceAtMost(WHEEL_ACCEL_MAX)
            else -> 1f
        }

        // Aim at the scrollable view in the focused window, picked once per burst.
        if (newBurst || (wheelTargetX == 0f && wheelTargetY == 0f)) {
            updateWheelTarget()
        }

        lastWheelTickUptime = now
        lastWheelDirection = dir

        return wheelInjector.scroll(
            wheelTargetX, wheelTargetY,
            v * wheelAccel, h * wheelAccel,
            prefs.getScrollSmoothness().ease
        )
    }

    /**
     * Wheel events go to whatever window is under the given point, so pick a point inside
     * the focused window: the centre of its first scrollable view if there is one, else the
     * centre of the window, else the centre of the screen.
     */
    private fun updateWheelTarget() {
        val dm = resources.displayMetrics
        wheelTargetX = dm.widthPixels / 2f
        wheelTargetY = dm.heightPixels / 2f

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        val rect = android.graphics.Rect()
        try {
            root.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                wheelTargetX = rect.exactCenterX()
                wheelTargetY = rect.exactCenterY()
            }
        } catch (_: Exception) {
        }

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            visited++
            if (node.isScrollable && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    wheelTargetX = rect.exactCenterX()
                    wheelTargetY = rect.exactCenterY()
                }
                node.recycle()
                recycleQueue(queue)
                return
            }
            if (visited < MAX_TREE_NODES) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            node.recycle()
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

    private fun isIgnoredForegroundPackage(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (pkg == "android" || pkg == "com.android.systemui") return true
        if (isImePackage(pkg)) return true
        return prefs.isExcluded(pkg)
    }

    private fun handleForegroundAppChanged(packageName: String) {
        if (isIgnoredForegroundPackage(packageName)) return

        val prevFg = AppState.currentForegroundPackage
        AppState.currentForegroundPackage = packageName
        val appChanged = prevFg != null && prevFg != packageName

        val overridePkg = AppState.manualOverrideForPackage
        if (overridePkg != null && overridePkg != packageName) {
            AppState.manualOverrideForPackage = null
        }

        if (AppState.textInputOverrideActive && appChanged) {
            AppState.textInputOverrideActive = false
            AppState.modeBeforeTextInput = null
            stopTextOverrideMonitor()
        }

        if ((hold1.active || hold2.active) && appChanged) {
            clearAllHoldState()
        }

        if (AppState.manualOverrideForPackage == packageName) return
        if (AppState.textInputOverrideActive || hold1.active || hold2.active) return

        val (effectiveMode, isDefault) = when (val appMode = prefs.getAppMode(packageName)) {
            Mode.MOUSE, Mode.KEYBOARD, Mode.SCROLL_WHEEL -> appMode to false
            Mode.FOLLOW_SYSTEM, null -> prefs.getSystemDefaultMode() to true
        }

        // Repeat events from the same app (new activity, dialog, etc.) with nothing to change.
        if (lastAppliedPackage == packageName && lastAppliedMode == effectiveMode &&
            AppState.currentMode == effectiveMode
        ) return

        val currentMode = AppState.currentMode
        lastAppliedPackage = packageName
        lastAppliedMode = effectiveMode

        val onFailure: () -> Unit = {
            // Forget what we "applied" so the next event for this app retries.
            if (lastAppliedPackage == packageName) {
                lastAppliedPackage = null
                lastAppliedMode = null
            }
        }

        if (isDefault && currentMode == effectiveMode) {
            // Logical mode already matches: no toast, but still make sure the hardware agrees
            // (it may have been changed outside the app). This is a cheap read in the background.
            applyMode(effectiveMode, onFailure = onFailure)
            return
        }

        prefs.setLastKnownMode(effectiveMode)

        val message: String? = when {
            isDefault && prefs.isToastDefaultModeEnabled() ->
                "Switching to default mode! (${modeLabel(effectiveMode)})"
            !isDefault && prefs.isToastPerAppEnabled() -> {
                val appLabel = getAppLabel(packageName)
                if (appLabel != null) "Switching to ${modeLabel(effectiveMode)} Mode for $appLabel!"
                else "Switching to ${modeLabel(effectiveMode)} Mode!"
            }
            else -> null
        }

        // The toast is shown once the hardware write succeeds (a few ms with the persistent
        // root shell), so it never claims a switch that didn't happen. If the user has already
        // moved on to another app, the superseded request's toast is dropped.
        applyMode(effectiveMode, onSuccess = {
            if (message != null && AppState.currentForegroundPackage == packageName) {
                ToastHelper.show(this, message)
            }
        }, onFailure = onFailure)
    }

    private fun isActiveWindowChange(event: AccessibilityEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val changes = event.windowChanges
        return (changes and (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED)) != 0
    }

    private fun scheduleForegroundVerify() {
        mainHandler.removeCallbacks(foregroundVerifyRunnable)
        mainHandler.postDelayed(foregroundVerifyRunnable, FOREGROUND_VERIFY_DELAY_MS)
    }

    /**
     * Fallback for missed/filtered TYPE_WINDOW_STATE_CHANGED events: look at the active
     * window and switch if it belongs to a different app than the one we think is in front.
     */
    private fun verifyForegroundFromActiveWindow() {
        // A real window-state event just arrived; it is more reliable than the window list,
        // which can briefly lag behind during transitions.
        val sinceStateChange = SystemClock.uptimeMillis() - lastWindowStateChangeUptime
        if (sinceStateChange < FOREGROUND_VERIFY_DELAY_MS) {
            mainHandler.postDelayed(foregroundVerifyRunnable, FOREGROUND_VERIFY_DELAY_MS - sinceStateChange)
            return
        }

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        val pkg = try {
            root.packageName?.toString()
        } catch (_: Exception) {
            null
        } finally {
            root.recycle()
        }

        if (pkg.isNullOrEmpty() || pkg == AppState.currentForegroundPackage) return
        handleForegroundAppChanged(pkg)
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
        if (appLabelCache.containsKey(packageName)) return appLabelCache[packageName]
        val label = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
        if (appLabelCache.size >= APP_LABEL_CACHE_MAX) appLabelCache.clear()
        appLabelCache[packageName] = label
        return label
    }

    private fun showRootError() {
        NotificationHelper.showRootError(
            this,
            "Root access failed. Please grant root to Q25 Trackpad Customizer."
        )
    }

    companion object {
        private const val TEXT_OVERRIDE_CHECK_INTERVAL_MS = 350L
        private const val TEXT_RECENT_MS = 1200L
        private const val CONTENT_CHECK_DELAY_MS = 120L
        private const val SCROLL_QUIET_MS = 300L
        private const val WHEEL_BURST_GAP_MS = 300L
        private const val WHEEL_ACCEL_WINDOW_MS = 120L
        private const val WHEEL_ACCEL_GROWTH = 1.3f
        private const val WHEEL_ACCEL_MAX = 3.0f
        private const val FOREGROUND_VERIFY_DELAY_MS = 250L
        private const val MAX_TREE_NODES = 300
        private const val APP_LABEL_CACHE_MAX = 64
        private const val DOUBLE_PRESS_TIMEOUT_MS = 320L
        private const val SINGLE_HOLD_ACTIVATE_DELAY_MS = 120L
    }
}
