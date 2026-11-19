package tech.shroyer.q25trackpadcustomizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Quick Toggle by broadcast, for key mapper / automation apps (and the mode notification's
 * Toggle button): action [ACTION_QUICK_TOGGLE], package tech.shroyer.q25trackpadcustomizer.
 * Lighter than starting [QuickToggleActivity] and never takes focus from the current app.
 */
class QuickToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_QUICK_TOGGLE) return
        QuickToggle.perform(context)
    }

    companion object {
        const val ACTION_QUICK_TOGGLE = "tech.shroyer.q25trackpadcustomizer.QUICK_TOGGLE"
    }
}
