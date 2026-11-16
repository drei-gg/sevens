package gg.seven.billing

import android.app.Activity
import android.content.Context
import android.util.Base64
import com.android.billingclient.api.*
import gg.seven.kit.PurchaseSource
import gg.seven.kit.PurchaseState
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One non-consumable. Google Play's signed purchase is verified locally; no app server or
 * analytics.
 */
class PlayPurchaseSource(
    context: Context,
    private val productId: String,
    private val publicKey: String,
) : PurchaseSource {
    override val state = MutableStateFlow(PurchaseState())
    private val packageName = context.packageName
    private val cache = context.getSharedPreferences("seven-play", Context.MODE_PRIVATE)
    private var product: ProductDetails? = null
    private var connecting = false
    private val client =
        BillingClient.newBuilder(context.applicationContext)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .enableAutoServiceReconnection()
            .setListener { result, _ ->
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK,
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
                    BillingClient.BillingResponseCode.USER_CANCELED ->
                        state.update { it.copy(busy = false, message = "Purchase cancelled") }
                    else ->
                        state.update {
                            it.copy(
                                busy = false,
                                message = "Google Play could not complete the purchase. Try again.",
                            )
                        }
                }
            }
            .build()

    init {
        val data = cache.getString("data", null)
        val signature = cache.getString("signature", null)
        if (data != null && signature != null)
            runCatching { Purchase(data, signature) }
                .getOrNull()
                ?.takeIf(::valid)
                ?.let { state.value = PurchaseState(owned = true) }
        refresh()
    }

    private fun valid(p: Purchase): Boolean =
        p.packageName == packageName &&
            p.purchaseState == Purchase.PurchaseState.PURCHASED &&
            productId in p.products &&
            runCatching {
                    val key =
                        KeyFactory.getInstance("RSA")
                            .generatePublic(
                                X509EncodedKeySpec(Base64.decode(publicKey, Base64.DEFAULT))
                            )
                    Signature.getInstance("SHA1withRSA").run {
                        initVerify(key)
                        update(p.originalJson.toByteArray(Charsets.UTF_8))
                        verify(Base64.decode(p.signature, Base64.DEFAULT))
                    }
                }
                .getOrDefault(false)

    override fun refresh() {
        if (!client.isReady) {
            if (connecting) return
            connecting = true
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        connecting = false
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) query()
                        else
                            state.update {
                                it.copy(
                                    busy = false,
                                    loaded = true,
                                    message =
                                        "Google Play is unavailable. Saved access still works offline.",
                                )
                            }
                    }

                    override fun onBillingServiceDisconnected() {
                        connecting = false
                    }
                }
            )
        } else query()
    }

    private fun query() {
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()
        ) { result, details ->
            product =
                if (result.responseCode == BillingClient.BillingResponseCode.OK)
                    details.productDetailsList.firstOrNull()
                else null
            state.update {
                it.copy(
                    price = product?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice,
                    message =
                        if (product == null) "Unlock is unavailable on Google Play right now."
                        else null,
                )
            }
        }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                state.update {
                    it.copy(
                        busy = false,
                        loaded = true,
                        message = "Could not refresh purchases. Try Restore again when online.",
                    )
                }
                return@queryPurchasesAsync
            }
            val owned = purchases.firstOrNull(::valid)
            val pending =
                purchases.any {
                    productId in it.products && it.purchaseState == Purchase.PurchaseState.PENDING
                }
            // Only a successful Play query revokes cached access. Errors never masquerade as an
            // empty query.
            if (owned == null) cache.edit().clear().apply()
            else
                cache
                    .edit()
                    .putString("data", owned.originalJson)
                    .putString("signature", owned.signature)
                    .apply()
            state.update {
                it.copy(
                    owned = owned != null,
                    pending = pending,
                    busy = false,
                    loaded = true,
                    message =
                        if (pending) "Payment pending. Access starts when Google Play confirms it."
                        else it.message,
                )
            }
            if (owned != null && !owned.isAcknowledged)
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(owned.purchaseToken)
                        .build()
                ) { ack ->
                    if (ack.responseCode != BillingClient.BillingResponseCode.OK)
                        state.update {
                            it.copy(
                                message =
                                    "Purchase received; confirmation will retry when the app reopens."
                            )
                        }
                }
        }
    }

    override fun purchase(activity: Activity) {
        val detail = product ?: return refresh()
        val offer = detail.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: return
        val params =
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(detail)
                .apply { offer.offerToken?.let { setOfferToken(it) } }
                .build()
        state.update { it.copy(busy = true, message = null) }
        val result =
            client.launchBillingFlow(
                activity,
                BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build(),
            )
        if (result.responseCode != BillingClient.BillingResponseCode.OK)
            state.update { it.copy(busy = false, message = "Could not open Google Play checkout.") }
    }

    override fun close() {
        client.endConnection()
    }
}
