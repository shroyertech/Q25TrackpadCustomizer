package tech.shroyer.q25trackpadcustomizer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Status-bar notifications (currently used for root errors).
 */
object NotificationHelper {

    private const val CHANNEL_ID = "q25_errors"
    private const val CHANNEL_NAME = "Q25 errors"
    private const val CHANNEL_DESC = "Error notifications for Q25 Trackpad Customizer"

    private const val ROOT_ERROR_NOTIFICATION_ID = 1

    fun showRootError(context: Context, text: String) {
        if (AppState.rootErrorShown) return
        AppState.rootErrorShown = true

        val appContext = context.applicationContext
        createChannel(appContext)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Q25 Trackpad Customizer: Root required")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build()

        NotificationManagerCompat.from(appContext).notify(ROOT_ERROR_NOTIFICATION_ID, notification)
    }

    fun clearRootError(context: Context) {
        if (!AppState.rootErrorShown) return

        AppState.rootErrorShown = false
        val appContext = context.applicationContext
        NotificationManagerCompat.from(appContext).cancel(ROOT_ERROR_NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
            }

            nm.createNotificationChannel(channel)
        }
    }
}