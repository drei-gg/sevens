@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import gg.seven.alarm.core.*
import gg.seven.kit.SevenColors
import gg.seven.kit.Space
import gg.seven.kit.numeric
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * The mission that wakes you (iOS MissionScreen): the routine and your reason big, the task in the
 * middle, one filled button to finish it, snooze and emergency stop quieter beneath. With no [row]
 * it is a preview: nothing rings and nothing is recorded.
 */
@Composable
fun MissionScreen(model: AlarmModel, routine: Routine, row: Registration?, onClose: () -> Unit) {
    val engine = model.engine
    var feedback by remember { mutableStateOf("") }
    var emergency by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    val view = LocalView.current
    fun finish() {
        if (row == null) {
            complete = true
            Haptics.confirm(view)
        } else
            model.run {
                if (finish(row, EventKind.COMPLETED)) Haptics.confirm(view)
                else {
                    feedback = error.value ?: "Couldn’t complete."
                    error.value = null
                }
            }
    }
    Dialog(
        onDismissRequest = { if (row == null) onClose() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnBackPress = row == null,
                dismissOnClickOutside = false,
            ),
    ) {
        if (row != null) BackHandler {}
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(if (row == null) "Try mission" else "Wake up") },
                    navigationIcon = {
                        if (row == null)
                            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding)
                    .consumeWindowInsets(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Space.l),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Column(
                    Modifier.widthIn(max = 520.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Icon(
                        if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.WbSunny,
                        null,
                        Modifier.size(56.dp),
                        tint = SevenColors.time,
                    )
                    Text(
                        if (complete) "Done." else routine.name,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (!complete)
                        routine.reason
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                    if (row == null)
                        Text(
                            "Mission preview. No alarm will ring.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (!complete) {
                    Box(
                        Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Mission(routine, onFeedback = { feedback = it }, onComplete = ::finish)
                    }
                    if (feedback.isNotEmpty())
                        Text(
                            feedback,
                            Modifier.testTag("mission-feedback").semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    Column(
                        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.ms),
                    ) {
                        if (row != null && row.snoozes < routine.snoozeLimit)
                            FilledTonalButton(
                                onClick = {
                                    model.run {
                                        if (!finish(row, EventKind.SNOOZED)) {
                                            feedback = error.value ?: "Couldn’t snooze."
                                            error.value = null
                                        }
                                    }
                                },
                                modifier =
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("snooze"),
                            ) {
                                Icon(
                                    Icons.Outlined.Snooze,
                                    null,
                                    Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text("Snooze ${ClockFormat.minutes(routine.snoozeMinutes)}")
                            }
                        if (row != null)
                            TextButton(
                                onClick = { emergency = true },
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                modifier = Modifier.testTag("emergency-stop"),
                            ) {
                                Text("Emergency stop")
                            }
                    }
                } else PrimaryButton("Close", onClick = onClose)
            }
        }
        if (emergency)
            AlertDialog(
                onDismissRequest = { emergency = false },
                title = { Text("Stop without completing the mission?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            emergency = false
                            // The app's snackbar sits under this dialog, so a failure shows here.
                            if (row != null)
                                model.run {
                                    if (!finish(row, EventKind.EMERGENCY_STOP)) {
                                        feedback = error.value ?: "Couldn’t stop the alarm."
                                        error.value = null
                                    }
                                }
                        },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        Text("Stop alarm")
                    }
                },
                dismissButton = { TextButton(onClick = { emergency = false }) { Text("Cancel") } },
            )
    }
    // A preview whose routine was opened from the editor closes with it, so the model stays tidy.
    DisposableEffect(Unit) { onDispose { if (row == null) engine.previewMission.value = null } }
}

