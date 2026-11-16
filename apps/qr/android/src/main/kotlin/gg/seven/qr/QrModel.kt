package gg.seven.qr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore

class QrModel(app: Application) : AndroidViewModel(app) {
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )

    override fun onCleared() = store.close()
}
