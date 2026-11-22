package gg.seven.kit

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/*
 * Port of iOS SevenKit's Store, Gate content and About status: one place that knows whether the
 * paid features are available, and the app's own words for the three brand moments.
 */

/**
 * The trial, the purchase and the launch flags of one app. Owned by the app's ViewModel; call
 * [refresh] when the activity resumes so a trial that lapsed in the background is noticed.
 */
class SevenStore(
    context: Context,
    personalUnlock: Boolean,
    val purchases: PurchaseSource,
    scope: CoroutineScope,
) {
    val access = AccessStore(context, personalUnlock)
    /** A personal build's explicit build-time unlock counts as owning it (iOS DevUnlock). */
    private val personal = personalUnlock
    private val launch = context.getSharedPreferences("seven-launch", Context.MODE_PRIVATE)
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val purchase: StateFlow<PurchaseState> = purchases.state

    val entitlement: StateFlow<Entitlement> =
        combine(purchases.state, clock, ::resolve)
            .stateIn(scope, SharingStarted.Eagerly, resolve(purchases.state.value, clock.value))

    val paidFeaturesAvailable
        get() = entitlement.value.paidFeaturesAvailable

    private fun resolve(purchase: PurchaseState, now: Long): Entitlement =
        entitlementState(
            EntitlementFacts(
                trialStartedAt = access.trialStart.takeIf { it != 0L },
                hasUnlock = personal || purchase.owned,
                purchasePending = purchase.pending,
            ),
            now,
        )

    val canPurchase
        get() = canPurchase(purchase.value, entitlement.value)

    /**
     * The one sheet that may open at launch: Before you buy first, the trial-ended sheet only when
     * Before you buy isn't due (SPEC §13). Read it once the purchase source has [PurchaseState.loaded].
     */
    val launchSheet: LaunchSheetPlan
        get() =
            LaunchSheetPlan.next(
                beforeYouBuyDue = !beforeYouBuyShown,
                trialEndedDue = entitlement.value is Entitlement.Expired && !trialEndedShown,
            )

    fun refresh() {
        val now = System.currentTimeMillis()
        access.observeTime(now)
        clock.value = now
        purchases.refresh()
    }

    /** "Start my free 7 days". Does nothing once the trial has started or the unlock is owned. */
    fun startTrial() {
        if (entitlement.value != Entitlement.NotStarted) return
        val now = System.currentTimeMillis()
        access.startTrial(now)
        clock.value = now
    }

    fun unlock(activity: Activity) = purchases.purchase(activity)

    fun restore() = purchases.refresh()

    var beforeYouBuyShown: Boolean
        get() = launch.getBoolean("beforeYouBuy", false)
        set(value) = launch.edit().putBoolean("beforeYouBuy", value).apply()

    var trialEndedShown: Boolean
        get() = launch.getBoolean("trialEnded", false)
        set(value) = launch.edit().putBoolean("trialEnded", value).apply()

    fun close() = purchases.close()

}

/** The unlock can be bought: a price is known, nothing in flight, not pending or owned. */
fun canPurchase(purchase: PurchaseState, entitlement: Entitlement): Boolean =
    purchase.price != null &&
        !purchase.busy &&
        entitlement != Entitlement.Pending &&
        entitlement != Entitlement.Unlocked

/** "Unlock · $7.00", or plain "Unlock" while the price hasn't loaded. */
@Composable
fun PurchaseState.unlockTitle(): String =
    price?.let { stringResource(R.string.seven_unlock_price, it) }
        ?: stringResource(R.string.seven_unlock)

internal fun day(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** The trial status sentence (iOS AboutSection status). */
fun Entitlement.statusLine(resources: Resources, now: Long, notStarted: String? = null): String =
    when (this) {
        Entitlement.NotStarted ->
            notStarted ?: resources.getString(R.string.seven_status_not_started)
        is Entitlement.Trial -> {
            val today = TrialMath.dayNumber(endsAt - TrialMath.TRIAL_MILLIS, now)
            resources.getString(
                R.string.seven_status_trial,
                today,
                TrialMath.totalDays(TrialMath.TRIAL_MILLIS),
                day(endsAt),
            )
        }
        is Entitlement.Expired -> resources.getString(R.string.seven_status_expired)
        Entitlement.Unlocked -> resources.getString(R.string.seven_status_unlocked)
        Entitlement.Pending -> resources.getString(R.string.seven_status_pending)
    }

/**
 * First-launch honesty screen (SPEC §1): what the phone already does, what this adds, and "keep
 * your money". `{price}` in a paragraph becomes the Play price; without one, [Paragraph.withoutPrice].
 */
data class BeforeYouBuyContent(
    val title: String,
    val paragraphs: List<Paragraph>,
    /** The app's own wording, or SevenKit's "Start my free 7 days" when null. */
    val startTrialLabel: String? = null,
    /** The app's own wording, or SevenKit's "Look around first" when null. */
    val lookAroundLabel: String? = null,
) {
    data class Paragraph(val text: String, val withoutPrice: String? = null)

    /** The paragraphs with the price filled in; [noPrice] stands in when neither is known. */
    fun resolved(price: String?, noPrice: String): List<String> =
        paragraphs.map { p ->
            when {
                PRICE !in p.text -> p.text
                price != null -> p.text.replace(PRICE, price)
                else -> p.withoutPrice ?: noPrice
            }
        }

    companion object {
        const val PRICE = "{price}"
    }
}

/** What the unlock adds, listed on the unlock sheet. */
data class UnlockContent(val appName: String, val features: List<String>)

/** The one sheet after the trial ends. */
data class TrialEndedContent(val stillFree: List<String>, val unlockKeeps: List<String>)

/** One app's words for its brand moments. */
data class SevenContent(
    val beforeYouBuy: BeforeYouBuyContent,
    val unlock: UnlockContent,
    val trialEnded: TrialEndedContent,
    /** The About status line before the trial starts ("Scanning stays free."). */
    val notStartedStatus: String? = null,
)
