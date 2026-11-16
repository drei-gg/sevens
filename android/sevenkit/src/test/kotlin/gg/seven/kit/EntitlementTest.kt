package gg.seven.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors iOS SevenKitCoreTests (EntitlementStateTests, TrialMathTests, LaunchSheetPlanTests). */
class EntitlementTest {
    private val start = 1_800_000_000_000L
    private val day = TrialMath.DAY_MILLIS
    private val week = 7 * day

    private fun state(facts: EntitlementFacts, offset: Long) = entitlementState(facts, start + offset)

    @Test
    fun nothingStartedIsNotStarted() {
        assertEquals(Entitlement.NotStarted, entitlementState(EntitlementFacts(), start))
    }

    @Test
    fun trialRunsForSevenDays() {
        val f = EntitlementFacts(trialStartedAt = start)
        assertEquals(Entitlement.Trial(start + week), state(f, 0))
        assertEquals(Entitlement.Trial(start + week), state(f, 3 * day))
    }

    @Test
    fun oneMillisecondBeforeSevenDaysIsStillTrial() {
        assertEquals(
            Entitlement.Trial(start + week),
            state(EntitlementFacts(trialStartedAt = start), week - 1),
        )
    }

    @Test
    fun exactlySevenDaysIsExpired() {
        assertEquals(
            Entitlement.Expired(start + week),
            state(EntitlementFacts(trialStartedAt = start), week),
        )
    }

    @Test
    fun afterSevenDaysIsExpired() {
        assertEquals(
            Entitlement.Expired(start + week),
            state(EntitlementFacts(trialStartedAt = start), week + 1),
        )
    }

    @Test
    fun clockMovedBackNeverExtendsOrPunishes() {
        val s = state(EntitlementFacts(trialStartedAt = start), -30 * day)
        assertEquals(Entitlement.Trial(start + week), s)
        assertTrue(s.paidFeaturesAvailable)
    }

    @Test
    fun unlockDuringTrialWins() {
        assertEquals(
            Entitlement.Unlocked,
            state(EntitlementFacts(trialStartedAt = start, hasUnlock = true), day),
        )
    }

    @Test
    fun unlockAfterExpiryWins() {
        assertEquals(
            Entitlement.Unlocked,
            state(EntitlementFacts(trialStartedAt = start, hasUnlock = true), 30 * day),
        )
    }

    @Test
    fun unlockWinsOverPending() {
        val f = EntitlementFacts(trialStartedAt = start, hasUnlock = true, purchasePending = true)
        assertEquals(Entitlement.Unlocked, state(f, 0))
    }

    @Test
    fun pendingNeverTakesAwayARunningTrial() {
        assertEquals(
            Entitlement.Pending,
            entitlementState(EntitlementFacts(purchasePending = true), start),
        )
        val f = EntitlementFacts(trialStartedAt = start, purchasePending = true)
        assertEquals(Entitlement.Trial(start + week), state(f, day))
        assertEquals(Entitlement.Pending, state(f, 8 * day))
        assertFalse(Entitlement.Pending.paidFeaturesAvailable)
    }

    @Test
    fun customTrialLength() {
        val f = EntitlementFacts(trialStartedAt = start)
        assertEquals(
            Entitlement.Expired(start + 60_000),
            entitlementState(f, start + 61_000, trialLength = 60_000),
        )
    }

    @Test
    fun dayNumberIsOneBasedAndClamped() {
        assertEquals(1, TrialMath.dayNumber(start, start))
        assertEquals(1, TrialMath.dayNumber(start, start + day - 1))
        assertEquals(2, TrialMath.dayNumber(start, start + day))
        assertEquals(7, TrialMath.dayNumber(start, start + 6 * day))
        assertEquals(7, TrialMath.dayNumber(start, start + 30 * day))
        assertEquals(1, TrialMath.dayNumber(start, start - 5 * day))
    }

    @Test
    fun daysRemainingRoundsUpAndStopsAtZero() {
        val end = start + week
        assertEquals(7, TrialMath.daysRemaining(end, start))
        assertEquals(1, TrialMath.daysRemaining(end, end - 1))
        assertEquals(0, TrialMath.daysRemaining(end, end))
        assertEquals(0, TrialMath.daysRemaining(end, end + day))
    }

    @Test
    fun totalDays() {
        assertEquals(7, TrialMath.totalDays(week))
        assertEquals(1, TrialMath.totalDays(10_000))
    }

    @Test
    fun nothingDueShowsNothing() {
        assertEquals(LaunchSheetPlan.None, LaunchSheetPlan.next(false, false))
    }

    @Test
    fun eachOneAloneIsShown() {
        assertEquals(LaunchSheetPlan.BeforeYouBuy, LaunchSheetPlan.next(true, false))
        assertEquals(LaunchSheetPlan.TrialEnded, LaunchSheetPlan.next(false, true))
    }

    @Test
    fun beforeYouBuyWinsWhenBothAreDue() {
        assertEquals(LaunchSheetPlan.BeforeYouBuy, LaunchSheetPlan.next(true, true))
    }
}
