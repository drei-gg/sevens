package gg.seven.fast.wear

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import gg.seven.fast.wire.WireSnapshot

/** Everything outside the app that shows the timer: the tile, the complication and the ongoing activity. */
object Surfaces {
    private const val CHANNEL = "running-fast"
    private const val NOTIFICATION = 7

    fun update(context: Context, snapshot: WireSnapshot?) {
        runCatching { TileService.getUpdater(context).requestUpdate(FastTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, FastComplicationService::class.java))
                .requestUpdateAll()
        }
        ongoing(context, snapshot)
    }

    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** An Ongoing Activity while a fast runs, so the timer is a tap away from the watch face. */
    fun ongoing(context: Context, snapshot: WireSnapshot?) {
        val manager = NotificationManagerCompat.from(context)
        val start = snapshot?.start
        if (start == null) {
            manager.cancel(NOTIFICATION)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_running))
                .build()
        )
        val open = openApp(context)
        val title = context.getString(R.string.fasting)
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_fast)
                .setContentTitle(title)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setWhen(start)
                .setUsesChronometer(true)
                .setContentIntent(open)
        // The ongoing-activity clock runs on elapsedRealtime.
        val zero = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - start)
        OngoingActivity.Builder(context, NOTIFICATION, notification)
            .setStaticIcon(R.drawable.ic_fast)
            .setTouchIntent(open)
            .setTitle(title)
            .setStatus(Status.Builder().addTemplate("$title #time#").addPart("time", Status.StopwatchPart(zero)).build())
            .build()
            .apply(context)
        manager.notify(NOTIFICATION, notification.build())
    }
}
