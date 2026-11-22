package gg.seven.kit

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
    /** A build from the public source: everything unlocked, nothing to buy (owned is true). */
    val sourceBuild: Boolean = false,
    /** SevenKit's own [message], as a string resource, so it follows the device language. */
    @StringRes val messageRes: Int? = null,
)

/** The source's note for the person: [PurchaseState.message], else [PurchaseState.messageRes]. */
@Composable
fun PurchaseState.messageText(): String? = message ?: messageRes?.let { stringResource(it) }

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
                messageRes = R.string.seven_purchase_local,
                loaded = true,
            )
        )

    override fun refresh() = Unit

    override fun purchase(activity: Activity) = Unit

    override fun close() = Unit
}

/**
 * The generic build, compiled from the public GPL source (and the one F-Droid would ship): every
 * feature unlocked, no trial, nothing for sale. Paying on Google Play is convenience and support.
 */
class SourceBuildPurchaseSource : PurchaseSource {
    override val state =
        MutableStateFlow(
            PurchaseState(
                owned = true,
                loaded = true,
                sourceBuild = true,
                messageRes = R.string.seven_purchase_source_build,
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

    fun label(resources: Resources, purchase: PurchaseState, now: Long): String =
        when {
            personalUnlock -> resources.getString(R.string.seven_access_personal)
            purchase.sourceBuild -> resources.getString(R.string.seven_access_source_build)
            purchase.owned -> resources.getString(R.string.seven_access_owned)
            remaining(now) > 0 -> {
                val days = ((remaining(now) + 86_399_999) / 86_400_000).toInt()
                resources.getQuantityString(R.plurals.seven_access_trial_days_left, days, days)
            }
            trialStart == 0L -> resources.getString(R.string.seven_access_not_started)
            else -> resources.getString(R.string.seven_access_trial_ended)
        }
}
