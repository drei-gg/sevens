package gg.seven.kit.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Why the system asked an app to put its schedule back. Every wake is gone after a reboot; after a
 * clock or zone change, armed wakes hold stale instants and must be re-armed even if they look
 * live.
 *
 * Receivers list these actions in the manifest: `LOCKED_BOOT_COMPLETED` (direct-boot-aware apps
 * only), `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` and
 * `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.
 */
enum class RestoreReason {
    LOCKED_BOOT,
    BOOT,
    PACKAGE_REPLACED,
    CLOCK_CHANGED,
    ZONE_CHANGED,
    EXACT_ACCESS_CHANGED;

    /** The clock moved under armed wakes: re-arm everything. */
    val clockMoved: Boolean
        get() = this == CLOCK_CHANGED || this == ZONE_CHANGED
}

/** The [RestoreReason] for a system broadcast, or null for anything else. */
fun restoreReason(intent: Intent?): RestoreReason? =
    when (intent?.action) {
        Intent.ACTION_LOCKED_BOOT_COMPLETED -> RestoreReason.LOCKED_BOOT
        Intent.ACTION_BOOT_COMPLETED -> RestoreReason.BOOT
        Intent.ACTION_MY_PACKAGE_REPLACED -> RestoreReason.PACKAGE_REPLACED
        Intent.ACTION_TIME_CHANGED -> RestoreReason.CLOCK_CHANGED
        Intent.ACTION_TIMEZONE_CHANGED -> RestoreReason.ZONE_CHANGED
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
            RestoreReason.EXACT_ACCESS_CHANGED
        else -> null
    }

/** Where receivers run their work: off the main thread, one failure never cancelling another. */
val ScheduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Runs [block] after the receiver returns, holding the broadcast open until it finishes (the system
 * allows about ten seconds). Works before the first unlock, unlike WorkManager.
 */
fun BroadcastReceiver.finishAsync(
    scope: CoroutineScope = ScheduleScope,
    block: suspend () -> Unit,
) {
    val pending = goAsync()
    scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
