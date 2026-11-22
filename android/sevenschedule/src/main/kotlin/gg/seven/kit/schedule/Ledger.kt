package gg.seven.kit.schedule

/** One day in seconds: how far back a late wake looks, and how long a posted alert stays valid. */
const val DAY_SECONDS = 86_400L

/**
 * A reminder the app's core says came due. [at] is the moment it was due (epoch seconds) and the
 * alert's timestamp; [postUntil] the latest moment a late wake may still show it for the first
 * time. [payload] is the app's own data for building the notification.
 */
data class DueReminder(
    val tag: String,
    val at: Long,
    val postUntil: Long = at + DAY_SECONDS,
    val payload: Any? = null,
)

/**
 * The rolling scheduler's saved state: the one wake armed ([next], epoch seconds, 0 for none), what
 * was already posted ([delivered], tag to the moment it was due) and whether the last reconcile
 * failed. Stored in Bloom's original format (see [ReminderScheduler]).
 */
data class ReminderLedger(
    val next: Long = 0,
    val delivered: Map<String, Long> = emptyMap(),
    val failure: String? = null,
)

/**
 * How a rolling wake turns "what came due" into notifications exactly once. These are Android
 * delivery mechanics: when a reminder is due is decided by each app's Rust core.
 */
object ReminderRules {
    /** Whether a wake should post: the armed time has come. */
    fun wakeIsDue(ledger: ReminderLedger, now: Long): Boolean = ledger.next in 1..now

    /**
     * Where a late wake starts looking: just before the time it was armed for, so nothing due at
     * that very second is missed, but never more than a day back.
     */
    fun lookback(ledger: ReminderLedger, now: Long): Long =
        maxOf(ledger.next - 1, now - DAY_SECONDS)

    /** What to post now: due, not yet posted for that same moment, and not lapsed. */
    fun toPost(due: List<DueReminder>, ledger: ReminderLedger, now: Long): List<DueReminder> =
        due.filter { ledger.delivered[it.tag] != it.at && now <= it.postUntil }

    /** The ledger after posting [posted]. */
    fun record(ledger: ReminderLedger, posted: List<DueReminder>): ReminderLedger =
        ledger.copy(delivered = ledger.delivered + posted.associate { it.tag to it.at })

    /**
     * Posted alerts that no longer match the current schedule: the plan was paused, deleted or
     * edited, or the alert is over a day old. [active] pairs each posted tag with its timestamp in
     * seconds; [valid] is what the core says came due in the last day. Only tags starting with
     * [prefix] are considered.
     */
    fun stale(
        active: List<Pair<String, Long>>,
        prefix: String,
        valid: List<DueReminder>,
    ): List<String> {
        val times = valid.associate { it.tag to it.at }
        return active
            .filter { (tag, at) -> tag.startsWith(prefix) && times[tag] != at }
            .map { it.first }
    }

    /** Drops delivery records older than a day: nothing that old is replayed. */
    fun retain(delivered: Map<String, Long>, now: Long): Map<String, Long> =
        delivered.filterValues { it > now - DAY_SECONDS }
}
