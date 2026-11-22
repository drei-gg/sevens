package gg.seven.bloom

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import gg.seven.bloom.core.bloomDashboard
import gg.seven.bloom.core.bloomDueReminders
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class BloomApplication : Application() {
    val repository by lazy { BloomRepository(this) }
}

/**
 * One rolling alarm; the Rust schedule is re-read after delivery, edits, boot and clock changes.
 */
// Synchronous preference commits happen on IO: the alarm and replay watermark must survive
// process death before returning from a receiver. KTX edit does not expose commit failure.
@android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
object BloomReminders {
    const val CHANNEL = "care-reminders"
    private val lock = Mutex()

    private fun prefs(c: Context) = c.getSharedPreferences("bloom-reminders", Context.MODE_PRIVATE)

    private fun alarm(c: Context) =
        PendingIntent.getBroadcast(
            c,
            0,
            Intent(c, BloomReminderReceiver::class.java).setAction("gg.seven.bloom.DUE"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun enabled(c: Context): Boolean {
        val manager = c.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(CHANNEL)?.importance ?: 3) !=
                NotificationManager.IMPORTANCE_NONE
    }

    fun exact(c: Context) =
        Build.VERSION.SDK_INT < 31 ||
            c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun status(c: Context): String =
        when {
            !enabled(c) -> "Notifications are off. Allow them to receive your care reminders."
            prefs(c).getString("failure", null) != null ->
                "Reminders need attention. Open the journal again to retry scheduling."
            prefs(c).getLong("next", 0) == 0L ->
                "No upcoming reminders. Add or enable a plan in Care."
            !exact(c) ->
                "Approximate delivery. Android may delay alerts; allow precise alarms for time-sensitive reminders."
            else -> "Precise alarms enabled. Your next reminder is scheduled."
        }

    fun maintain(c: Context) {
        WorkManager.getInstance(c)
            .enqueueUniquePeriodicWork(
                "bloom-reminder-recovery",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BloomReminderWorker>(12, TimeUnit.HOURS).build(),
            )
    }

    suspend fun reconcile(
        c: Context,
        deliver: Boolean = false,
        now: Long = Instant.now().epochSecond,
    ) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val manager = c.getSystemService(AlarmManager::class.java)
                val notifications = c.getSystemService(NotificationManager::class.java)
                notifications.createNotificationChannel(
                    NotificationChannel(
                            CHANNEL,
                            "Care reminders",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        )
                        .apply {
                            description = "Reminders you choose for plans and appointments"
                            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                        }
                )
                val store = prefs(c)
                try {
                    val repository = (c.applicationContext as BloomApplication).repository
                    val archiveText = repository.read()
                    val zone = ZoneId.systemDefault().id
                    val scheduled = store.getLong("next", 0)
                    val delivered = JSONObject(store.getString("delivered", "{}")!!)
                    if (deliver && scheduled in 1..now && enabled(c)) {
                        val lookback = maxOf(scheduled - 1, now - 86400)
                        val rows = JSONArray(bloomDueReminders(archiveText, lookback, now, zone))
                        val due = (0 until rows.length()).map { rows.getJSONObject(it) }
                        for (event in due) {
                            val tag = event.getString("id")
                            val at = event.getLong("at")
                            if (delivered.optLong(tag) == at) continue
                            val open =
                                PendingIntent.getActivity(
                                    c,
                                    0,
                                    Intent(c, MainActivity::class.java)
                                        .putExtra("open_care", true)
                                        .addFlags(
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        ),
                                    PendingIntent.FLAG_UPDATE_CURRENT or
                                        PendingIntent.FLAG_IMMUTABLE,
                                )
                            val text = event.getString("body")
                            val notification =
                                NotificationCompat.Builder(c, CHANNEL)
                                    .setSmallIcon(R.drawable.ic_reminder)
                                    .setContentTitle(event.getString("title"))
                                    .setContentText(text)
                                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                                    .setAutoCancel(true)
                                    .setContentIntent(open)
                                    .setOnlyAlertOnce(true)
                                    .setWhen(at * 1000)
                                    .build()
                            notifications.notify(tag, 1, notification)
                            delivered.put(tag, at)
                        }
                    }
                    // Re-evaluate the current archive: paused/deleted plans, corrected doses and
                    // edited
                    // appointments must remove their stale delivered alerts, even with other active
                    // plans.
                    val recent = JSONArray(bloomDueReminders(archiveText, now - 86400, now, zone))
                    val validTimes =
                        (0 until recent.length()).associate { index ->
                            val event = recent.getJSONObject(index)
                            event.getString("id") to event.getLong("at")
                        }
                    for (notification in notifications.activeNotifications) {
                        val tag = notification.tag ?: continue
                        if (
                            tag.startsWith("bloom.") &&
                                validTimes[tag] != notification.notification.`when` / 1000
                        )
                            notifications.cancel(tag, 1)
                    }
                    BloomGlanceWidget.refresh(c)
                    val next =
                        JSONObject(bloomDashboard(archiveText, now, zone))
                            .rows("reminders")
                            .firstOrNull()
                            ?.getLong("at")
                    val retained = JSONObject()
                    delivered.keys().forEach { id ->
                        if (delivered.getLong(id) > now - 86400)
                            retained.put(id, delivered.getLong(id))
                    }
                    manager.cancel(alarm(c))
                    check(
                        store
                            .edit()
                            .putLong("next", next ?: 0)
                            .putString("delivered", retained.toString())
                            .remove("failure")
                            .commit()
                    ) {
                        "Could not persist reminder schedule."
                    }
                    if (next != null && enabled(c)) {
                        try {
                            if (exact(c))
                                manager.setExactAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    next * 1000,
                                    alarm(c),
                                )
                            else
                                manager.setAndAllowWhileIdle(
                                    AlarmManager.RTC_WAKEUP,
                                    next * 1000,
                                    alarm(c),
                                )
                        } catch (_: SecurityException) {
                            // Permission may change between the capability check and scheduling.
                            manager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                next * 1000,
                                alarm(c),
                            )
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    manager.cancel(alarm(c))
                    store.edit().putString("failure", "schedule").commit()
                    throw error
                }
            }
        }

    suspend fun clear(c: Context) =
        withContext(Dispatchers.IO) {
            c.getSystemService(AlarmManager::class.java).cancel(alarm(c))
            c.getSystemService(NotificationManager::class.java).cancelAll()
            check(prefs(c).edit().clear().commit()) { "Could not clear reminder state." }
        }
}

class BloomReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "gg.seven.bloom.DUE") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BloomReminders.reconcile(context, deliver = true)
            } catch (_: Exception) {
                /* Recovery worker retries; Care shows a failure state. */
            } finally {
                pending.finish()
            }
        }
    }
}

class BloomRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                )
        )
            return
        // WorkManager holds the background execution lease beyond the receiver's short lifetime.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "bloom-restore",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BloomReminderWorker>().build(),
            )
    }
}

class BloomReminderWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        try {
            BloomReminders.reconcile(applicationContext, deliver = true)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
}
