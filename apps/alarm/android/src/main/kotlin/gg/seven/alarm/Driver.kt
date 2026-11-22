package gg.seven.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import gg.seven.alarm.core.CalendarSpec
import gg.seven.alarm.core.Delivery
import gg.seven.alarm.core.Registration
import gg.seven.alarm.core.registrationNext
import gg.seven.kit.schedule.Precision
import gg.seven.kit.schedule.ScheduleAccess
import gg.seven.kit.schedule.SystemWakes
import gg.seven.kit.schedule.WakeId
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** This device's calendar for the core: its zone and first weekday (1 Sunday … 7 Saturday). */
fun deviceCalendar(): CalendarSpec {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return CalendarSpec(
        ZoneId.systemDefault().id,
        if (first == DayOfWeek.SUNDAY) 1 else first.value + 1,
    )
}

/**
 * The system side of a registration (iOS `AlarmDriver`). Android has no repeating weekly alarm and
 * no list of what is set, so [schedule] arms the registration's next ring (the core's
 * `registrationNext`) and the receiver re-arms it when it rings; [isLive] asks whether its pending
 * intent still exists.
 */
interface AlarmDriver {
    /** One line: what the phone will actually do (iOS `status`). */
    val status: String

    fun isLive(row: Registration): Boolean

    /** A widget also reflects permissions at the last app check; this is never delivery proof. */
    fun widgetIsLive(row: Registration): Boolean = isLive(row)

    /** Arms the registration's next ring; throws [LoopFailure] with what to do when it can't. */
    fun schedule(row: Registration)

    fun cancel(row: Registration)

    /** Silences the registration if it is ringing now. */
    fun stop(row: Registration)
}

/**
 * The real driver, on SevenKit's scheduler (`gg.seven.kit.schedule`): one wake per registration,
 * identified exactly as before the move (AlarmReceiver, [AlarmReceiver.ACTION_FIRE] and the
 * registration's data URI, request code 0), so alarms armed by an earlier version stay live and are
 * never duplicated.
 */
class NativeAlarmDriver(private val context: Context) : AlarmDriver {
    private val wakes = SystemWakes(context)
    private val access = ScheduleAccess(context)

    val exactAllowed
        get() = access.exactAllowed

    val notificationsAllowed
        get() = access.notificationsAllowed

    val fullScreenAllowed
        get() = access.fullScreenAllowed

    /** Whether the user left the ringing alarm's channel audible (not set to "None"). */
    val alarmChannelOn
        get() = access.channelEnabled(Ringing.ALARM_CHANNEL)

    private val notificationsAsked
        get() =
            context
                .getSharedPreferences("alarm-permissions", Context.MODE_PRIVATE)
                .getBoolean("asked", false) || access.postPermissionGranted

    override val status: String
        get() =
            when {
                !exactAllowed -> Words.get(R.string.status_alarms_blocked)
                !notificationsAllowed && !notificationsAsked ->
                    Words.get(R.string.status_not_requested)
                !notificationsAllowed -> Words.get(R.string.status_notifications_blocked)
                !alarmChannelOn -> Words.get(R.string.status_channel_muted)
                !fullScreenAllowed -> Words.get(R.string.status_full_screen_blocked)
                else -> Words.get(R.string.status_allowed)
            }

    override fun isLive(row: Registration) = wakes.isArmed(wake(row.id))

    override fun widgetIsLive(row: Registration): Boolean =
        isLive(row) &&
            notificationsAllowed &&
            (row.routine.delivery == Delivery.REMINDER ||
                (exactAllowed && fullScreenAllowed && alarmChannelOn))

    override fun schedule(row: Registration) {
        if (row.routine.delivery == Delivery.REMINDER) {
            if (!notificationsAllowed)
                throw LoopFailure(Words.get(R.string.error_allow_notifications))
        } else if (!exactAllowed)
            throw LoopFailure(Words.get(R.string.error_allow_alarms))
        val next = registrationNext(row, Instant.now(), deviceCalendar())
        if (next == null) {
            // A one-off whose time has passed: nothing is left to ring.
            wakes.cancel(wake(row.id))
            return
        }
        val precision =
            if (row.routine.delivery == Delivery.ALARM) {
                // A real alarm clock: exact in Doze, shown as the next alarm in the status bar and
                // on the lock screen; tapping that opens the app.
                val show =
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                Precision.AlarmClock(show)
            }
            // Quiet reminders are notifications: exact while idle when allowed, otherwise as close
            // as Android lets an idle phone wake.
            else Precision.ExactIfAllowed
        wakes.arm(wake(row.id), next.toEpochMilli(), precision)
    }

    override fun cancel(row: Registration) {
        wakes.cancel(wake(row.id))
        NotificationManagerCompat.from(context).cancel(row.id.toString(), REMINDER_NOTIFICATION)
    }

    override fun stop(row: Registration) {
        if (row.routine.delivery == Delivery.ALARM) RingingService.silence(row.id)
    }

    companion object {
        const val REMINDER_NOTIFICATION = 7

        /** A registration's wake: the data URI tells them apart. */
        fun wake(id: UUID) =
            WakeId(
                AlarmReceiver::class.java,
                AlarmReceiver.ACTION_FIRE,
                "alarm-loops://registration/$id".toUri(),
            )

        /** One intent per registration (the Stop action and the ringing notification reuse it). */
        fun fireIntent(context: Context, id: UUID) = wake(id).intent(context)

        fun fireId(intent: Intent): UUID? =
            intent.data?.lastPathSegment?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}

/** Keeps registrations in memory and never touches the system (iOS PreviewAlarmDriver; tests). */
class PreviewAlarmDriver : AlarmDriver {
    val rows = linkedMapOf<UUID, Registration>()
    var failure: String? = null
    override val status: String
        get() = Words.get(R.string.status_preview)

    override fun isLive(row: Registration) = row.id in rows

    override fun schedule(row: Registration) {
        failure?.let { throw LoopFailure(it) }
        rows[row.id] = row
    }

    override fun cancel(row: Registration) {
        rows.remove(row.id)
    }

    override fun stop(row: Registration) = Unit
}
