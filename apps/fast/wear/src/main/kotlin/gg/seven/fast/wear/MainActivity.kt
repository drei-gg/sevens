package gg.seven.fast.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = watchModel
        val link = DataLayerLink(this)
        setContent {
            // The ongoing activity needs notification permission (Wear OS 4+); ask once a fast runs.
            val notifications =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) Surfaces.ongoing(this, model.snapshot.value)
                }
            val snapshot by model.snapshot.collectAsState()
            val running = snapshot?.start != null
            LaunchedEffect(running) {
                if (
                    running &&
                        Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                )
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            WatchHome(model)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Catch up with a snapshot delivered while closed, then ask the phone for a fresh one.
                runCatching { link.stored() }.getOrDefault(emptyList()).forEach(model::receive)
                model.refresh()
                val stop = link.watchReachability(this@MainActivity) { launch { if (model.updateReachability()) model.refresh() } }
                try {
                    awaitCancellation()
                } finally {
                    stop()
                }
            }
        }
    }
}
