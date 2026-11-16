package gg.seven.scanner

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
                val model: ScannerModel = viewModel()
                SevenRoot(model.store, ScannerContent) { ScannerApp() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[ScannerModel::class.java].store.refresh()
    }
}
