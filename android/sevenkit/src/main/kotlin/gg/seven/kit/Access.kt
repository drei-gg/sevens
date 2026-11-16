package gg.seven.kit

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Entitlements are separate from user backups. Importing a file can never buy the app.
data class PurchaseState(
    val owned: Boolean = false,
    val price: String? = null,
    val pending: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false,
    /** The source has answered once (owned or not), so launch decisions can be made. */
    val loaded: Boolean = false,
)

interface PurchaseSource {
    val state: StateFlow<PurchaseState>

    fun refresh()

    fun purchase(activity: Activity)

    fun close()
}

class LocalPurchaseSource : PurchaseSource {
    override val state =
        MutableStateFlow(
            PurchaseState(
                message = "Purchases are available in the Google Play edition.",
                loaded = true,
            )
        )

    override fun refresh() = Unit

    override fun purchase(activity: Activity) = Unit

    override fun close() = Unit
}

class AccessStore(context: Context, private val personalUnlock: Boolean) {
    private val prefs = context.getSharedPreferences("seven-trial", Context.MODE_PRIVATE)
    val trialStart
        get() = prefs.getLong("start", 0L)

    fun startTrial(now: Long = System.currentTimeMillis()) {
        if (trialStart == 0L)
            check(prefs.edit().putLong("start", now).putLong("lastSeen", now).commit()) {
                "Could not save trial"
            }
    }

    fun observeTime(now: Long = System.currentTimeMillis()) {
        if (now - prefs.getLong("lastSeen", 0) >= 60_000)
            prefs.edit().putLong("lastSeen", now).apply()
    }

    fun remaining(now: Long) =
        if (trialStart == 0L) 0L
        else
            (trialStart + 7 * 86_400_000L - maxOf(now, prefs.getLong("lastSeen", 0))).coerceAtLeast(
                0
            )

    fun canCreate(purchase: PurchaseState, now: Long) =
        personalUnlock || purchase.owned || remaining(now) > 0

    fun label(purchase: PurchaseState, now: Long) =
        when {
            personalUnlock -> "Personal build · unlocked"
            purchase.owned -> "Unlocked · paid once"
            remaining(now) > 0 ->
                "Free trial · ${(remaining(now) + 86_399_999) / 86_400_000} days left"
            trialStart == 0L -> "Try everything free for seven days"
            else -> "Trial ended · your records stay yours"
        }
}
