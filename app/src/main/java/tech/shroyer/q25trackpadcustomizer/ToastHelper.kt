package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Central toast display.
 *
 * Shows a plain text toast directly from the application context. This used to launch
 * ToastActivity for every toast; starting an activity in the middle of an app switch is
 * expensive and competes with the app that is coming to the foreground, so it is now
 * only used as a fallback if a direct toast can't be shown.
 *
 * A new toast replaces the previous one instead of queueing behind it, so quick
 * successive switches don't produce a backlog of stale messages.
 */
object ToastHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: Toast? = null

    fun show(context: Context, text: String) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMain(appContext, text)
        } else {
            mainHandler.post { showOnMain(appContext, text) }
        }
    }

    private fun showOnMain(appContext: Context, text: String) {
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
}
