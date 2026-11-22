package gg.seven.kit.schedule

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The app's current schedule, read fresh for each reconcile (usually from its archive and Rust
 * core). Times are epoch seconds.
 */
interface ReminderPlan {
    /** What came due after [after] and by [now]. */
    fun due(after: Long, now: Long): List<DueReminder>

    /** The next moment anything is due strictly after [now], or null for nothing upcoming. */
    fun next(now: Long): Long?

    /**
     * Fills in [builder] for [reminder]: icon, title, text, tap action. The scheduler has already
     * set the channel, timestamp, private visibility, auto-cancel and alert-once.
     */
    fun build(reminder: DueReminder, builder: NotificationCompat.Builder)
}

/** The notification channel reminders post to, created on every reconcile. */
data class ReminderChannel(
    val id: String,
    val name: String,
    val description: String? = null,
    val importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
)

/**
 * One app's rolling reminder scheduler. The identities are what the app first shipped, so armed
 * wakes and saved state survive an upgrade.
 *
 * @param wake the single wake; its receiver calls [ReminderScheduler.reconcile] with `deliver`.
 * @param preferences the SharedPreferences file holding the [ReminderLedger].
 * @param tagPrefix every notification tag this scheduler posts starts with it; others are left
 *   alone.
 * @param notificationId posted with the tag (tag plus id is the notification's identity).
 * @param precision [Precision.ExactIfAllowed] or [Precision.Inexact].
 * @param recovery work names and worker class for [ReminderScheduler.maintain] and
 *   [ReminderScheduler.restoreLater], or null when the app has none.
 */
data class ReminderConfig(
    val wake: WakeId,
    val preferences: String,
    val channel: ReminderChannel,
    val tagPrefix: String,
    val notificationId: Int = 1,
    val precision: Precision = Precision.ExactIfAllowed,
    val recovery: ReminderRecovery? = null,
)

/** WorkManager names and the worker that reconciles (a [ReminderRecoveryWorker] subclass). */
data class ReminderRecovery(
    val periodicName: String,
    val restoreName: String,
    val worker: Class<out ListenableWorker>,
    val periodHours: Long = 12,
)

/** What the scheduler can say about delivery; each app has its own words for these. */
enum class ReminderState {
    NOTIFICATIONS_OFF,
    FAILED,
    NONE_UPCOMING,
    APPROXIMATE,
    PRECISE,
}

/**
 * One rolling wake per app, re-read from the app's schedule after every delivery, edit, boot and
 * clock change (Bloom's design, shared on 2026-11-22):
 * - A wake posts what came due since it was armed (up to a day back), each reminder once: a ledger
 *   of what was posted is committed before the receiver returns.
 * - Posted alerts that no longer match the schedule are removed.
 * - The next wake is armed after the ledger is saved; when anything fails, the failure is saved and
 *   a retry wake is armed, so a broken read never silently ends reminders.
 * Reconciles run one at a time.
 */
