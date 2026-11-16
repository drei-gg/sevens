package gg.seven.kit

/*
 * Port of iOS SevenKitCore (Entitlement, TrialMath, LaunchSheetPlan): the pure rules that decide
 * access and which sheet may open at launch. No Android here, so the JVM tests mirror the Swift
 * ones line for line. Times are epoch milliseconds.
 */

/** Where this install stands. Records stay readable, editable and exportable in every state. */
sealed interface Entitlement {
    data object NotStarted : Entitlement

    data class Trial(val endsAt: Long) : Entitlement

    data class Expired(val endedAt: Long) : Entitlement

    data object Unlocked : Entitlement

    /** A purchase waiting for approval (family approval, a slow payment), with no running trial. */
    data object Pending : Entitlement

    /** Trial or unlocked. */
    val paidFeaturesAvailable
        get() = this is Trial || this == Unlocked
}

/** What the store knows; [entitlementState] turns it into an [Entitlement]. */
data class EntitlementFacts(
    val trialStartedAt: Long? = null,
    val hasUnlock: Boolean = false,
    val purchasePending: Boolean = false,
)

/**
 * Unlock wins over everything (pending, an expired trial). A running trial wins over a pending
 * purchase: waiting for approval never takes away days already given. Pending shows only when
 * there's no running trial. A clock moved back before the start never extends the trial (the end is
 * always start + length) and never punishes it either.
 */
fun entitlementState(
    facts: EntitlementFacts,
    now: Long,
    trialLength: Long = TrialMath.TRIAL_MILLIS,
): Entitlement {
    if (facts.hasUnlock) return Entitlement.Unlocked
    facts.trialStartedAt?.let { start ->
        val end = start + trialLength
        if (now < end) return Entitlement.Trial(end)
        return if (facts.purchasePending) Entitlement.Pending else Entitlement.Expired(end)
    }
    return if (facts.purchasePending) Entitlement.Pending else Entitlement.NotStarted
}

/** Helpers for showing trial progress. None of these decide access; [entitlementState] does. */
object TrialMath {
    const val DAY_MILLIS = 86_400_000L
    const val TRIAL_MILLIS = 7 * DAY_MILLIS

    /** 1-based day of the trial, clamped to `1..totalDays`. A clock before the start gives 1. */
    fun dayNumber(startedAt: Long, now: Long, trialLength: Long = TRIAL_MILLIS): Int {
        val elapsed = (now - startedAt).coerceAtLeast(0)
        return (elapsed / DAY_MILLIS + 1).toInt().coerceIn(1, totalDays(trialLength))
    }

    /** Whole days left, rounded up, never negative. Exactly at the end it is 0. */
    fun daysRemaining(endsAt: Long, now: Long): Int {
        val left = endsAt - now
        return if (left <= 0) 0 else ((left + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
    }

    fun totalDays(trialLength: Long): Int =
        ((trialLength + DAY_MILLIS - 1) / DAY_MILLIS).toInt().coerceAtLeast(1)
}

/**
 * Which sheet may open at launch (SPEC §13). Before you buy and the trial-ended sheet can both be
 * due on one launch (a reinstall after the trial); Before you buy comes first and the trial-ended
 * sheet waits for the next launch, so the two are never shown together.
 */
enum class LaunchSheetPlan {
    None,
    BeforeYouBuy,
    TrialEnded;

    companion object {
        fun next(beforeYouBuyDue: Boolean, trialEndedDue: Boolean): LaunchSheetPlan =
            when {
                beforeYouBuyDue -> BeforeYouBuy
                trialEndedDue -> TrialEnded
                else -> None
            }
    }
}
