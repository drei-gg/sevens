package gg.seven.bloom

import android.app.Application
import android.app.PendingIntent
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.work.WorkerParameters
import gg.seven.bloom.core.bloomDashboard
import gg.seven.bloom.core.bloomDueReminders
import gg.seven.kit.schedule.DueReminder
import gg.seven.kit.schedule.Precision
import gg.seven.kit.schedule.ReminderChannel
import gg.seven.kit.schedule.ReminderConfig
import gg.seven.kit.schedule.ReminderHost
import gg.seven.kit.schedule.ReminderPlan
import gg.seven.kit.schedule.ReminderRecovery
import gg.seven.kit.schedule.ReminderRecoveryWorker
import gg.seven.kit.schedule.ReminderScheduler
import gg.seven.kit.schedule.ReminderState
import gg.seven.kit.schedule.ScheduleAccess
import gg.seven.kit.schedule.WakeId
import gg.seven.kit.schedule.finishAsync
import gg.seven.kit.schedule.restoreReason
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

class BloomApplication : Application(), ReminderHost {
    val repository by lazy { BloomRepository(this) }
    override val reminderScheduler by lazy {
        ReminderScheduler(this, BloomReminders.config()) {
            BloomReminders.Plan(this, repository.read(), ZoneId.systemDefault().id)
        }
    }
}

/**
 * Bloom's care reminders on SevenKit's rolling scheduler (gg.seven.kit.schedule), which grew out of
 * this file: one wake re-read from the Rust schedule after delivery, edits, boot and clock changes.
 * Its identities are the ones Bloom shipped (the `gg.seven.bloom.DUE` wake, the `bloom-reminders`
 * ledger, the recovery work names and worker class), so an upgrade keeps everything armed.
 */
object BloomReminders {
    const val CHANNEL = "care-reminders"

    fun config() =
        ReminderConfig(
            wake = WakeId(BloomReminderReceiver::class.java, "gg.seven.bloom.DUE"),
            preferences = "bloom-reminders",
            channel =
                ReminderChannel(
                    CHANNEL,
                    "Care reminders",
                    "Reminders you choose for plans and appointments",
                ),
            tagPrefix = "bloom.",
            precision = Precision.ExactIfAllowed,
            recovery =
                ReminderRecovery(
                    periodicName = "bloom-reminder-recovery",
                    restoreName = "bloom-restore",
                    worker = BloomReminderWorker::class.java,
                ),
        )

    private fun scheduler(c: Context) = (c.applicationContext as BloomApplication).reminderScheduler

    fun enabled(c: Context): Boolean = ScheduleAccess(c).canNotify(CHANNEL)

    fun exact(c: Context): Boolean = ScheduleAccess(c).exactAllowed

    fun status(c: Context): String =
        when (scheduler(c).state()) {
            ReminderState.NOTIFICATIONS_OFF ->
                "Notifications are off. Allow them to receive your care reminders."
            ReminderState.FAILED ->
                "Reminders need attention. Open the journal again to retry scheduling."
            ReminderState.NONE_UPCOMING -> "No upcoming reminders. Add or enable a plan in Care."
            ReminderState.APPROXIMATE ->
                "Approximate delivery. Android may delay alerts; allow precise alarms for time-sensitive reminders."
            ReminderState.PRECISE -> "Precise alarms enabled. Your next reminder is scheduled."
        }

    fun maintain(c: Context) = scheduler(c).maintain()

    /** Posts what is due (with [deliver]), removes stale alerts and arms the next wake. */
    suspend fun reconcile(
        c: Context,
        deliver: Boolean = false,
        now: Long = Instant.now().epochSecond,
    ) {
        try {
            scheduler(c).reconcile(deliver, now)
        } finally {
            BloomGlanceWidget.refresh(c)
        }
    }

    suspend fun clear(c: Context) = scheduler(c).clear()

    /** The current journal's reminders, from the Rust core. */
    class Plan(private val context: Context, private val archive: String, private val zone: String) :
        ReminderPlan {
        override fun due(after: Long, now: Long): List<DueReminder> {
            val rows = JSONArray(bloomDueReminders(archive, after, now, zone))
            return (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                DueReminder(row.getString("id"), row.getLong("at"), payload = row)
            }
        }

        override fun next(now: Long): Long? =
            JSONObject(bloomDashboard(archive, now, zone)).rows("reminders").firstOrNull()?.getLong("at")

        override fun build(reminder: DueReminder, builder: NotificationCompat.Builder) {
            val row = reminder.payload as JSONObject
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .putExtra("open_care", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val text = row.getString("body")
            builder
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle(row.getString("title"))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
        }
    }
}

class BloomReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "gg.seven.bloom.DUE") return
        // A failure is saved and retried by the scheduler; Care shows it.
        finishAsync { runCatching { BloomReminders.reconcile(context, deliver = true) } }
    }
}

class BloomRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        restoreReason(intent.action) ?: return
        // WorkManager holds the background execution lease beyond the receiver's short lifetime.
        (context.applicationContext as BloomApplication).reminderScheduler.restoreLater()
    }
}

/** Kept under the class name Bloom shipped: WorkManager stores it with enqueued work. */
class BloomReminderWorker(context: Context, parameters: WorkerParameters) :
    ReminderRecoveryWorker(context, parameters) {
    override suspend fun doWork(): Result =
        try {
            BloomReminders.reconcile(applicationContext, deliver = true) // and the widget
            Result.success()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
}
