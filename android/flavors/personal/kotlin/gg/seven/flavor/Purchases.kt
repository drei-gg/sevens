package gg.seven.flavor

import android.content.Context
import gg.seven.kit.LocalPurchaseSource
import gg.seven.kit.PurchaseSource

/** No purchase provider outside the store build: the local 7-day trial (or a personal unlock). */
@Suppress("UNUSED_PARAMETER")
fun sevenPurchases(context: Context, productId: String, publicKey: String): PurchaseSource =
    LocalPurchaseSource()
