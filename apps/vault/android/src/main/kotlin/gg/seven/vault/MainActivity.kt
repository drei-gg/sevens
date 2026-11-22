package gg.seven.vault

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import gg.seven.kit.SevenRoot
import gg.seven.kit.SevenTheme

/** True while Vault is not the app in front: sensitive screens draw [PrivacyCover] over themselves. */
val LocalCovered = staticCompositionLocalOf { mutableStateOf(false) }

/** iOS `PrivacyShield`'s cover: the screen's contents hidden behind a plain "Vault locked". */
@Composable
fun PrivacyCover() {
    val covered by LocalCovered.current
    if (covered)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.lock_cover), style = MaterialTheme.typography.titleLarge)
        }
}

/**
 * The lock lifecycle (iOS `scenePhase`): FLAG_SECURE keeps every window out of screenshots, screen
 * recording and the recent-apps thumbnail; the contents are covered whenever the activity is not
 * resumed; the vault locks as soon as the app leaves the screen. The one exception is a system
 * screen Vault itself just opened (a picker, the share chooser, a permission or biometric prompt,
 * tracked by [SystemFlowGuard] from launch until its result or Vault's return): the lock waits for
 * it, bounded by the guard's short timeout, so an import doesn't lock the vault it is importing
 * into. Locking also clears temporary plaintext. FragmentActivity hosts BiometricPrompt.
 */
class MainActivity : FragmentActivity() {
    private val model by lazy { ViewModelProvider(this)[VaultModel::class.java] }
    private val covered = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private val pendingLock = Runnable { model.lock() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: `adb shell run-as <package> touch files/allow-screenshots` lets
        // screenshots through for UI review. Release builds always set the flag.
        if (!(BuildConfig.DEBUG && java.io.File(filesDir, "allow-screenshots").exists()))
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            SevenTheme {
                CompositionLocalProvider(LocalCovered provides covered) {
                    SevenRoot(model.store, vaultContent()) { VaultApp() }
                    PrivacyCover()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(pendingLock)
        model.endSystemFlow()
    }

    override fun onResume() {
        super.onResume()
        covered.value = false
        model.foreground()
    }

    override fun onPause() {
        super.onPause()
        if (!model.systemFlow.active) covered.value = true
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || model.library == null) return
        val wait = model.systemFlow.remaining()
        if (wait > 0) handler.postDelayed(pendingLock, wait) else model.lock()
    }
}
