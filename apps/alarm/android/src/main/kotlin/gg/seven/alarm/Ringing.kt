package gg.seven.alarm

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.alarm.core.AlarmTone
import gg.seven.kit.SevenTheme
import gg.seven.kit.Space
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What is ringing right now, for the ringing screen and the driver's `stop`. */
data class RingingNow(val id: UUID, val name: String, val since: Instant)

object Ringing {
    const val ALARM_CHANNEL = "alarms"
    const val REMINDER_CHANNEL = "quiet-reminders"
    const val RINGING_NOTIFICATION = 1
    const val EXTRA_OPEN = "gg.seven.alarm.OPEN"

    /** How long an alarm rings before it goes quiet on its own (Android Clock's default). */
    const val TIMEOUT_MILLIS = 10 * 60_000L

    val current = MutableStateFlow<RingingNow?>(null)

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The alarm while it rings. The app plays its tone at alarm volume."
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL, "Quiet reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Loops delivered as reminders instead of alarms."
            }
        )
    }

    /** Opens the alarm's mission in the app (the ringing alarm's Open). */
    fun openIntent(context: Context, id: UUID) =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, id.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** The tones: the system's alarm sound or one of the three bundled with the app (shared with iOS). */
object Tones {
    fun asset(tone: AlarmTone): String? =
        when (tone) {
            AlarmTone.SYSTEM -> null
            AlarmTone.CHIME -> "alarm-chime.wav"
            AlarmTone.PULSE -> "alarm-pulse.wav"
            AlarmTone.BRIGHT -> "alarm-bright.wav"
        }

    /**
     * A player for [tone]: at alarm volume for a ringing alarm, at media volume for a preview (iOS:
     * "Preview uses media volume"). The system sound falls back to the chime when the phone has
     * none (or can't read it before the first unlock).
     */
    fun player(context: Context, tone: AlarmTone, ringing: Boolean): MediaPlayer {
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (ringing) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        val system = if (tone == AlarmTone.SYSTEM) RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) else null
        val loaded =
            system != null && runCatching { player.setDataSource(context, system) }.isSuccess
        if (!loaded) context.assets.openFd(asset(tone) ?: "alarm-chime.wav").use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        player.isLooping = ringing
        player.prepare()
        return player
    }
}

/**
 * Rings an alarm: its tone at alarm volume, looping, with vibration, under a high-priority
 * notification whose full-screen intent shows [RingingActivity] over the lock screen. Stop and
 * Open are on the notification and the screen. Goes quiet after [Ringing.TIMEOUT_MILLIS].
 */
class RingingService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SILENCE) {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_ID)?.let(UUID::fromString)
        if (id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val now = RingingNow(id, name, Instant.now())
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, Ringing.RINGING_NOTIFICATION, notification(now), type)
        Ringing.current.value = now
        release()
        val tone = intent.getStringExtra(EXTRA_TONE)?.let { runCatching { AlarmTone.valueOf(it) }.getOrNull() } ?: AlarmTone.SYSTEM
        val audio = getSystemService(AudioManager::class.java)
        focus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                .build()
                .also { audio.requestAudioFocus(it) }
        player = runCatching { Tones.player(this, tone, ringing = true).also { it.start() } }.getOrNull()
        vibrate()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, Ringing.TIMEOUT_MILLIS)
        return START_NOT_STICKY
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        if (Build.VERSION.SDK_INT >= 33) vibrator().vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else
            @Suppress("DEPRECATION")
            vibrator().vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    private fun notification(now: RingingNow): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val screen =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                immutable,
            )
        val stop = PendingIntent.getBroadcast(this, 0, Intent(this, StopReceiver::class.java).setData(NativeAlarmDriver.fireIntent(this, now.id).data), immutable)
        val open = PendingIntent.getActivity(this, 1, Ringing.openIntent(this, now.id), immutable)
        return NotificationCompat.Builder(this, Ringing.ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(now.name)
            .setContentText(ClockFormat.time(now.since))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Not silent: a silent notification suppresses its own full-screen intent. The channel
            // has no sound or vibration; the service plays both.
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(0, "Stop", stop)
            .addAction(0, "Open", open)
            .build()
    }

    private fun release() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        vibrator().cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        release()
        Ringing.current.value = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TONE = "tone"
        private const val ACTION_SILENCE = "gg.seven.alarm.SILENCE"

        fun ring(context: Context, id: UUID, name: String, tone: AlarmTone) {
            context.startForegroundService(
                Intent(context, RingingService::class.java)
                    .putExtra(EXTRA_ID, id.toString())
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_TONE, tone.name)
            )
        }

        fun silence(context: Context) {
            if (Ringing.current.value != null) context.stopService(Intent(context, RingingService::class.java))
        }
    }
}

/**
 * The ringing alarm over the lock screen (iOS: the system alarm with Stop and Open). Stop records a
 * dismissal; Open asks to unlock, then opens the mission in the app.
 */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            SevenTheme {
                val now by Ringing.current.collectAsStateWithLifecycle()
                LaunchedEffect(now) { if (now == null) finish() }
                now?.let { ringing ->
                    RingingScreen(
                        ringing,
                        onStop = {
                            RingingService.silence(this)
                            val app = application as AlarmApplication
                            app.scope.launch { runCatching { app.engine.dismissNative(ringing.id) } }
                            finish()
                        },
                        onOpen = {
                            RingingService.silence(this)
                            open(ringing.id)
                        },
                    )
                }
            }
        }
    }

    private fun open(id: UUID) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            startActivity(Ringing.openIntent(this, id))
            finish()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    startActivity(Ringing.openIntent(this@RingingActivity, id))
                    finish()
                }

                override fun onDismissCancelled() = finish()

                override fun onDismissError() = finish()
            },
        )
    }
}

@Composable
private fun RingingScreen(now: RingingNow, onStop: () -> Unit, onOpen: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = Space.l, vertical = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text("Alarm", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ClockText(ClockFormat.parts(now.since), Modifier.padding(vertical = Space.s), role = ClockRole.Display)
            Text(
                now.name,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1.4f))
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ringing-open")) { Text("Open") }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ringing-stop")) { Text("Stop") }
            }
            Text(
                "Open starts the alarm’s mission. Stop records a dismissal.",
                Modifier.padding(top = Space.m),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
