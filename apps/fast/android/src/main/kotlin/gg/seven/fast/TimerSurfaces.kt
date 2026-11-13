package gg.seven.fast

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import gg.seven.fast.core.*
import kotlinx.coroutines.*

/** Android counterparts to the display-only WidgetKit / Live Activity surfaces. No journal data. */
object TimerSurfaces {
    private const val notificationId = 700

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
        val views = RemoteViews(context.packageName, R.layout.fast_widget)
        views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
        views.setOnClickPendingIntent(R.id.widget_root, launch)
        views.setViewVisibility(R.id.widget_clock, if (active != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_empty, if (active == null) View.VISIBLE else View.GONE)
        if (active != null) {
            views.setChronometer(
                R.id.widget_clock,
                SystemClock.elapsedRealtime() - active.duration(now).toLong(),
                null,
                true,
            )
            views.setTextViewText(
                R.id.widget_detail,
                active.goalDate?.let { "Goal · ${stamp(it)}" } ?: "Open ended",
            )
        } else {
            views.setTextViewText(R.id.widget_empty, "No fast running")
            views.setTextViewText(R.id.widget_detail, "Open to start, view or edit your records")
        }
        val widgetManager = AppWidgetManager.getInstance(context)
        widgetManager.updateAppWidget(
            widgetManager.getAppWidgetIds(ComponentName(context, FastWidget::class.java)),
            views,
        )
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
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(launch)
                .build(),
        )
    }
}

class FastWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = (context.applicationContext as FastApplication).repository
                repository.load()
                repository.state.value?.let { TimerSurfaces.update(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
