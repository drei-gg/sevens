package gg.seven.fast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.seven.kit.SevenRoot
import gg.seven.kit.SevenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: FastModel = viewModel()
            SevenTheme { SevenRoot(model.store, FastContent) { FastApp(model) } }
        }
    }

    override fun onResume() {
        super.onResume()
        androidx.lifecycle.ViewModelProvider(this)[FastModel::class.java].refresh()
    }
}
