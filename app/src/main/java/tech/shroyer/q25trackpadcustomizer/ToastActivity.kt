package tech.shroyer.q25trackpadcustomizer

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

// Headless activity whose only job is to show a toast, then immediately finish.
// And here I am without any jam or butter.
class ToastActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = intent.getStringExtra(EXTRA_MESSAGE)

        if (!message.isNullOrEmpty()) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }

        // Immediately finish - no UI is ever shown
        finish()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}