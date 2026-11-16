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
            Text("Vault locked", style = MaterialTheme.typography.titleLarge)
        }
}

/**
 * The lock lifecycle (iOS `scenePhase`): FLAG_SECURE keeps every window out of screenshots, screen
 * recording and the recent-apps thumbnail; the contents are covered whenever the activity is not
 * resumed; the vault locks when the app leaves the screen. A system picker, camera or share sheet
 * that Vault itself opened gets five minutes before the lock, so an import doesn't lock the vault
 * it is importing into; returning cancels it. FragmentActivity hosts BiometricPrompt.
 */
class MainActivity : FragmentActivity() {
    private val model by lazy { ViewModelProvider(this)[VaultModel::class.java] }
    private val covered = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private val pendingLock = Runnable { model.lock() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            SevenTheme {
                CompositionLocalProvider(LocalCovered provides covered) {
                    SevenRoot(model.store, VaultContent) { VaultApp() }
                    PrivacyCover()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(pendingLock)
        model.external = false
    }

    override fun onResume() {
        super.onResume()
        covered.value = false
        model.foreground()
    }

    override fun onPause() {
        super.onPause()
        if (!model.external) covered.value = true
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || model.library == null) return
        if (model.external) handler.postDelayed(pendingLock, EXTERNAL_GRACE_MILLIS) else model.lock()
    }

    companion object {
        const val EXTERNAL_GRACE_MILLIS = 5 * 60 * 1000L
    }
}
