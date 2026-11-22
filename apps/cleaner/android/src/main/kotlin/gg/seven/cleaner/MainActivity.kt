package gg.seven.cleaner

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
        ViewModelProvider(this)[CleanerModel::class.java].start()
        setContent {
            SevenTheme {
                val model: CleanerModel = viewModel()
                SevenRoot(model.store, cleanerContent()) { CleanerApp(model) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ViewModelProvider(this)[CleanerModel::class.java].store.refresh()
    }
}
