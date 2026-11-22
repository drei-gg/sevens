package gg.seven.qr

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import gg.seven.kit.SevenRoot
import gg.seven.kit.SevenTheme
import gg.seven.qr.core.ScannedCode
import gg.seven.qr.core.Symbology

class MainActivity : ComponentActivity() {
    private val model
        get() = ViewModelProvider(this)[QrModel::class.java]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QrLists.start(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) applyDebugExtras(intent)
        val model = model
        setContent {
            val res = LocalResources.current
            val content = remember(res) { qrContent(res) }
            SevenTheme { SevenRoot(model.store, content) { QrApp(model) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyDebugExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        model.store.refresh()
    }

    override fun onStop() {
        super.onStop()
        model.flush()
    }

    /**
     * Debug builds only (iOS QRLaunchArgument): `qr.inject` (Base64 of a payload) shows its result
     * at launch; `qr.skipFirstLaunch` marks Before you buy as shown. Nothing here grants access.
     */
    private fun applyDebugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra(EXTRA_SKIP_FIRST_LAUNCH, false)) model.store.beforeYouBuyShown = true
        val encoded = intent.getStringExtra(EXTRA_INJECT) ?: return
        val bytes = decodeInject(encoded) ?: return
        model.pendingCode.value = ScannedCode(ScanDecoding.strictUtf8(bytes), bytes, Symbology.QR)
    }

    companion object {
        const val EXTRA_INJECT = "qr.inject"
        const val EXTRA_SKIP_FIRST_LAUNCH = "qr.skipFirstLaunch"
        private const val MAX_INJECT_BYTES = 64 * 1024

        /** Standard or URL-safe Base64, padding optional. Null when empty, invalid or too long. */
        fun decodeInject(text: String): ByteArray? {
            val s = text.trim().replace('-', '+').replace('_', '/')
            if (s.isEmpty() || s.length > MAX_INJECT_BYTES * 2) return null
            val padded = s + "=".repeat((4 - s.length % 4) % 4)
            return runCatching { Base64.decode(padded, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= MAX_INJECT_BYTES }
        }
    }
}
