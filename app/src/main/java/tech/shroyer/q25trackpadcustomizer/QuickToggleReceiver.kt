package tech.shroyer.q25trackpadcustomizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Mode switching by broadcast, for key mapper / automation apps and the mode notification:
 *  - [ACTION_QUICK_TOGGLE]: cycle to the next Quick Toggle mode
 *  - [ACTION_SET_MODE] with extra [EXTRA_MODE] = "mouse", "keyboard" or "scroll": pick one
 * Package tech.shroyer.q25trackpadcustomizer. Lighter than starting [QuickToggleActivity]
 * and never takes focus from the current app.
 */
class QuickToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_QUICK_TOGGLE -> QuickToggle.perform(context)
            ACTION_SET_MODE -> {
                val mode = when (intent.getStringExtra(EXTRA_MODE)?.lowercase()) {
                    "mouse" -> Mode.MOUSE
                    "keyboard" -> Mode.KEYBOARD
                    "scroll", "scroll_wheel" -> Mode.SCROLL_WHEEL
                    else -> return
                }
                QuickToggle.setMode(context, mode)
            }
        }
    }

    companion object {
        const val ACTION_QUICK_TOGGLE = "tech.shroyer.q25trackpadcustomizer.QUICK_TOGGLE"
        const val ACTION_SET_MODE = "tech.shroyer.q25trackpadcustomizer.SET_MODE"
        const val EXTRA_MODE = "mode"
    }
}
