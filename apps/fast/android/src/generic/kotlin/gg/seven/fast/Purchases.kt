package gg.seven.fast

import android.content.Context
import gg.seven.kit.PurchaseSource
import gg.seven.kit.SourceBuildPurchaseSource

/** The generic build is the public source as anyone (or F-Droid) builds it: everything unlocked. */
@Suppress("UNUSED_PARAMETER")
internal fun purchases(context: Context): PurchaseSource = SourceBuildPurchaseSource()
