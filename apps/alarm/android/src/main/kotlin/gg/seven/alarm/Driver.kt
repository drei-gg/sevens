package gg.seven.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import gg.seven.alarm.core.CalendarSpec
import gg.seven.alarm.core.Delivery
import gg.seven.alarm.core.Registration
import gg.seven.alarm.core.registrationNext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

/** This device's calendar for the core: its zone and first weekday (1 Sunday … 7 Saturday). */
fun deviceCalendar(): CalendarSpec {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return CalendarSpec(ZoneId.systemDefault().id, if (first == DayOfWeek.SUNDAY) 1 else first.value + 1)
}

/**
 * The system side of a registration (iOS `AlarmDriver`). Android has no repeating weekly alarm
 * and no list of what is set, so [schedule] arms the registration's next ring (the core's
 * `registrationNext`) and the receiver re-arms it when it rings; [isLive] asks whether its pending
 * intent still exists.
 */
interface AlarmDriver {
    /** One line: what the phone will actually do (iOS `status`). */
    val status: String

    fun isLive(row: Registration): Boolean

    /** Arms the registration's next ring; throws [LoopFailure] with what to do when it can't. */
    fun schedule(row: Registration)

    fun cancel(row: Registration)

    /** Silences the registration if it is ringing now. */
    fun stop(row: Registration)
}

class NativeAlarmDriver(private val context: Context) : AlarmDriver {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    val exactAllowed
        get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    val notificationsAllowed
        get() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    val fullScreenAllowed
        get() =
            Build.VERSION.SDK_INT < 34 ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private val notificationsAsked
        get() =
            Build.VERSION.SDK_INT < 33 ||
                context.getSharedPreferences("alarm-permissions", Context.MODE_PRIVATE).getBoolean("asked", false) ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override val status: String
        get() =
            when {
                !exactAllowed -> "Alarms blocked in Settings"
                !notificationsAllowed && !notificationsAsked -> "Alarm permission not requested"
                !notificationsAllowed -> "Notifications blocked in Settings"
                !fullScreenAllowed -> "Full-screen alarms blocked in Settings"
                else -> "Alarms allowed"
            }

    override fun isLive(row: Registration) = pending(row.id, PendingIntent.FLAG_NO_CREATE) != null

    override fun schedule(row: Registration) {
        if (row.routine.delivery == Delivery.REMINDER) {
            if (!notificationsAllowed) throw LoopFailure("Allow notifications in Settings to deliver quiet reminders.")
        } else if (!exactAllowed) throw LoopFailure("Allow alarms in Settings before relying on this schedule.")
        val next = registrationNext(row, Instant.now(), deviceCalendar())
        val operation = pending(row.id, PendingIntent.FLAG_UPDATE_CURRENT)!!
        if (next == null) {
            // A one-off whose time has passed: nothing is left to ring.
            alarms.cancel(operation)
            operation.cancel()
            return
        }
        val at = next.toEpochMilli()
        when {
            row.routine.delivery == Delivery.ALARM -> {
                // A real alarm clock: exact in Doze, shown as the next alarm in the status bar and
                // on the lock screen; tapping that opens the app.
                val show =
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
            }
            // Quiet reminders are notifications: exact while idle when allowed, otherwise as close
            // as Android lets an idle phone wake.
            exactAllowed -> alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    override fun cancel(row: Registration) {
        pending(row.id, PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
        NotificationManagerCompat.from(context).cancel(row.id.toString(), REMINDER_NOTIFICATION)
    }

    override fun stop(row: Registration) {
        if (row.routine.delivery == Delivery.ALARM && Ringing.current.value?.id == row.id) RingingService.silence(context)
    }

    private fun pending(id: UUID, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, fireIntent(context, id), flag or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val REMINDER_NOTIFICATION = 7

        /** One intent per registration: the data URI tells them apart. */
        fun fireIntent(context: Context, id: UUID) =
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .setData("alarm-loops://registration/$id".toUri())

        fun fireId(intent: Intent): UUID? =
            intent.data?.lastPathSegment?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}

/** Keeps registrations in memory and never touches the system (iOS PreviewAlarmDriver; tests). */
class PreviewAlarmDriver : AlarmDriver {
    val rows = linkedMapOf<UUID, Registration>()
    var failure: String? = null
    override val status = "Preview mode: no alarms are scheduled"

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
