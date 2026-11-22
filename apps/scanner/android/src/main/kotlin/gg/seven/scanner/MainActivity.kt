package gg.seven.scanner

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import gg.seven.kit.SevenRoot
import gg.seven.kit.SevenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val seed = BuildConfig.DEBUG && savedInstanceState == null
        setContent {
            val model: ScannerModel = viewModel()
            if (seed)
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { Demo.fromIntent(this@MainActivity, intent) }
                    model.reload()
                }
            val appearance by model.appearance.collectAsStateWithLifecycle()
            // Settings' System / Light / Dark: SevenTheme follows the configuration's night mode,
            // so the chosen one is applied to the configuration the whole app reads.
            val system = isSystemInDarkTheme()
            val dark = when (appearance) {
                "dark" -> true
                "light" -> false
                else -> system
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            val base = LocalConfiguration.current
            val configuration =
                Configuration(base).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                SevenTheme { SevenRoot(model.store, scannerContent()) { ScannerApp(model) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[ScannerModel::class.java].store.refresh()
    }
}
