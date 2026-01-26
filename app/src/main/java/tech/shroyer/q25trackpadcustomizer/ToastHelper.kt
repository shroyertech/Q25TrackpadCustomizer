package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.content.Intent

// Centralize toast display by launching ToastActivity in its own task,
// so toasts always behave consistently without bringing the main UI to front.
object ToastHelper {

    fun show(context: Context, text: String) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, ToastActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ToastActivity.EXTRA_MESSAGE, text)
        }
        appContext.startActivity(intent)
    }
}