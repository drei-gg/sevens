package gg.seven.fast

import android.content.Context
import gg.seven.billing.PlayPurchaseSource
import gg.seven.kit.PurchaseSource

internal fun purchases(context: Context): PurchaseSource =
    PlayPurchaseSource(context, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY)