@Composable
private fun Mission(routine: Routine, onFeedback: (String) -> Unit, onComplete: () -> Unit) {
    when (routine.mission) {
        MissionKind.NONE -> PrimaryButton("I’m up", Modifier.testTag("im-up"), onClick = onComplete)
        MissionKind.ARITHMETIC -> ArithmeticMission(routine.mathCount, onFeedback, onComplete)
        MissionKind.TYPING -> TypingMission(routine.phrase, onFeedback, onComplete)
        MissionKind.CODE -> CodeMission(routine.code, onFeedback, onComplete)
        MissionKind.MEMORY -> MemoryMission(routine.difficulty, onComplete)
        MissionKind.SHAKE -> MovementMission(MissionKind.SHAKE, routine.difficulty * 10, onComplete)
        MissionKind.WALK -> MovementMission(MissionKind.WALK, routine.difficulty * 20, onComplete)
        MissionKind.NUMBER_ORDER -> NumberOrderMission(routine.difficulty, onComplete)
        MissionKind.COLOR_WORD -> ColorWordMission(routine.difficulty, onComplete)
    }
}

@Composable
private fun ArithmeticMission(count: Int, onFeedback: (String) -> Unit, onComplete: () -> Unit) {
    var challenge by remember { mutableStateOf(newArithmeticChallenge()) }
    var answer by remember { mutableStateOf("") }
    var solved by remember { mutableIntStateOf(0) }
    fun check() {
        if (arithmeticAccepts(challenge, answer)) {
            solved += 1
            answer = ""
            onFeedback("")
            if (solved >= count) onComplete() else challenge = newArithmeticChallenge()
        } else onFeedback("Try that one again.")
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            "Question ${solved + 1} of $count",
            style = MaterialTheme.typography.labelLarge.numeric(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedContent(challenge, label = "sum") { c ->
            Text(
                "${c.left} + ${c.right}",
                Modifier.semantics { contentDescription = "${c.left} plus ${c.right}" },
                style =
                    MaterialTheme.typography.displayLarge
                        .numeric()
                        .copy(fontWeight = FontWeight.Bold),
            )
        }
        OutlinedTextField(
            answer,
            { next -> answer = next.filter { it.isDigit() || it == '-' }.take(6) },
            Modifier.widthIn(max = 240.dp).testTag("mission-answer"),
            label = { Text("Answer") },
            singleLine = true,
            textStyle =
                MaterialTheme.typography.titleLarge.numeric().copy(textAlign = TextAlign.Center),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { check() }),
        )
        PrimaryButton("Check answer", Modifier.testTag("check-answer"), onClick = ::check)
    }
}

@Composable
private fun TypingMission(phrase: String, onFeedback: (String) -> Unit, onComplete: () -> Unit) {
    var answer by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(phrase, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        OutlinedTextField(
            answer,
            { answer = it },
            Modifier.fillMaxWidth().testTag("mission-phrase"),
            label = { Text("Type the phrase") },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
        )
        PrimaryButton("Check phrase") {
            if (missionCheckPhrase(answer, phrase)) onComplete()
            else onFeedback("The phrase doesn’t match yet.")
        }
    }
}

@Composable
private fun CodeMission(expected: String, onFeedback: (String) -> Unit, onComplete: () -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(
            Icons.Outlined.QrCodeScanner,
            null,
            Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Go to your saved code and scan it.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        PrimaryButton("Scan code", icon = Icons.Outlined.QrCodeScanner) { scanning = true }
    }
    if (scanning)
        CodeScanner(onClose = { scanning = false }) { value ->
            scanning = false
            // Compared as data, never opened.
            if (missionCheckCode(value, expected)) onComplete()
            else onFeedback("That’s a different code.")
        }
}

/**
 * A square tile for the memory and number missions: card on paper, ink while lit, muted once done.
 */
@Composable
private fun MissionTile(
    text: String,
    modifier: Modifier = Modifier,
    lit: Boolean = false,
    done: Boolean = false,
    enabled: Boolean = true,
    label: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = if (lit) colors.primary else colors.surfaceContainer,
                contentColor = if (lit) colors.onPrimary else colors.onSurface,
                disabledContainerColor =
                    when {
                        lit -> colors.primary
                        done -> colors.secondaryContainer
                        else -> colors.surfaceContainer
                    },
                disabledContentColor =
                    when {
                        lit -> colors.onPrimary
                        done -> colors.onSecondaryContainer
                        else -> colors.onSurfaceVariant
                    },
            ),
        modifier =
            modifier.heightIn(min = 68.dp).semantics {
                contentDescription = label
                stateDescription = if (lit) "Highlighted" else if (done) "Completed" else "Ready"
            },
        contentPadding = PaddingValues(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.headlineMedium.numeric())
    }
}

