package gg.seven.flavor

import android.content.Context
import gg.seven.billing.PlayPurchaseSource
import gg.seven.kit.PurchaseSource

/** The store build's one non-consumable unlock, verified on the device. */
fun sevenPurchases(context: Context, productId: String, publicKey: String): PurchaseSource =
    PlayPurchaseSource(context, productId, publicKey)
