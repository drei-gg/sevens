package gg.seven.fast

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import gg.seven.fast.core.*
import java.time.ZoneId
import kotlinx.coroutines.*

object ReminderScheduler {
    private const val channel = "chosen-reminders"

    private fun alarm(context: Context) =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun reconcile(context: Context, archive: FastArchive) {
        TimerSurfaces.update(context, archive)
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = alarm(context)
        manager.cancel(pending)
        val next =
            ReminderPlan.events(archive, nowMillis(), ZoneId.systemDefault()).minOfOrNull { it.at }
        context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit {
            putLong("next", next?.toLong() ?: 0)
        }
        if (next != null)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toLong(), pending)
    }

    fun deliver(context: Context, archive: FastArchive) {
        val due =
            context
                .getSharedPreferences("reminders", Context.MODE_PRIVATE)
                .getLong("next", 0)
                .toDouble()
        val now = nowMillis()
        val zone = ZoneId.systemDefault()
        if (due <= 0 || due > now) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                channel,
                "Your chosen reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        ReminderPlan.events(archive, due - 1, zone)
            .filter { it.at <= now }
            .forEach { event ->
                // Inexact Android alarms may be delayed. Never deliver a late water prompt outside
                // its chosen window.
                if (
                    event.kind == ReminderKind.WATER &&
                        (now - event.at > 30 * 60_000 ||
                            now.instant().atZone(zone).hour !in
                                archive.settings.water.startHour until
                                    archive.settings.water.endHour)
                )
                    return@forEach
                if (now - event.at > 86_400_000) return@forEach
                val text =
                    when (event.kind) {
                        ReminderKind.GOAL ->
                            "Your chosen fasting time has elapsed. Finish whenever you choose."
                        ReminderKind.EATING -> "Your chosen eating window has elapsed."
                        ReminderKind.WATER -> "Your optional water reminder."
                        else -> "Your planned fasting time is here. Nothing starts automatically."
                    }
                val intent =
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                NotificationManagerCompat.from(context)
                    .notify(
                        event.id.hashCode(),
                        NotificationCompat.Builder(context, channel)
                            .setSmallIcon(R.drawable.generic_icon)
                            .setContentTitle(context.getString(R.string.app_name))
                            .setContentText(text)
                            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                            .setAutoCancel(true)
                            .setContentIntent(intent)
                            .build(),
                    )
            }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        perform(context, true)
    }

    private fun perform(context: Context, deliver: Boolean) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as FastApplication).repository
                repo.load()
                repo.state.value?.let {
                    if (deliver) ReminderScheduler.deliver(context, it)
                    ReminderScheduler.reconcile(context, it)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class RestoreRemindersReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                )
        )
            return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as FastApplication).repository
                repo.load()
                repo.state.value?.let { ReminderScheduler.reconcile(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
