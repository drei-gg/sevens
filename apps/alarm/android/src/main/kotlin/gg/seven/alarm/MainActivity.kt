package gg.seven.alarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import gg.seven.kit.SevenRoot
import gg.seven.kit.SevenTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val model: AlarmModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ClockFormat.h24 = android.text.format.DateFormat.is24HourFormat(this)
        if (savedInstanceState == null) handle(intent)
        setContent { SevenTheme { SevenRoot(model.store, AlarmContent) { AlarmApp(model) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The ringing alarm's Open: silence it and show its mission. */
    private fun handle(intent: Intent?) {
        val id = intent?.getStringExtra(Ringing.EXTRA_OPEN)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        intent.removeExtra(Ringing.EXTRA_OPEN)
        RingingService.silence(this)
        model.open(id)
    }

    override fun onResume() {
        super.onResume()
        ClockFormat.h24 = android.text.format.DateFormat.is24HourFormat(this)
        model.resume()
    }
}
