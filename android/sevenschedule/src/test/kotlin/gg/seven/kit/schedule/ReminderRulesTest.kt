package gg.seven.kit.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRulesTest {
    private val now = 1_800_000_000L

    @Test
    fun aWakePostsOnlyOnceItsTimeHasCome() {
        assertFalse(ReminderRules.wakeIsDue(ReminderLedger(next = 0), now))
        assertFalse(ReminderRules.wakeIsDue(ReminderLedger(next = now + 1), now))
        assertTrue(ReminderRules.wakeIsDue(ReminderLedger(next = now), now))
    }

    @Test
    fun aLateWakeLooksBackToItsArmedTimeButNoMoreThanADay() {
        assertEquals(now - 61, ReminderRules.lookback(ReminderLedger(next = now - 60), now))
        assertEquals(now - DAY_SECONDS, ReminderRules.lookback(ReminderLedger(next = now - 3 * DAY_SECONDS), now))
    }

    @Test
    fun eachReminderIsPostedOncePerMoment() {
        val due = listOf(DueReminder("a", now - 10), DueReminder("b", now - 5))
        val ledger = ReminderLedger(delivered = mapOf("a" to now - 10, "b" to now - 86_000))
        // "a" was posted for this moment; "b" was posted for an earlier one, so this is new.
        assertEquals(listOf("b"), ReminderRules.toPost(due, ledger, now).map { it.tag })
        val after = ReminderRules.record(ledger, ReminderRules.toPost(due, ledger, now))
        assertTrue(ReminderRules.toPost(due, after, now).isEmpty())
    }

    @Test
    fun lapsedRemindersAreNotPostedLate() {
        val water = DueReminder("water", now - 3_600, postUntil = now - 1_800)
        val goal = DueReminder("goal", now - 3_600)
        assertEquals(listOf("goal"), ReminderRules.toPost(listOf(water, goal), ReminderLedger(), now).map { it.tag })
    }

    @Test
    fun alertsThatNoLongerMatchAreStale() {
        val active = listOf("bloom.a" to now - 10, "bloom.b" to now - 20, "bloom.c" to now - 30, "other" to 0L)
        val valid = listOf(DueReminder("bloom.a", now - 10), DueReminder("bloom.b", now - 99))
        // b's time changed and c is no longer due; "other" is not ours.
        assertEquals(listOf("bloom.b", "bloom.c"), ReminderRules.stale(active, "bloom.", valid))
    }

    @Test
    fun deliveryRecordsOlderThanADayAreDropped() {
        val kept = ReminderRules.retain(mapOf("old" to now - DAY_SECONDS, "new" to now - DAY_SECONDS + 1), now)
        assertEquals(setOf("new"), kept.keys)
    }
}
