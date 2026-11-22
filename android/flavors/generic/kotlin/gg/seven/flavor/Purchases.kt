package gg.seven.flavor

import android.content.Context
import gg.seven.kit.PurchaseSource
import gg.seven.kit.SourceBuildPurchaseSource

/** The generic build is the public source as built by anyone (and by F-Droid): everything unlocked. */
@Suppress("UNUSED_PARAMETER")
fun sevenPurchases(context: Context, productId: String, publicKey: String): PurchaseSource =
    SourceBuildPurchaseSource()