class ReminderScheduler(
    private val context: Context,
    val config: ReminderConfig,
    private val plan: suspend () -> ReminderPlan,
) {
    private val lock = Mutex()
    private val wakes = SystemWakes(context)
    private val access = ScheduleAccess(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(config.preferences, Context.MODE_PRIVATE)

    /** The saved ledger, read as Bloom wrote it: `next`, `delivered` (JSON) and `failure`. */
    fun ledger(): ReminderLedger {
        val store = preferences()
        val json =
            runCatching { JSONObject(store.getString("delivered", "{}")!!) }
                .getOrDefault(JSONObject())
        return ReminderLedger(
            next = store.getLong("next", 0),
            delivered = json.keys().asSequence().associateWith { json.getLong(it) },
            failure = store.getString("failure", null),
        )
    }

    // Synchronous commits: the ledger must survive process death before a receiver returns, and a
    // failed write must be noticed. KTX `edit` does not expose commit failure.
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun save(ledger: ReminderLedger) {
        val delivered = JSONObject()
        ledger.delivered.forEach { (tag, at) -> delivered.put(tag, at) }
        check(
            preferences()
                .edit()
                .putLong("next", ledger.next)
                .putString("delivered", delivered.toString())
                .apply {
                    if (ledger.failure == null) remove("failure")
                    else putString("failure", ledger.failure)
                }
                .commit()
        ) {
            "Could not persist reminder schedule."
        }
    }

    /** Starts the ledger at [next] (epoch seconds) when an app moves its old state over once. */
    fun seed(next: Long) = save(ledger().copy(next = next))

    /** The delivery state for the app's status line. */
    fun state(): ReminderState {
        val ledger = ledger()
        return when {
            !access.canNotify(config.channel.id) -> ReminderState.NOTIFICATIONS_OFF
            ledger.failure != null -> ReminderState.FAILED
            ledger.next == 0L -> ReminderState.NONE_UPCOMING
            config.precision == Precision.Inexact || !access.exactAllowed ->
                ReminderState.APPROXIMATE
            else -> ReminderState.PRECISE
        }
    }

    /**
     * Posts what is due when [deliver] is set and the wake's time has come, removes stale alerts,
     * saves the ledger and arms the next wake. Throws after saving the failure (and arming a retry)
     * if the schedule cannot be read or saved.
     */
    suspend fun reconcile(deliver: Boolean = false, now: Long = Instant.now().epochSecond) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                createChannel()
                try {
                    val plan = plan()
                    var ledger = ledger()
                    val canNotify = access.canNotify(config.channel.id)
                    if (deliver && canNotify && ReminderRules.wakeIsDue(ledger, now)) {
                        val due = plan.due(ReminderRules.lookback(ledger, now), now)
                        val posted = ReminderRules.toPost(due, ledger, now)
                        for (reminder in posted) post(plan, reminder)
                        ledger = ReminderRules.record(ledger, posted)
                    }
                    val active =
                        notifications.activeNotifications
                            .filter { it.id == config.notificationId }
                            .mapNotNull { n -> n.tag?.let { it to n.notification.`when` / 1000 } }
                    val valid = plan.due(now - DAY_SECONDS, now)
                    for (tag in ReminderRules.stale(active, config.tagPrefix, valid))
                        notifications.cancel(tag, config.notificationId)
                    val next = plan.next(now)
                    wakes.cancel(config.wake)
                    val kept = ReminderRules.retain(ledger.delivered, now)
                    save(ReminderLedger(next ?: 0, kept, failure = null))
                    if (next != null) wakes.arm(config.wake, next * 1000, config.precision)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    // Keep the old `next`, so the retry replays from where delivery stopped.
                    runCatching { save(ledger().copy(failure = "schedule")) }
                    val retry = (now + RETRY_SECONDS) * 1000
                    runCatching { wakes.arm(config.wake, retry, Precision.Inexact) }
                    throw error
                }
            }
        }

    private fun createChannel() {
        val channel = config.channel
        notifications.createNotificationChannel(
            NotificationChannel(channel.id, channel.name, channel.importance).apply {
                description = channel.description
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    @SuppressLint("MissingPermission") // canNotify() was checked by the caller.
    private fun post(plan: ReminderPlan, reminder: DueReminder) {
        val builder =
            NotificationCompat.Builder(context, config.channel.id)
                .setWhen(reminder.at * 1000)
                .setShowWhen(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
        plan.build(reminder, builder)
        if (!access.postPermissionGranted) return
        notifications.notify(reminder.tag, config.notificationId, builder.build())
    }

    /** Cancels the wake and every notification the app posted, and forgets the ledger. */
    @SuppressLint("ApplySharedPref", "UseKtx")
    suspend fun clear() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                wakes.cancel(config.wake)
                notifications.cancelAll()
                check(preferences().edit().clear().commit()) { "Could not clear reminder state." }
            }
        }

    /** Keeps the periodic recovery reconcile enqueued (a safety net if a wake is ever lost). */
    fun maintain() {
        val recovery = config.recovery ?: return
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                recovery.periodicName,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(recovery.worker, recovery.periodHours, TimeUnit.HOURS)
                    .build(),
            )
    }

    /** Reconciles soon under WorkManager, which outlives a receiver's short time limit. */
    fun restoreLater() {
        val recovery = config.recovery ?: return
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                recovery.restoreName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(recovery.worker).build(),
            )
    }

    companion object {
        /** When a failed reconcile tries again. */
        const val RETRY_SECONDS = 15 * 60L
    }
}

/** An Application that owns a [ReminderScheduler], so workers and receivers can find it. */
interface ReminderHost {
    val reminderScheduler: ReminderScheduler
}

/**
 * Reconciles with delivery, retrying on failure. Apps subclass it under the class name they first
 * shipped: WorkManager stores that name with enqueued work.
 */
open class ReminderRecoveryWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        try {
            (applicationContext as ReminderHost).reminderScheduler.reconcile(deliver = true)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
}
