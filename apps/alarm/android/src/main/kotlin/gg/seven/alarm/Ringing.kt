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
import androidx.compose.ui.res.pluralStringResource
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.alarm.core.AlarmTone
import gg.seven.kit.SevenTheme
import gg.seven.kit.Space
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An alarm that is ringing or waiting to ring, for the ringing screen and the driver's `stop`. */
data class RingingNow(
    val id: UUID,
    val name: String,
    val since: Instant,
    val tone: AlarmTone? = null,
    val sound: UUID? = null,
)

/**
 * The alarms that went off and are not yet stopped or opened, in the order they rang. The first one
 * plays; any that go off meanwhile wait, each with its own notification, Stop and Open, so every
 * alarm keeps its own dismissal record (iOS: AlarmKit presents each alarm). When the first is
 * stopped, opened or goes quiet, the next one rings. Plain Kotlin, so its rules are unit tested.
 */
class RingQueue {
    private val _items = MutableStateFlow<List<RingingNow>>(emptyList())
    private val _head = MutableStateFlow<RingingNow?>(null)
    val items: StateFlow<List<RingingNow>> = _items.asStateFlow()

    /** The alarm that plays now. */
    val head: StateFlow<RingingNow?> = _head.asStateFlow()

    operator fun contains(id: UUID) = _items.value.any { it.id == id }

    private fun set(items: List<RingingNow>) {
        _items.value = items
        _head.value = items.firstOrNull()
    }

    /** Adds an alarm that just went off; one already here keeps its place. True if it was added. */
    @Synchronized
    fun add(alarm: RingingNow): Boolean {
        if (alarm.id in this) return false
        set(_items.value + alarm)
        return true
    }

    /** Stops one alarm, ringing or waiting; the next one rings if it was first. */
    @Synchronized
    fun remove(id: UUID): RingingNow? {
        val removed = _items.value.firstOrNull { it.id == id } ?: return null
        set(_items.value - removed)
        return removed
    }

    @Synchronized
    fun clear() = set(emptyList())
}

/**
 * The ringing screen's Open, as a small state machine so the order is tested: on a locked phone the
 * alarm keeps ringing while the unlock prompt shows; only a successful unlock silences it and opens
 * the mission. A cancelled or failed unlock leaves it ringing, with Stop and Open still available.
 */
class OpenFlow {
    sealed interface Step {
        /** Silence this alarm, open its mission and close the ringing screen. */
        data class Launch(val id: UUID) : Step

        /** Ask to unlock; keep ringing until the answer. */
        data class Unlock(val id: UUID) : Step

        /** An unlock prompt is already showing. */
        data object Wait : Step
    }

    var unlocking: UUID? = null
        private set

    fun open(id: UUID, locked: Boolean): Step =
        when {
            unlocking != null -> Step.Wait
            locked -> {
                unlocking = id
                Step.Unlock(id)
            }
            else -> Step.Launch(id)
        }

    /** The phone unlocked: launch the alarm that asked. */
    fun unlocked(): Step = unlocking?.let { Step.Launch(it) }.also { unlocking = null } ?: Step.Wait

    /** The unlock was cancelled or failed: nothing changes, the alarm keeps ringing. */
    fun cancelled() {
        unlocking = null
    }
}

object Ringing {
    const val ALARM_CHANNEL = "alarms"
    const val REMINDER_CHANNEL = "quiet-reminders"
    const val RINGING_NOTIFICATION = 1
    const val WAITING_NOTIFICATION = 2
    const val EXTRA_OPEN = "gg.seven.alarm.OPEN"

    /** How long an alarm rings before it goes quiet on its own (Android Clock's default). */
    const val TIMEOUT_MILLIS = 10 * 60_000L

    val queue = RingQueue()

    /** The alarm that plays now, if any. */
    val current: StateFlow<RingingNow?>
        get() = queue.head

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description =
                        "The alarm while it rings. The app plays its tone at alarm volume."
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                    REMINDER_CHANNEL,
                    "Quiet reminders",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply { description = "Loops delivered as reminders instead of alarms." }
        )
    }

    /**
     * Opens the alarm's mission in the app (the ringing alarm's Open). The data makes each alarm's
     * Open its own pending intent, so two waiting alarms never share one.
     */
    fun openIntent(context: Context, id: UUID) =
        Intent(context, MainActivity::class.java)
            .setData("alarm-loops://open/$id".toUri())
            .putExtra(EXTRA_OPEN, id.toString())
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
}

