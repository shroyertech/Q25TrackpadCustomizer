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

    private const val STATUS_CHANNEL_ID = "q25_mode_status"
    private const val STATUS_NOTIFICATION_ID = 2

    /** Ongoing, silent notification showing the current mode, with a Toggle button. */
    fun showModeStatus(context: Context, modeLabel: String, appLabel: String?) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(STATUS_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(STATUS_CHANNEL_ID, "Current mode", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows the current trackpad mode"
                        setShowBadge(false)
                    }
                )
            }
        }
        val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val open = android.app.PendingIntent.getActivity(
            appContext, 0,
            android.content.Intent(appContext, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            piFlags
        )
        val toggle = android.app.PendingIntent.getBroadcast(
            appContext, 1,
            android.content.Intent(QuickToggleReceiver.ACTION_QUICK_TOGGLE)
                .setClass(appContext, QuickToggleReceiver::class.java),
            piFlags
        )
        val n = NotificationCompat.Builder(appContext, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trackpad)
            .setContentTitle("Trackpad: $modeLabel")
            .setContentText(if (appLabel != null) "In $appLabel" else "Tap Toggle to switch modes")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Toggle", toggle)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(STATUS_NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun clearModeStatus(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(STATUS_NOTIFICATION_ID)
    }

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