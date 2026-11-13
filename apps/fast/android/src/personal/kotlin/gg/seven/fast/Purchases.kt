package gg.seven.fast

import android.content.Context
import gg.seven.kit.LocalPurchaseSource
import gg.seven.kit.PurchaseSource

internal fun purchases(context: Context): PurchaseSource = LocalPurchaseSource()