/**
 * The tones: the system's alarm sound or one of the three bundled with the app (shared with iOS).
 */
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
    fun player(
        context: Context,
        tone: AlarmTone,
        ringing: Boolean,
        imported: gg.seven.alarm.core.ImportedSound? = null,
    ): MediaPlayer {
        fun prepare(source: (MediaPlayer) -> Unit): MediaPlayer {
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (ringing) AudioAttributes.USAGE_ALARM
                            else AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                source(player)
                player.isLooping = ringing
                player.prepare()
                return player
            } catch (error: Exception) {
                player.release()
                throw error
            }
        }
        if (imported != null) {
            try {
                return prepare { it.setDataSource(SoundFiles(context).file(imported).path) }
            } catch (error: Exception) {
                if (!ringing)
                    throw IllegalStateException(
                        "This imported sound cannot be played. Re-import it or use the fallback tone.",
                        error,
                    )
            }
        }
        if (tone == AlarmTone.SYSTEM) {
            val uri =
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            if (uri != null)
                runCatching { prepare { it.setDataSource(context, uri) } }
                    .getOrNull()
                    ?.let {
                        return it
                    }
        }
        return prepare { player ->
            context.assets.openFd(asset(tone) ?: "alarm-chime.wav").use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
        }
    }
}

/**
 * Rings alarms: the first in [Ringing.queue] plays its tone at alarm volume, looping, with
 * vibration, under a high-priority notification whose full-screen intent shows [RingingActivity]
 * over the lock screen. Alarms that go off meanwhile wait under their own notifications, each with
 * Stop and Open. Stop and Open are on the notifications and the screen. Each alarm goes quiet after
 * [Ringing.TIMEOUT_MILLIS] of playing, and the next one rings.
 */
class RingingService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var playing: UUID? = null
    private val waiting = mutableSetOf<UUID>()
    private var started = false
    private var lastStart = 0

    /** The playing alarm went quiet on its own: nothing is recorded, and the next one rings. */
    private val timeout = Runnable { playing?.let { Ringing.queue.remove(it) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch { Ringing.queue.items.collect { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val id = intent?.getStringExtra(EXTRA_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val alarm =
            id?.let {
                RingingNow(
                    it,
                    intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    Instant.now(),
                    intent.getStringExtra(EXTRA_TONE)?.let { name ->
                        runCatching { AlarmTone.valueOf(name) }.getOrNull()
                    },
                    intent.getStringExtra(EXTRA_SOUND)?.let { value ->
                        runCatching { UUID.fromString(value) }.getOrNull()
                    },
                )
            }
        alarm?.let(Ringing.queue::add)
        val head = Ringing.queue.head.value
        if (head == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Every start must be answered with startForeground, even for an alarm that will wait.
        foreground(head)
        started = true
        render(Ringing.queue.items.value)
        return START_NOT_STICKY
    }

    private fun foreground(head: RingingNow) {
        val type =
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            else 0
        ServiceCompat.startForeground(
            this,
            Ringing.RINGING_NOTIFICATION,
            notification(head, waiting = false),
            type,
        )
    }

    /** Plays the first alarm and keeps one notification per waiting alarm. Idempotent. */
    private fun render(items: List<RingingNow>) {
        if (!started) return
        val head = items.firstOrNull()
        if (head == null) {
            stopSelf(lastStart)
            return
        }
        if (head.id != playing) {
            playing = head.id
            foreground(head)
            play(head)
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, Ringing.TIMEOUT_MILLIS)
        }
        val manager = NotificationManagerCompat.from(this)
        val wanted = items.drop(1)
        // Without the notification permission a waiting alarm still rings in turn; it just has no
        // notification of its own until then.
        val allowed =
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        for (alarm in wanted)
            if (allowed && waiting.add(alarm.id))
                manager.notify(
                    alarm.id.toString(),
                    Ringing.WAITING_NOTIFICATION,
                    notification(alarm, waiting = true),
                )
        val ids = wanted.mapTo(HashSet()) { it.id }
        for (gone in waiting.filter { it !in ids }) {
            manager.cancel(gone.toString(), Ringing.WAITING_NOTIFICATION)
            waiting.remove(gone)
        }
    }

    private fun play(alarm: RingingNow) {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        val audio = getSystemService(AudioManager::class.java)
        if (focus == null)
            focus =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                    )
                    .build()
                    .also { audio.requestAudioFocus(it) }
        val imported =
            alarm.sound?.let { gg.seven.alarm.core.ImportedSound(it, "Imported sound") }
        player =
            runCatching {
                    val next =
                        Tones.player(
                            this,
                            alarm.tone ?: AlarmTone.SYSTEM,
                            ringing = true,
                            imported = imported,
                        )
                    try {
                        next.start()
                        next
                    } catch (error: Exception) {
                        next.release()
                        throw error
                    }
                }
                .getOrNull()
        vibrate()
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        if (Build.VERSION.SDK_INT >= 33)
            vibrator()
                .vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
        else
            @Suppress("DEPRECATION")
            vibrator()
                .vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
    }

    private fun notification(now: RingingNow, waiting: Boolean): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val screen =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RingingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                immutable,
            )
        val stop =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(this, StopReceiver::class.java)
                    .setData(NativeAlarmDriver.fireIntent(this, now.id).data),
                immutable,
            )
        val open = PendingIntent.getActivity(this, 1, Ringing.openIntent(this, now.id), immutable)
        val time = ClockFormat.time(now.since)
        return NotificationCompat.Builder(this, Ringing.ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(now.name)
            .setContentText(if (waiting) "$time · Rings after the current alarm" else time)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // Not silent: a silent notification suppresses its own full-screen intent. The channel
            // has no sound or vibration; the service plays both.
            .setOnlyAlertOnce(true)
            .apply { if (!waiting) setFullScreenIntent(screen, true) }
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
        scope.cancel()
        handler.removeCallbacks(timeout)
        release()
        val manager = NotificationManagerCompat.from(this)
        for (id in waiting) manager.cancel(id.toString(), Ringing.WAITING_NOTIFICATION)
        waiting.clear()
        playing = null
        Ringing.queue.clear()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TONE = "tone"
        private const val EXTRA_SOUND = "sound"

        fun ring(
            context: Context,
            id: UUID,
            name: String,
            tone: AlarmTone,
            imported: gg.seven.alarm.core.ImportedSound? = null,
        ) {
            context.startForegroundService(
                Intent(context, RingingService::class.java)
                    .putExtra(EXTRA_ID, id.toString())
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_TONE, tone.name)
                    .putExtra(EXTRA_SOUND, imported?.id?.toString())
            )
        }

        /**
         * Stops one alarm, ringing or waiting. The service rings the next one, or stops when none
         * is left.
         */
        fun silence(id: UUID) {
            Ringing.queue.remove(id)
        }
    }
}

