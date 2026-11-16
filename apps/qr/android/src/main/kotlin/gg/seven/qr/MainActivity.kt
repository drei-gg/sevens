package gg.seven.qr

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
                val model: QrModel = viewModel()
                SevenRoot(model.store, QrContent) { QrApp() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[QrModel::class.java].store.refresh()
    }
}
