package gg.seven.fast

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.WorkerParameters
import gg.seven.fast.core.*
import gg.seven.kit.schedule.DueReminder
import gg.seven.kit.schedule.Precision
import gg.seven.kit.schedule.ReminderChannel
import gg.seven.kit.schedule.ReminderConfig
import gg.seven.kit.schedule.ReminderPlan
import gg.seven.kit.schedule.ReminderRecovery
import gg.seven.kit.schedule.ReminderRecoveryWorker
import gg.seven.kit.schedule.ReminderScheduler
import gg.seven.kit.schedule.WakeId
import gg.seven.kit.schedule.finishAsync
import gg.seven.kit.schedule.restoreReason
import java.time.Instant

/**
 * Fast's reminders on SevenKit's rolling scheduler (gg.seven.kit.schedule). Which reminders exist
 * and when each is due come from the core (reminder_schedule, reminders_due); this file supplies
 * only the words. Delivery is approximate by design: Fast never asks for exact-alarm access.
 */
object FastReminders {
    private const val PREFERENCES = "fast-reminders"

    /** Before 2026-11-22: `reminders/next`, epoch milliseconds, and no delivery ledger. */
    private const val LEGACY_PREFERENCES = "reminders"

    fun config(context: Context) =
        ReminderConfig(
            // The wake Fast first shipped: ReminderReceiver, no action, request code 1.
            wake = WakeId(ReminderReceiver::class.java, requestCode = 1),
            preferences = PREFERENCES,
            channel = ReminderChannel("chosen-reminders", context.getString(R.string.channel_reminders)),
            tagPrefix = "fast.",
            precision = Precision.Inexact,
            recovery =
                ReminderRecovery(
                    periodicName = "fast-reminder-recovery",
                    restoreName = "fast-reminder-restore",
                    worker = FastReminderWorker::class.java,
                ),
        )

    fun scheduler(context: Context): ReminderScheduler =
        (context.applicationContext as FastApplication).reminderScheduler

    fun create(context: Context): ReminderScheduler {
        val app = context.applicationContext as FastApplication
        val scheduler = ReminderScheduler(app, config(app)) { Plan(app, load(app)) }
        migrate(app, scheduler)
        return scheduler
    }

    /** Carries the old watermark over once, so the first wake after the upgrade still delivers. */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun migrate(context: Context, scheduler: ReminderScheduler) {
        val legacy = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
        if (!legacy.contains("next")) return
        val millis = legacy.getLong("next", 0)
        if (scheduler.ledger().next == 0L && millis > 0) scheduler.seed(millis / 1000)
        legacy.edit().remove("next").commit()
    }

    private suspend fun load(app: FastApplication): FastArchive {
        app.repository.load()
        return app.repository.state.value ?: error(app.getString(R.string.error_records_unreadable))
    }

    /** Refreshes the timer surfaces and reminders from the saved archive. */
    suspend fun reconcile(context: Context, deliver: Boolean = false) {
        val app = context.applicationContext as FastApplication
        TimerSurfaces.update(app, load(app))
        scheduler(app).reconcile(deliver)
    }

    private class Plan(private val context: Context, private val archive: FastArchive) :
        ReminderPlan {
        private val calendar = calendarSpec()

        override fun due(after: Long, now: Long): List<DueReminder> =
            remindersDue(archive, Instant.ofEpochSecond(after), Instant.ofEpochSecond(now), calendar)
                .map { DueReminder(it.id, it.at.epochSecond, it.postUntil.epochSecond, it.kind) }

        override fun next(now: Long): Long? =
            reminderSchedule(archive, Instant.ofEpochSecond(now), calendar)
                .minOfOrNull { it.at }
                ?.epochSecond

        override fun build(reminder: DueReminder, builder: NotificationCompat.Builder) {
            val text =
                context.getString(
                    when (reminder.payload as ReminderKind) {
                        ReminderKind.GOAL -> R.string.reminder_goal
                        ReminderKind.EATING -> R.string.reminder_eating
                        ReminderKind.WATER -> R.string.reminder_water
                        else -> R.string.reminder_plan
                    }
                )
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder
                .setSmallIcon(R.drawable.generic_icon)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
        }
    }
}

/** The rolling wake: post what came due, then arm the next. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        finishAsync { runCatching { FastReminders.reconcile(context, deliver = true) } }
    }
}

/** Puts reminders back after a reboot, an update, or a clock or time-zone change. */
class RestoreRemindersReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        restoreReason(intent.action) ?: return
        finishAsync { runCatching { FastReminders.reconcile(context) } }
    }
}

/** The periodic safety net (SevenKit's recovery worker). */
class FastReminderWorker(context: Context, parameters: WorkerParameters) :
    ReminderRecoveryWorker(context, parameters)