/**
 * The ringing alarm over the lock screen (iOS: the system alarm with Stop and Open). Stop records a
 * dismissal; Open asks to unlock, then silences the alarm and opens the mission. While the unlock
 * prompt shows, the alarm keeps ringing; a cancelled unlock leaves it ringing and actionable. With
 * several alarms, the screen shows the one playing, and the next after it is stopped.
 */
class RingingActivity : ComponentActivity() {
    private val flow = OpenFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        enableEdgeToEdge()
        setContent {
            SevenTheme {
                val now by Ringing.current.collectAsStateWithLifecycle()
                val items by Ringing.queue.items.collectAsStateWithLifecycle()
                // An unlock in progress finishes the screen itself, once it has an answer.
                LaunchedEffect(now) { if (now == null && flow.unlocking == null) finish() }
                now?.let { ringing ->
                    RingingScreen(
                        ringing,
                        waiting = (items.size - 1).coerceAtLeast(0),
                        onStop = {
                            val app = application as AlarmApplication
                            RingingService.silence(ringing.id)
                            app.scope.launch {
                                runCatching { app.engine.dismissNative(ringing.id) }
                            }
                            if (Ringing.current.value == null) finish()
                        },
                        onOpen = { step(flow.open(ringing.id, locked())) },
                    )
                }
            }
        }
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun step(step: OpenFlow.Step) {
        when (step) {
            is OpenFlow.Step.Launch -> {
                RingingService.silence(step.id)
                startActivity(Ringing.openIntent(this, step.id))
                finish()
            }
            is OpenFlow.Step.Unlock ->
                getSystemService(KeyguardManager::class.java)
                    .requestDismissKeyguard(
                        this,
                        object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() = step(flow.unlocked())

                            override fun onDismissCancelled() = cancelled()

                            override fun onDismissError() = cancelled()
                        },
                    )
            OpenFlow.Step.Wait -> Unit
        }
    }

    /** Still ringing, still actionable; close only if the alarm went quiet meanwhile. */
    private fun cancelled() {
        flow.cancelled()
        if (Ringing.current.value == null) finish()
    }
}

@Composable
private fun RingingScreen(
    now: RingingNow,
    waiting: Int,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .padding(horizontal = Space.l, vertical = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "Alarm",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ClockText(
                ClockFormat.parts(now.since),
                Modifier.padding(vertical = Space.s),
                role = ClockRole.Display,
            )
            Text(
                now.name,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            if (waiting > 0)
                Text(
                    pluralStringResource(R.plurals.alarms_waiting, waiting, waiting),
                    Modifier.padding(top = Space.s),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            Spacer(Modifier.weight(1.4f))
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.ms),
            ) {
                Button(
                    onClick = onOpen,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ringing-open"),
                ) {
                    Text("Open")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ringing-stop"),
                ) {
                    Text("Stop")
                }
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
