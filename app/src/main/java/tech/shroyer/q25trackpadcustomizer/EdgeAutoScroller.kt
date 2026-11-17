package tech.shroyer.q25trackpadcustomizer

import android.accessibilityservice.AccessibilityService
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
 * Mouse mode: invisible strips along the enabled screen edges catch the cursor's hover
 * events. While the cursor is on a strip and the trackpad keeps pushing toward that edge,
 * the page is scrolled in that direction, proportional to the push.
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
        handler.removeCallbacks(stripCheck)
        if (prefs.isEdgeScrollUsedAnywhere()) {
            handler.post(stripCheck)
        } else {
            removeStrips()
            setStatus("Edge scroll is off")
        }
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

    // ---------- Mouse mode: edge strips ----------
    //
    // Nothing on some builds reports the cursor position to an accessibility service
    // (e.g. LineageOS 24 has no "observe motion events" API), so Mouse mode uses thin,
    // invisible accessibility-overlay strips along the enabled screen edges. Hover events
    // only reach the window under the cursor, so when the cursor touches a strip we know
    // it is at that edge, and the hover events carry how far the pad keeps pushing.
    // A mouse click that lands on a strip is passed through to the app underneath.

    private val strips = HashMap<String, android.view.View>()
    private var stripsSuppressedUntil = 0L

    private val stripCheck = object : Runnable {
        override fun run() {
            updateStrips()
            if (prefs.isEdgeScrollUsedAnywhere()) handler.postDelayed(this, STRIP_CHECK_MS)
        }
    }

    /** Show/hide edge strips to match mode + settings. Cheap; safe to call often. */
    fun updateStrips() {
        val s = current()
        val want = AppState.currentMode == Mode.MOUSE && s != null && s.enabled && s.inMouseMode
        val wanted = HashSet<String>()
        if (want && s != null) {
            if (s.up) wanted.add("top")
            if (s.down) wanted.add("bottom")
            if (s.left) wanted.add("left")
            if (s.right) wanted.add("right")
        }
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        for (edge in strips.keys.toList()) {
            if (edge !in wanted) {
                try {
                    wm.removeView(strips.remove(edge))
                } catch (_: Exception) {
                }
            }
        }
        for (edge in wanted) {
            if (edge in strips) continue
            val v = makeStrip(edge)
            try {
                wm.addView(v, stripParams(edge))
                strips[edge] = v
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "edge strip $edge failed: $t")
                setStatus("Could not add edge strips: ${t.javaClass.simpleName}")
            }
        }
        if (want) {
            if (strips.isNotEmpty() && mouseEventsSeen == 0L) setStatus("Ready: push the cursor against an edge")
        } else {
            setStatus(
                when {
                    !prefs.isEdgeScrollUsedAnywhere() -> "Edge scroll is off"
                    AppState.currentMode != Mode.MOUSE -> "Mouse mode edge strips appear in Mouse mode"
                    else -> "Edge scroll is off for this app or Mouse mode"
                }
            )
        }
    }

    private fun stripParams(edge: String): android.view.WindowManager.LayoutParams {
        val t = STRIP_PX
        val match = android.view.WindowManager.LayoutParams.MATCH_PARENT
        val (w, h, gravity) = when (edge) {
            "top" -> Triple(match, t, android.view.Gravity.TOP)
            "bottom" -> Triple(match, t, android.view.Gravity.BOTTOM)
            "left" -> Triple(t, match, android.view.Gravity.LEFT)
            else -> Triple(t, match, android.view.Gravity.RIGHT)
        }
        @Suppress("DEPRECATION")
        val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return android.view.WindowManager.LayoutParams(
            w, h,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            title = "Q25EdgeStrip-$edge"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
    }

    private fun makeStrip(edge: String): android.view.View {
        val v = android.view.View(service)
        v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        v.setOnHoverListener { _, ev ->
            val rawX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ev.rawX else ev.x
            val rawY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ev.rawY else ev.y
            onMouseMove(ev.actionMasked, rawX, rawY,
                ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X), ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y), edge)
            true
        }
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
                passClickThrough(if (ev.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) 0 else 1)
            }
            true
        }
        return v
    }

    /** Hide the strips' touchability briefly and replay the click onto the app underneath. */
    private fun passClickThrough(count: Int) {
        val now = SystemClock.uptimeMillis()
        if (now < stripsSuppressedUntil) return
        stripsSuppressedUntil = now + 450L
        setStripsTouchable(false)
        handler.postDelayed({ wheel.click(count) }, 60L)
        handler.postDelayed({ setStripsTouchable(true) }, 450L)
    }

    private fun setStripsTouchable(touchable: Boolean) {
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        for ((edge, v) in strips) {
            try {
                val lp = stripParams(edge)
                if (!touchable) lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeStrips() {
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        for (v in strips.values) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        strips.clear()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        removeStrips()
    }

    // ---------- Mouse mode ----------

    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    private var mouseEventsSeen = 0L
    private var lastSeenLogUptime = 0L

    /** Service-level mouse events (only on builds where observing them works). */
    fun onMouseEvent(event: MotionEvent) {
        onMouseMove(
            event.actionMasked, event.x, event.y,
            event.getAxisValue(MotionEvent.AXIS_RELATIVE_X), event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y), null
        )
    }

    private fun onMouseMove(action: Int, x: Float, y: Float, relX: Float, relY: Float, stripEdge: String?) {
        mouseEventsSeen++
        val nowSeen = SystemClock.uptimeMillis()
        if (mouseEventsSeen == 1L || nowSeen - lastSeenLogUptime > 3000L) {
            lastSeenLogUptime = nowSeen
            android.util.Log.i(TAG, "edge events seen=$mouseEventsSeen action=$action strip=$stripEdge mode=${AppState.currentMode}")
            setStatus("Working: cursor reached the ${stripEdge ?: "edge"} ($mouseEventsSeen events)")
        }
        if (action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_HOVER_ENTER &&
            action != MotionEvent.ACTION_MOVE) return
        if (AppState.currentMode != Mode.MOUSE) return
        val s = current() ?: return
        if (!s.enabled || !s.inMouseMode || !s.anyDirection) return

        var rx = relX
        var ry = relY
        val hadRelative = rx != 0f || ry != 0f
        if (!hadRelative && !lastMouseX.isNaN() && action != MotionEvent.ACTION_HOVER_ENTER) {
            // No relative axes on this device: fall back to position change.
            rx = x - lastMouseX
            ry = y - lastMouseY
        }
        lastMouseX = x
        lastMouseY = y

        // Edges are the physical screen edges (what the cursor stops against), plus the
        // app window's edges when the window is smaller (split screen, freeform).
        val screen = screenBounds()
        val win = windowBounds()
        val band = edgeBand(screen)

        val atBottom = stripEdge == "bottom" || y >= screen.bottom - band ||
                (win.bottom < screen.bottom && y >= win.bottom - band)
        val atTop = stripEdge == "top" || y <= screen.top + band || (win.top > screen.top && y <= win.top + band)
        val atRight = stripEdge == "right" || x >= screen.right - band ||
                (win.right < screen.right && x >= win.right - band)
        val atLeft = stripEdge == "left" || x <= screen.left + band || (win.left > screen.left && x <= win.left + band)

        if (rx == 0f && ry == 0f) {
            // The cursor is pinned against the edge and the device reports no relative
            // motion: an event still means the finger moved, so push toward the pinned edge.
            if (stripEdge == "bottom" || y >= screen.bottom - PINNED_PX) ry = DEFAULT_PUSH
            else if (stripEdge == "top" || y <= screen.top + PINNED_PX) ry = -DEFAULT_PUSH
            if (stripEdge == "right" || x >= screen.right - PINNED_PX) rx = DEFAULT_PUSH
            else if (stripEdge == "left" || x <= screen.left + PINNED_PX) rx = -DEFAULT_PUSH
        }

        var pushX = 0f
        var pushY = 0f
        if (ry > 0f && atBottom && s.down) pushY = ry
        else if (ry < 0f && atTop && s.up) pushY = ry
        if (rx > 0f && atRight && s.right) pushX = rx
        else if (rx < 0f && atLeft && s.left) pushX = rx

        logThrottled(x, y, rx, ry, hadRelative, screen, win, pushX, pushY)
        if (pushX == 0f && pushY == 0f) return

        val gain = mouseGain(speedFor(s))
        // Push down/right reveals content below/right: negative VSCROLL, positive HSCROLL.
        val v = -pushY * gain / pxPerNotchV
        val h = pushX * gain / pxPerNotchH

        // Wheel events go to whatever is under the point. At the edge that's usually a
        // toolbar (e.g. Chrome's bottom address bar) or a system bar, not the page, so aim
        // at the middle of the window along the scrolling axis and follow the cursor on
        // the other axis.
        val inset = band * 2
        val px = if (pushX != 0f) win.exactCenterX()
        else x.coerceIn(win.left + inset, win.right - inset)
        val py = if (pushY != 0f) win.exactCenterY()
        else y.coerceIn(win.top + inset, win.bottom - inset)

        if (wheel.scroll(px, py, v, h, MOUSE_EASE, inertia())) return

        val now = SystemClock.uptimeMillis()
        if (now - lastFallbackUptime < FALLBACK_INTERVAL_MS) return
        lastFallbackUptime = now
        fallbackScroll(sign(pushX), sign(pushY))
    }

    private var lastLogUptime = 0L

    private fun setStatus(text: String) {
        AppState.edgeScrollStatus = text
    }

    private fun logThrottled(
        x: Float, y: Float, rx: Float, ry: Float, rel: Boolean,
        screen: Rect, win: Rect, pushX: Float, pushY: Float
    ) {
        val now = SystemClock.uptimeMillis()
        if (pushX == 0f && pushY == 0f && now - lastLogUptime < 1000L) return
        if ((pushX != 0f || pushY != 0f) && now - lastLogUptime < 150L) return
        lastLogUptime = now
        android.util.Log.i(
            TAG,
            "mouse x=$x y=$y rel=($rx,$ry) hasRel=$rel screen=$screen win=$win push=($pushX,$pushY) wheel=${wheel.isReady}"
        )
    }

    private val screenRect = Rect()

    /** Full physical screen in the current rotation. */
    private fun screenBounds(): Rect {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = service.getSystemService(android.view.WindowManager::class.java)
                screenRect.set(wm.maximumWindowMetrics.bounds)
                if (!screenRect.isEmpty) return screenRect
            }
        } catch (_: Exception) {
        }
        try {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(dm)
            screenRect.set(0, 0, dm.widthPixels, dm.heightPixels)
            if (!screenRect.isEmpty) return screenRect
        } catch (_: Exception) {
        }
        val dm = service.resources.displayMetrics
        screenRect.set(0, 0, dm.widthPixels, dm.heightPixels)
        return screenRect
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

        val notches = speedFor(s).wheelNotches
        val v = -keyDy * notches
        val h = keyDx * notches
        val band = edgeBand(r)
        val px = if (focused != null && !now.isEmpty) now.exactCenterX() else r.exactCenterX()
        val py = if (focused != null && !now.isEmpty) now.exactCenterY() else r.exactCenterY()
        val tx = px.coerceIn(r.left + band * 2, r.right - band * 2)
        val ty = py.coerceIn(r.top + band * 2, r.bottom - band * 2)

        // No touch-drag fallback here: injected touches would knock the app out of
        // keyboard-navigation mode. The helper is started on demand; the next press works.
        wheel.scroll(tx, ty, v, h, KEY_EASE, inertia())
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

    /** Edge speed: its own override, else the app's scroll wheel sensitivity. */
    private fun speedFor(s: EdgeScrollSettings): ScrollSensitivity =
        s.speedOverride ?: prefs.getEffectiveScrollSettings(AppState.currentForegroundPackage).sensitivity

    private fun inertia(): Int = prefs.getScrollInertia().level

    // Scroll pixels per pixel of push past the edge.
    private fun mouseGain(speed: ScrollSensitivity): Float = when (speed) {
        ScrollSensitivity.SLOW -> 4f
        ScrollSensitivity.MEDIUM -> 7f
        ScrollSensitivity.FAST -> 11f
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
        private const val TAG = "Q25EdgeScroll"
        private const val EDGE_BAND_DP = 6f
        private const val PINNED_PX = 2f
        private const val STRIP_PX = 3
        private const val STRIP_CHECK_MS = 700L
        private const val DEFAULT_PUSH = 3f
        private const val MOUSE_EASE = 0.5f
        private const val KEY_EASE = 0.3f
        private const val KEY_CHECK_DELAY_MS = 110L
        private const val KEY_EDGE_ZONE = 0.12f
        private const val WINDOW_CACHE_MS = 500L
        private const val FALLBACK_INTERVAL_MS = 140L
    }
}
