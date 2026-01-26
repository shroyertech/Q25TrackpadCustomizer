package tech.shroyer.q25trackpadcustomizer

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Legacy launcher shortcut creator for QuickToggleActivity.
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shortcutIntent = Intent(this, QuickToggleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }

        val resultIntent = Intent().apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            putExtra(Intent.EXTRA_SHORTCUT_NAME, "Q25 Trackpad Customizer - Quick Toggle")
            putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(
                    this@ShortcutActivity,
                    R.mipmap.ic_launcher_foreground
                )
            )
        }

        setResult(RESULT_OK, resultIntent)
        finish()
    }
}