package gg.seven.kit.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * A wake's identity: the [PendingIntent] the system holds for it, matched by receiver, action, data
 * and request code. Keep it exactly as an app first shipped it. A changed identity orphans the
 * wakes already armed on users' phones: they would still fire, and nothing would cancel them.
 */
data class WakeId(
    val receiver: Class<out BroadcastReceiver>,
    val action: String? = null,
    val data: Uri? = null,
    val requestCode: Int = 0,
) {
    fun intent(context: Context): Intent =
        Intent(context, receiver).also { intent ->
            action?.let { intent.action = it }
            data?.let { intent.data = it }
        }
}

/** How exactly a wake should fire. */
sealed interface Precision {
    /**
     * A real alarm clock: exact even in Doze, shown as the phone's next alarm in the status bar and
     * on the lock screen; tapping that opens [show]. Needs exact-alarm access.
     */
    data class AlarmClock(val show: PendingIntent) : Precision

    /**
     * Exact while idle when the app may schedule exact alarms, otherwise as close as Android
     * allows.
     */
    data object ExactIfAllowed : Precision

    /** Whenever an idle phone next wakes near the time. Never asks for exact-alarm access. */
    data object Inexact : Precision
}

/** What a wake was actually armed with. */
enum class Armed {
    ALARM_CLOCK,
    EXACT,
    INEXACT,
}

/**
 * AlarmManager for the Sevens: arm, check and cancel [WakeId]s. Android keeps no list of what is
 * set, so [isArmed] asks whether the wake's pending intent still exists; Android drops every wake
 * on reboot, so apps re-arm from their saved schedule (see [restoreReason]).
 */
class SystemWakes(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Whether exact alarms are allowed (always before Android 12). */
    val exactAllowed: Boolean
        get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun isArmed(wake: WakeId): Boolean = pending(wake, PendingIntent.FLAG_NO_CREATE) != null

    /**
     * Arms [wake] at [atMillis] (epoch milliseconds), replacing any earlier time. An [AlarmClock]
     * without exact-alarm access throws [SecurityException]; [ExactIfAllowed] falls back to
     * inexact instead. On failure a pending intent this call created is discarded, so no orphan is
     * left looking armed, while one that existed before stays as it was.
     */
    // The app declares exact-alarm access (USE_EXACT_ALARM for Alarm, SCHEDULE_EXACT_ALARM for
    // Bloom); ExactIfAllowed checks it first and AlarmClock callers check it and handle the throw.
    @android.annotation.SuppressLint("MissingPermission")
    fun arm(wake: WakeId, atMillis: Long, precision: Precision): Armed {
        val existed = isArmed(wake)
        val operation = pending(wake, PendingIntent.FLAG_UPDATE_CURRENT)!!
        try {
            return when (precision) {
                is Precision.AlarmClock -> {
                    val info = AlarmManager.AlarmClockInfo(atMillis, precision.show)
                    alarms.setAlarmClock(info, operation)
                    Armed.ALARM_CLOCK
                }
                Precision.ExactIfAllowed ->
                    if (exactAllowed)
                        try {
                            alarms.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                atMillis,
                                operation,
                            )
                            Armed.EXACT
                        } catch (_: SecurityException) {
                            // Access may be revoked between the check and the call.
                            inexact(atMillis, operation)
                        }
                    else inexact(atMillis, operation)
                Precision.Inexact -> inexact(atMillis, operation)
            }
        } catch (error: Exception) {
            if (!existed) operation.cancel()
            throw error
        }
    }

    private fun inexact(atMillis: Long, operation: PendingIntent): Armed {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        return Armed.INEXACT
    }

    /** Cancels [wake] and discards its pending intent; nothing happens if it was not armed. */
    fun cancel(wake: WakeId) {
        pending(wake, PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    private fun pending(wake: WakeId, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            wake.requestCode,
            wake.intent(context),
            flag or PendingIntent.FLAG_IMMUTABLE,
        )
}
