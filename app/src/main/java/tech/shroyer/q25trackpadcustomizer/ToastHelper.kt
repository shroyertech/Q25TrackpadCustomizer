package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * Central toast display.
 *
 * While the accessibility service is running, messages are drawn as a small toast-style
 * accessibility overlay. Android rate-limits real toasts from apps that aren't in the
 * foreground (a few per window, then they are silently dropped), and mode-switch messages
 * come from the background in quick bursts, so system toasts went missing. The overlay
 * has no quota. Without the service, a normal Toast is shown (ToastActivity as fallback).
 *
 * A new message replaces the previous one instead of queueing behind it.
 */
object ToastHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Toast? = null

    private var overlayView: TextView? = null
    private val hideOverlay = Runnable { dismissOverlay() }

    fun show(context: Context, text: String) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMain(appContext, text)
        } else {
            mainHandler.post { showOnMain(appContext, text) }
        }
    }

    private fun showOnMain(appContext: Context, text: String) {
        if (showOverlay(text)) return
        try {
            current?.cancel()
            val toast = Toast.makeText(appContext, text, Toast.LENGTH_SHORT)
            current = toast
            toast.show()
        } catch (_: Exception) {
            current = null
            showViaActivity(appContext, text)
        }
    }

    private fun showOverlay(text: String): Boolean {
        val service = AppSwitchService.instance ?: return false
        return try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            var view = overlayView
            if (view == null) {
                view = makeView(service)
                wm.addView(view, layoutParams(service))
                overlayView = view
            }
            view.text = text
            view.alpha = 1f
            mainHandler.removeCallbacks(hideOverlay)
            mainHandler.postDelayed(hideOverlay, OVERLAY_MS)
            true
        } catch (t: Throwable) {
            android.util.Log.w("Q25Toast", "overlay toast failed: $t")
            overlayView = null
            false
        }
    }

    /** Remove the overlay toast now (also called when the service stops). */
    fun dismissOverlay() {
        mainHandler.removeCallbacks(hideOverlay)
        val view = overlayView ?: return
        overlayView = null
        try {
            (view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun dp(context: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private fun makeView(context: Context): TextView {
        return TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val h = dp(context, 16f)
            val v = dp(context, 10f)
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                setColor(0xE6333333.toInt())
                cornerRadius = dp(context, 22f).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun layoutParams(context: Context): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(context, 64f)
            title = "Q25Toast"
        }
    }

    private fun showViaActivity(appContext: Context, text: String) {
        try {
            val intent = Intent(appContext, ToastActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ToastActivity.EXTRA_MESSAGE, text)
            }
            appContext.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private const val OVERLAY_MS = 2000L
}
