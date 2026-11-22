package gg.seven.fast

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import gg.seven.fast.core.*
import kotlinx.coroutines.*

/** Android counterparts to the display-only WidgetKit / Live Activity surfaces. No journal data. */
object TimerSurfaces {
    private const val notificationId = 700
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun update(context: Context, archive: FastArchive) {
        val now = nowMillis()
        val active = archive.active
        val launch =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        WidgetGoalRefresh.schedule(context, archive)
        widgetScope.launch { FastGlanceWidget.refresh(context) }
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (active == null || !archive.settings.liveActivity) {
            notifications.cancel(notificationId)
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        notifications.createNotificationChannel(
            NotificationChannel(
                "timer",
                "Current fasting timer",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        notifications.notify(
            notificationId,
            NotificationCompat.Builder(context, "timer")
                .setSmallIcon(R.drawable.generic_icon)
                .setContentTitle("Fasting timer")
                .setContentText(
                    active.goalDate?.let { "Goal · ${stamp(it)}" }
                        ?: "Open ended · finish when you choose"
                )
                .setWhen(active.start.toEpochMilli())
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // Android 16+: shown as a Live Update (status-bar chip, lock screen). Ignored
                // on older versions, where it stays an ongoing notification.
                .setRequestPromotedOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(launch)
                .build(),
        )
    }
}
