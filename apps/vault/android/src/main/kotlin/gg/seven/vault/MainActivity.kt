package gg.seven.vault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.seven.kit.SevenRoot
import gg.seven.kit.SevenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SevenTheme {
                val model: VaultModel = viewModel()
                SevenRoot(model.store, VaultContent) { VaultApp() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[VaultModel::class.java].store.refresh()
    }
}