@Composable
private fun TileGrid(count: Int, tile: @Composable RowScope.(Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        (0 until count).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { tile(it) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MemoryMission(level: Int, onComplete: () -> Unit) {
    var sequence by remember {
        mutableStateOf(SequenceChallenge(List(level + 2) { (0 until 9).random() }))
    }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    var showing by remember { mutableStateOf(true) }
    var completed by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Watch the tiles, then repeat the order.") }
    LaunchedEffect(replay) {
        showing = true
        // The core object holds the progress; a replay starts a fresh one on the same tiles.
        if (replay > 0) sequence = SequenceChallenge(sequence.sequence())
        delay(600)
        for (tile in sequence.sequence()) {
            highlighted = tile
            message = "Tile ${tile + 1}"
            delay(850)
            highlighted = null
            delay(250)
        }
        showing = false
        message = "Your turn. Repeat the order."
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            message,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        TileGrid(9) { tile ->
            MissionTile(
                "${tile + 1}",
                Modifier.weight(1f).testTag("memory-tile-$tile"),
                lit = highlighted == tile,
                enabled = !showing && !completed,
                label = "Tile ${tile + 1}",
            ) {
                if (sequence.tap(tile)) {
                    if (sequence.complete()) {
                        completed = true
                        onComplete()
                    } else message = "${sequence.progress()} of ${sequence.sequence().size}"
                } else {
                    message = "Try that sequence again."
                    replay += 1
                }
            }
        }
        TextButton(onClick = { replay += 1 }, enabled = !showing && !completed) {
            Icon(Icons.Outlined.Replay, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Show sequence again")
        }
    }
}

@Composable
private fun NumberOrderMission(level: Int, onComplete: () -> Unit) {
    val tiles = remember { (1..(level * 3 + 3)).shuffled() }
    var next by remember { mutableIntStateOf(1) }
    var message by remember { mutableStateOf("Tap the numbers from smallest to largest.") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            message,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        TileGrid(tiles.size) { index ->
            val number = tiles[index]
            val done = number < next
            MissionTile(
                if (done) "✓" else "$number",
                Modifier.weight(1f).testTag("order-$number"),
                done = done,
                enabled = !done,
                label = if (done) "Completed $number" else "Number $number",
            ) {
                if (number == next) {
                    next += 1
                    message = "Next: $next"
                    if (next > tiles.size) onComplete()
                } else message = "Find $next next."
            }
        }
    }
}

/**
 * The game's own inks, each clearing 4.5:1 on paper and card in both themes (the iOS values). They
 * are the content of the game, not accents or status.
 */
@Composable
private fun ColorWord.ink(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        ColorWord.RED -> if (dark) Color(0xFFFF7468) else Color(0xFFB0201A)
        ColorWord.BLUE -> if (dark) Color(0xFF7EADFF) else Color(0xFF1F57B8)
        ColorWord.GREEN -> if (dark) Color(0xFF6CCB86) else Color(0xFF1D6B33)
        ColorWord.ORANGE -> if (dark) Color(0xFFF2A33A) else Color(0xFF8A4A00)
    }
}

private val ColorWord.label: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun ColorWordMission(level: Int, onComplete: () -> Unit) {
    fun draw(): Pair<ColorWord, ColorWord> {
        val ink = ColorWord.entries.random()
        return ColorWord.entries.filter { it != ink }.random() to ink
    }
    var round by remember { mutableStateOf(draw()) }
    var solved by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Choose the ink color, not the word.") }
    val target = level + 2
    val (word, ink) = round
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            "$solved of $target",
            style = MaterialTheme.typography.labelLarge.numeric(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            message,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            word.label.uppercase(),
            Modifier.semantics {
                    contentDescription = "The word ${word.label}, printed in ${ink.label}"
                }
                .testTag("color-word"),
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = ink.ink(),
        )
        ColorWord.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                pair.forEach { answer ->
                    OutlinedButton(
                        onClick = {
                            if (colorWordAccepts(answer, ink)) {
                                solved += 1
                                if (solved >= target) onComplete()
                                else {
                                    round = draw()
                                    message = "Choose the ink color."
                                }
                            } else message = "Look at the ink, not the word."
                        },
                        enabled = solved < target,
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("color-${answer.name}"),
                    ) {
                        Text(answer.label)
                    }
                }
            }
        }
        Text(
            "Color vision makes this one difficult? Choose a different mission in your alarm settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Shakes or steps, counted only after the person starts and stopped when they leave the mission or
 * the app goes to the background (iOS MovementTracker). Shakes come from the linear accelerometer
 * through the core's counter; steps from the step detector.
 */
private class MovementTracker(context: Context, private val mode: MissionKind) :
    SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var counter = ShakeCounter()
    private var baseline: Float? = null
    var count by mutableIntStateOf(0)
    var message by mutableStateOf("")
    var running by mutableStateOf(false)

    fun start() {
        stop()
        counter = ShakeCounter()
        baseline = null
        count = 0
        message = ""
        val sensor =
            if (mode == MissionKind.WALK)
                sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                    ?: sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            else sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            message =
                if (mode == MissionKind.WALK) "Step counting is unavailable on this device."
                else "Motion sensing is unavailable on this device."
            return
        }
        running =
            sensors.registerListener(
                this,
                sensor,
                if (mode == MissionKind.WALK) SensorManager.SENSOR_DELAY_NORMAL
                else SensorManager.SENSOR_DELAY_GAME,
            )
        if (!running) message = "Motion sensing is unavailable on this device."
    }

    fun stop() {
        running = false
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> count += 1
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values[0]
                val start = baseline ?: total.also { baseline = it }
                count = (total - start).toInt()
            }
            else -> {
                // The core counts in g, as iOS's userAcceleration is.
                val (x, y, z) = event.values
                counter.sample(
                    sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH,
                    event.timestamp / 1e9,
                )
                count = counter.count().toInt()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@Composable
private fun MovementMission(mode: MissionKind, target: Int, onComplete: () -> Unit) {
    val context = LocalContext.current
    val tracker = remember { MovementTracker(context, mode) }
    var finished by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) tracker.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            tracker.stop()
        }
    }
    LaunchedEffect(tracker.count) {
        if (tracker.count >= target && !finished) {
            finished = true
            tracker.stop()
            onComplete()
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tracker.start()
            else tracker.message = "Allow physical activity in Settings to count steps."
        }
    val shown = minOf(tracker.count, target)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(
            if (mode == MissionKind.WALK) Icons.AutoMirrored.Outlined.DirectionsWalk
            else Icons.Outlined.Vibration,
            null,
            Modifier.size(56.dp),
            tint = SevenColors.time,
        )
        Row(
            Modifier.clearAndSetSemantics {
                contentDescription =
                    if (mode == MissionKind.WALK) "$shown of $target steps"
                    else "$shown of $target shakes"
            },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            AnimatedContent(shown, label = "count") {
                Text(
                    "$it",
                    style =
                        MaterialTheme.typography.displayLarge
                            .numeric()
                            .copy(fontWeight = FontWeight.Bold),
                )
            }
            Text("/ $target", style = MaterialTheme.typography.titleLarge.numeric())
        }
        LinearProgressIndicator(
            progress = { shown / target.toFloat() },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            color = SevenColors.time,
        )
        Text(
            if (mode == MissionKind.WALK)
                "Take a short walk with your phone. Only steps after you start count."
            else "Hold your phone securely and shake it back and forth.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (tracker.message.isNotEmpty())
            Text(
                tracker.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (!tracker.running && !finished)
            PrimaryButton(
                "Start challenge",
                Modifier.testTag("start-challenge"),
                icon = Icons.Outlined.PlayArrow,
            ) {
                if (
                    mode == MissionKind.WALK &&
                        Build.VERSION.SDK_INT >= 29 &&
                        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                            PackageManager.PERMISSION_GRANTED
                )
                    permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                else tracker.start()
            }
        Text(
            "Motion stays on this phone and stops when you leave this challenge.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
