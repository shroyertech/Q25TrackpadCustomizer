package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Experimental edge auto-scroll.
 *
 * Mouse mode: the service observes (does not consume) mouse events (Android 15+). When the
 * cursor is at the edge of the app's window and the trackpad keeps pushing toward that
 * edge, the page under the cursor is scrolled in that direction, proportional to the push.
 *
 * Keyboard mode: after a D-pad press, if focus didn't move (stuck at the edge of the page)
 * or it landed on an item at the edge of the window, the page is scrolled a bit further in
 * that direction so more of it comes into view. The key itself is never consumed.
 *
 * Scrolling uses real wheel events through the root helper (lands on the view under the
 * given point). Without root, Mouse mode falls back to [fallbackScroll] (touch drags);
 * Keyboard mode needs root, since touches would end keyboard navigation in the app.
 */
class EdgeAutoScroller(
    private val service: AccessibilityService,
    private val prefs: Prefs,
    private val wheel: WheelInjector,
    /** Fallback scroll when the wheel helper isn't available. dx/dy = -1/0/1 (content direction to reveal). */
    private val fallbackScroll: (dx: Int, dy: Int) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    private var settings: EdgeScrollSettings? = null
    private var settingsPackage: String? = null

    private val windowRect = Rect()
    private var windowRectUptime = 0L

    private val pxPerNotchV: Float
    private val pxPerNotchH: Float

    private var observingMouse = false

    // Mouse-mode rate limit for the no-root fallback (touch drags are coarse).
    private var lastFallbackUptime = 0L

    init {
        val dm = service.resources.displayMetrics
        var v = 64f * dm.density
        var h = 64f * dm.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val vc = ViewConfiguration.get(service)
                v = vc.scaledVerticalScrollFactor
                h = vc.scaledHorizontalScrollFactor
            } catch (_: Exception) {
            }
        }
        pxPerNotchV = v.coerceAtLeast(1f)
        pxPerNotchH = h.coerceAtLeast(1f)
    }

    // ---------- settings / lifecycle ----------

    /** Re-read settings (call on app switch and when edge prefs change). */
    fun refresh() {
        settings = null
        windowRectUptime = 0L
        updateMouseObservation()
        val s = current()
        if (s != null && s.enabled && s.anyDirection) wheel.ensureStarted()
    }

    fun onWindowsChanged() {
        windowRectUptime = 0L
    }

    private fun current(): EdgeScrollSettings? {
        val pkg = AppState.currentForegroundPackage
        if (settings == null || pkg != settingsPackage) {
            settings = prefs.getEffectiveEdgeScrollSettings(pkg)
            settingsPackage = pkg
        }
        return settings
    }

    /**
     * Mouse events reach the service only while observation is on. It is turned on only
     * when edge scrolling in Mouse mode could apply, so mouse input is untouched otherwise.
     * Needs Android 15 (observe without consuming); on older versions Mouse mode edge
     * scrolling is unavailable rather than risk swallowing the cursor.
     */
    fun updateMouseObservation() {
        val g = prefs.getGlobalEdgeScrollSettings()
        val want = Build.VERSION.SDK_INT >= 35 && g.inMouseMode && prefs.isEdgeScrollUsedAnywhere()
        if (want == observingMouse) return
        val info = service.serviceInfo ?: return
        try {
            if (want) {
                info.motionEventSources = InputDevice.SOURCE_MOUSE
                AccessibilityServiceInfo::class.java
                    .getMethod("setObservedMotionEventSources", Int::class.javaPrimitiveType)
                    .invoke(info, InputDevice.SOURCE_MOUSE)
            } else {
                try {
                    AccessibilityServiceInfo::class.java
                        .getMethod("setObservedMotionEventSources", Int::class.javaPrimitiveType)
                        .invoke(info, 0)
                } catch (_: Exception) {
                }
                info.motionEventSources = 0
            }
            service.serviceInfo = info
            observingMouse = want
        } catch (_: Throwable) {
            // Not supported on this build: leave mouse events alone.
            if (want) {
                try {
                    info.motionEventSources = 0
                    service.serviceInfo = info
                } catch (_: Throwable) {
                }
            }
            observingMouse = false
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (observingMouse) {
            try {
                val info = service.serviceInfo
                if (info != null) {
                    info.motionEventSources = 0
                    service.serviceInfo = info
                }
            } catch (_: Throwable) {
            }
            observingMouse = false
        }
    }

    // ---------- Mouse mode ----------

    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    fun onMouseEvent(event: MotionEvent) {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_MOVE) return
        if (AppState.currentMode != Mode.MOUSE) return
        val s = current() ?: return
        if (!s.enabled || !s.inMouseMode || !s.anyDirection) return

        val x = event.x
        val y = event.y
        var rx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        var ry = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (rx == 0f && ry == 0f && !lastMouseX.isNaN()) {
            // No relative axes on this device: fall back to position change.
            rx = x - lastMouseX
            ry = y - lastMouseY
        }
        lastMouseX = x
        lastMouseY = y

        val r = windowBounds()
        val band = edgeBand(r)

        var pushX = 0f
        var pushY = 0f
        if (ry > 0f && y >= r.bottom - band && s.down) pushY = ry
        else if (ry < 0f && y <= r.top + band && s.up) pushY = ry
        if (rx > 0f && x >= r.right - band && s.right) pushX = rx
        else if (rx < 0f && x <= r.left + band && s.left) pushX = rx
        if (pushX == 0f && pushY == 0f) return

        val gain = mouseGain(s.speed)
        // Push down/right reveals content below/right: negative VSCROLL, positive HSCROLL.
        val v = -pushY * gain / pxPerNotchV
        val h = pushX * gain / pxPerNotchH

        // Aim inside the window (the cursor may be over a bar at the very edge).
        val px = x.coerceIn(r.left + band * 2, r.right - band * 2)
        val py = y.coerceIn(r.top + band * 2, r.bottom - band * 2)

        if (wheel.scroll(px, py, v, h, MOUSE_EASE, 0)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastFallbackUptime < FALLBACK_INTERVAL_MS) return
        lastFallbackUptime = now
        fallbackScroll(sign(pushX), sign(pushY))
    }

    // ---------- Keyboard mode ----------

    private var keyDx = 0
    private var keyDy = 0
    private var keyUptime = 0L
    private var focusBefore: Rect? = null
    private var focusEventSinceKey = false
    private var scrolledSinceKey = false

    private val keyCheck = Runnable { checkAfterKey() }

    /** Called for every key event; never consumes it. */
    fun onKeyEvent(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val (dx, dy) = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            else -> return
        }
        if (AppState.currentMode != Mode.KEYBOARD) return
        if (AppState.textInputOverrideActive) return
        if (event.isShiftPressed || event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) return
        val s = current() ?: return
        if (!s.enabled || !s.inKeyboardMode || !s.allows(dx, dy)) return

        val focused = findFocus()
        if (focused != null) {
            val editable = try {
                focused.isEditable
            } catch (_: Exception) {
                false
            }
            if (editable) {
                recycle(focused)
                return
            }
        }
        focusBefore = focused?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        recycle(focused)

        keyDx = dx
        keyDy = dy
        keyUptime = SystemClock.uptimeMillis()
        focusEventSinceKey = false
        scrolledSinceKey = false
        handler.removeCallbacks(keyCheck)
        handler.postDelayed(keyCheck, KEY_CHECK_DELAY_MS)
    }

    fun onFocusEvent() {
        if (SystemClock.uptimeMillis() - keyUptime < KEY_CHECK_DELAY_MS * 3) focusEventSinceKey = true
    }

    /** The app scrolled something itself (e.g. a web page handling the arrow key). */
    fun onViewScrolled() {
        if (SystemClock.uptimeMillis() - keyUptime < KEY_CHECK_DELAY_MS) scrolledSinceKey = true
    }

    private fun checkAfterKey() {
        if (AppState.currentMode != Mode.KEYBOARD) return
        val s = current() ?: return
        if (!s.enabled || !s.inKeyboardMode || !s.allows(keyDx, keyDy)) return

        val r = windowBounds()
        val focused = findFocus()
        val now = Rect()
        focused?.getBoundsInScreen(now)
        recycle(focused)

        val before = focusBefore
        // Focus didn't move and the app didn't scroll on its own: we're at the edge.
        val stuck = !scrolledSinceKey &&
                (focused == null || (!focusEventSinceKey && before != null && before == now))
        val atEdge = focused != null && isNearEdge(now, r, keyDx, keyDy)

        if (!stuck && !atEdge) return

        val notches = s.speed.wheelNotches
        val v = -keyDy * notches
        val h = keyDx * notches
        val band = edgeBand(r)
        val px = if (focused != null && !now.isEmpty) now.exactCenterX() else r.exactCenterX()
        val py = if (focused != null && !now.isEmpty) now.exactCenterY() else r.exactCenterY()
        val tx = px.coerceIn(r.left + band * 2, r.right - band * 2)
        val ty = py.coerceIn(r.top + band * 2, r.bottom - band * 2)

        // No touch-drag fallback here: injected touches would knock the app out of
        // keyboard-navigation mode. The helper is started on demand; the next press works.
        wheel.scroll(tx, ty, v, h, KEY_EASE, 0)
    }

    private fun isNearEdge(node: Rect, win: Rect, dx: Int, dy: Int): Boolean {
        if (node.isEmpty) return false
        val zoneV = win.height() * KEY_EDGE_ZONE
        val zoneH = win.width() * KEY_EDGE_ZONE
        return when {
            dy > 0 -> node.bottom >= win.bottom - zoneV
            dy < 0 -> node.top <= win.top + zoneV
            dx > 0 -> node.right >= win.right - zoneH
            dx < 0 -> node.left <= win.left + zoneH
            else -> false
        }
    }

    private fun findFocus(): AccessibilityNodeInfo? = try {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (_: Exception) {
        null
    }

    // ---------- shared helpers ----------

    /** Bounds of the active app window (cached briefly); the whole screen if unknown. */
    private fun windowBounds(): Rect {
        val now = SystemClock.uptimeMillis()
        if (windowRectUptime != 0L && now - windowRectUptime < WINDOW_CACHE_MS) return windowRect
        windowRectUptime = now

        val dm = service.resources.displayMetrics
        windowRect.set(0, 0, dm.widthPixels, dm.heightPixels)
        try {
            val windows = service.windows
            var picked: AccessibilityWindowInfo? = null
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                    picked = w
                    break
                }
            }
            if (picked == null) {
                picked = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            }
            if (picked != null) {
                val tmp = Rect()
                picked.getBoundsInScreen(tmp)
                if (!tmp.isEmpty) windowRect.set(tmp)
            }
        } catch (_: Exception) {
        }
        return windowRect
    }

    private fun edgeBand(r: Rect): Float {
        val dm = service.resources.displayMetrics
        return maxOf(EDGE_BAND_DP * dm.density, minOf(r.width(), r.height()) * 0.02f)
    }

    private fun mouseGain(speed: ScrollSensitivity): Float = when (speed) {
        ScrollSensitivity.SLOW -> 1.2f
        ScrollSensitivity.MEDIUM -> 2.2f
        ScrollSensitivity.FAST -> 4.0f
    }

    private fun sign(f: Float): Int = if (f > 0f) 1 else if (f < 0f) -1 else 0

    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo?) {
        try {
            n?.recycle()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val EDGE_BAND_DP = 6f
        private const val MOUSE_EASE = 0.5f
        private const val KEY_EASE = 0.3f
        private const val KEY_CHECK_DELAY_MS = 110L
        private const val KEY_EDGE_ZONE = 0.12f
        private const val WINDOW_CACHE_MS = 500L
        private const val FALLBACK_INTERVAL_MS = 140L
    }
}
