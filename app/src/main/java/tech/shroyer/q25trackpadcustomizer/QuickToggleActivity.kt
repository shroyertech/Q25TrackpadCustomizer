package tech.shroyer.q25trackpadcustomizer

import android.app.Activity
import android.os.Bundle

/**
 * Quick toggle entry point for shortcuts/keymappers. No UI is shown.
 */
class QuickToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickToggle.perform(this)
        finish()
    }
}
