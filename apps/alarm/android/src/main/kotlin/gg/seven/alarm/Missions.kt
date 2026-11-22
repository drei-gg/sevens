@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    val res = LocalResources.current
    var feedback by remember { mutableStateOf("") }
    var emergency by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    val view = LocalView.current
    // Steps already done in this ringing's mission, saved as they happen so reopening it (after a
    // call, a mis-swipe or the system ending the app, or a rotation) resumes here. A preview keeps
    // none. Read from the archive each time the screen is built, so it is never stale.
    var progress by
        remember(row?.id) {
            mutableStateOf(row?.let { engine.progress() ?: missionProgressStart(it) })
        }
    fun record(change: (MissionProgress) -> MissionProgress) {
        val next = progress?.let(change) ?: return
        progress = next
        model.run { saveProgress(next) }
    }
    fun finish() {
        if (row == null) {
            complete = true
            Haptics.confirm(view)
        } else
            model.run {
                if (finish(row, EventKind.COMPLETED)) Haptics.confirm(view)
                else {
                    feedback = error.value ?: res.getString(R.string.mission_complete_failed)
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
                    title = {
                        Text(
                            stringResource(
                                if (row == null) R.string.mission_title_preview
                                else R.string.mission_title_ringing
                            )
                        )
                    },
                    navigationIcon = {
                        if (row == null)
                            IconButton(onClick = onClose) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                            }
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
                        if (complete) stringResource(R.string.mission_done) else routine.name,
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
                            stringResource(R.string.mission_preview_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (!complete) {
                    Box(
                        Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Mission(
                            routine,
                            progress,
                            ::record,
                            onFeedback = { feedback = it },
                            onComplete = ::finish,
                        )
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
                                            feedback =
                                                error.value
                                                    ?: res.getString(R.string.mission_snooze_failed)
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
                                Text(
                                    stringResource(
                                        R.string.mission_snooze,
                                        ClockFormat.minutes(routine.snoozeMinutes),
                                    )
                                )
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
                                Text(stringResource(R.string.mission_emergency_stop))
                            }
                    }
                } else PrimaryButton(stringResource(R.string.common_close), onClick = onClose)
            }
        }
        if (emergency)
            AlertDialog(
                onDismissRequest = { emergency = false },
                title = { Text(stringResource(R.string.mission_emergency_title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            emergency = false
                            // The app's snackbar sits under this dialog, so a failure shows here.
                            if (row != null)
                                model.run {
                                    if (!finish(row, EventKind.EMERGENCY_STOP)) {
                                        feedback =
                                            error.value
                                                ?: res.getString(R.string.mission_stop_failed)
                                        error.value = null
                                    }
                                }
                        },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        Text(stringResource(R.string.mission_stop_alarm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { emergency = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
    }
    // A preview whose routine was opened from the editor closes with it, so the model stays tidy.
    DisposableEffect(Unit) { onDispose { if (row == null) engine.previewMission.value = null } }
}

/** Saves a step of the ringing's mission (MissionScreen's record); a preview ignores it. */
private typealias Record = ((MissionProgress) -> MissionProgress) -> Unit

@Composable
private fun Mission(
    routine: Routine,
    saved: MissionProgress?,
    record: Record,
    onFeedback: (String) -> Unit,
    onComplete: () -> Unit,
) {
    when (routine.mission) {
        MissionKind.NONE ->
            PrimaryButton(
                stringResource(R.string.mission_im_up),
                Modifier.testTag("im-up"),
                onClick = onComplete,
            )
        MissionKind.ARITHMETIC ->
            ArithmeticMission(routine.mathCount, saved, record, onFeedback, onComplete)
        MissionKind.TYPING -> TypingMission(routine.phrase, onFeedback, onComplete)
        MissionKind.CODE -> CodeMission(routine.code, saved, record, onFeedback, onComplete)
        MissionKind.MEMORY -> MemoryMission(routine.difficulty, saved, record, onComplete)
        MissionKind.SHAKE ->
            MovementMission(MissionKind.SHAKE, routine.difficulty * 10, saved, record, onComplete)
        MissionKind.WALK ->
            MovementMission(MissionKind.WALK, routine.difficulty * 20, saved, record, onComplete)
        MissionKind.NUMBER_ORDER ->
            NumberOrderMission(routine.difficulty, saved, record, onComplete)
        MissionKind.COLOR_WORD -> ColorWordMission(routine.difficulty, saved, record, onComplete)
    }
}

/** Steps saved before the mission was left, short of the last: reopening never completes it. */
private fun MissionProgress?.resumed(target: Int) =
    (this?.solved ?: 0).coerceIn(0, maxOf(0, target - 1))

@Composable
private fun ArithmeticMission(
    count: Int,
    saved: MissionProgress?,
    record: Record,
    onFeedback: (String) -> Unit,
    onComplete: () -> Unit,
) {
    val res = LocalResources.current
    var challenge by remember { mutableStateOf(newArithmeticChallenge()) }
    var answer by remember { mutableStateOf("") }
    var solved by remember { mutableIntStateOf(saved.resumed(count)) }
    fun check() {
        if (arithmeticAccepts(challenge, answer)) {
            solved += 1
            answer = ""
            onFeedback("")
            if (solved >= count) onComplete()
            else {
                val done = solved
                record { it.copy(solved = done) }
                challenge = newArithmeticChallenge()
            }
        } else onFeedback(res.getString(R.string.mission_try_again))
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            stringResource(R.string.mission_question, solved + 1, count),
            style = MaterialTheme.typography.labelLarge.numeric(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedContent(challenge, label = "sum") { c ->
            Text(
                stringResource(R.string.mission_sum, c.left, c.right),
                Modifier.semantics {
                    contentDescription = res.getString(R.string.mission_sum_spoken, c.left, c.right)
                },
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
            label = { Text(stringResource(R.string.mission_answer)) },
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
        PrimaryButton(
            stringResource(R.string.mission_check_answer),
            Modifier.testTag("check-answer"),
            onClick = ::check,
        )
    }
}

@Composable
private fun TypingMission(phrase: String, onFeedback: (String) -> Unit, onComplete: () -> Unit) {
    val res = LocalResources.current
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
            label = { Text(stringResource(R.string.mission_type_phrase)) },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
        )
        PrimaryButton(stringResource(R.string.mission_check_phrase)) {
            if (missionCheckPhrase(answer, phrase)) onComplete()
            else onFeedback(res.getString(R.string.mission_phrase_mismatch))
        }
    }
}

@Composable
private fun CodeMission(
    expected: String,
    saved: MissionProgress?,
    record: Record,
    onFeedback: (String) -> Unit,
    onComplete: () -> Unit,
) {
    val res = LocalResources.current
    var scanning by remember { mutableStateOf(false) }
    // Scanned before the mission was left: only recording the completion is left to do.
    val matched = saved?.matched == true
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Icon(
            if (matched) Icons.Outlined.CheckCircle else Icons.Outlined.QrCodeScanner,
            null,
            Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                if (matched) R.string.mission_code_scanned else R.string.mission_code_prompt
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (matched)
            PrimaryButton(
                stringResource(R.string.mission_finish),
                Modifier.testTag("finish-mission"),
                onClick = onComplete,
            )
        else
            PrimaryButton(
                stringResource(R.string.mission_scan_code),
                icon = Icons.Outlined.QrCodeScanner,
            ) {
                scanning = true
            }
    }
    if (scanning)
        CodeScanner(onClose = { scanning = false }) { value ->
            scanning = false
            // Compared as data, never opened.
            if (missionCheckCode(value, expected)) {
                record { it.copy(matched = true) }
                onComplete()
            } else onFeedback(res.getString(R.string.mission_code_mismatch))
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
    val state =
        stringResource(
            when {
                lit -> R.string.mission_tile_highlighted
                done -> R.string.mission_tile_completed
                else -> R.string.mission_tile_ready
            }
        )
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
                stateDescription = state
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
private fun MemoryMission(
    level: Int,
    saved: MissionProgress?,
    record: Record,
    onComplete: () -> Unit,
) {
    val res = LocalResources.current
    // A reopened mission replays the tiles it was showing.
    val kept =
        saved?.tiles.orEmpty().takeIf { t -> t.size == level + 2 && t.all { it in 0 until 9 } }
    var sequence by remember {
        mutableStateOf(SequenceChallenge(kept ?: List(level + 2) { (0 until 9).random() }))
    }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    var showing by remember { mutableStateOf(true) }
    var completed by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf(res.getString(R.string.memory_watch)) }
    LaunchedEffect(Unit) {
        val tiles = sequence.sequence()
        if (kept != tiles) record { it.copy(tiles = tiles) }
    }
    LaunchedEffect(replay) {
        showing = true
        // The core object holds the progress; a replay starts a fresh one on the same tiles.
        if (replay > 0) sequence = SequenceChallenge(sequence.sequence())
        delay(600)
        for (tile in sequence.sequence()) {
            highlighted = tile
            message = res.getString(R.string.memory_tile, tile + 1)
            delay(850)
            highlighted = null
            delay(250)
        }
        showing = false
        message = res.getString(R.string.memory_your_turn)
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
                label = stringResource(R.string.memory_tile, tile + 1),
            ) {
                if (sequence.tap(tile)) {
                    if (sequence.complete()) {
                        completed = true
                        onComplete()
                    } else
                        message =
                            res.getString(
                                R.string.mission_count_of,
                                sequence.progress().toInt(),
                                sequence.sequence().size,
                            )
                } else {
                    message = res.getString(R.string.memory_try_again)
                    replay += 1
                }
            }
        }
        TextButton(onClick = { replay += 1 }, enabled = !showing && !completed) {
            Icon(Icons.Outlined.Replay, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.memory_show_again))
        }
    }
}

@Composable
private fun NumberOrderMission(
    level: Int,
    saved: MissionProgress?,
    record: Record,
    onComplete: () -> Unit,
) {
    val res = LocalResources.current
    val count = level * 3 + 3
    // A reopened mission keeps its board and the numbers already tapped.
    val kept = saved?.tiles?.takeIf { it.size == count && it.toSet() == (1..count).toSet() }
    val tiles = remember { kept ?: (1..count).shuffled() }
    var next by remember { mutableIntStateOf(if (kept != null) saved.resumed(count) + 1 else 1) }
    var message by remember { mutableStateOf(res.getString(R.string.order_prompt)) }
    LaunchedEffect(Unit) { if (kept != tiles) record { it.copy(tiles = tiles, solved = 0) } }
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
                label =
                    stringResource(
                        if (done) R.string.order_completed else R.string.order_number,
                        number,
                    ),
            ) {
                if (number == next) {
                    next += 1
                    message = res.getString(R.string.order_next, next)
                    if (next > tiles.size) onComplete()
                    else {
                        val tapped = next - 1
                        record { it.copy(solved = tapped) }
                    }
                } else message = res.getString(R.string.order_find, next)
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

private val ColorWord.label: Int
    get() =
        when (this) {
            ColorWord.RED -> R.string.color_red
            ColorWord.BLUE -> R.string.color_blue
            ColorWord.GREEN -> R.string.color_green
            ColorWord.ORANGE -> R.string.color_orange
        }

@Composable
private fun ColorWordMission(
    level: Int,
    saved: MissionProgress?,
    record: Record,
    onComplete: () -> Unit,
) {
    val res = LocalResources.current
    fun draw(): Pair<ColorWord, ColorWord> {
        val ink = ColorWord.entries.random()
        return ColorWord.entries.filter { it != ink }.random() to ink
    }
    val target = level + 2
    var round by remember { mutableStateOf(draw()) }
    var solved by remember { mutableIntStateOf(saved.resumed(target)) }
    var message by remember { mutableStateOf(res.getString(R.string.color_prompt)) }
    val (word, ink) = round
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            stringResource(R.string.mission_count_of, solved, target),
            style = MaterialTheme.typography.labelLarge.numeric(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            message,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        val wordLabel = stringResource(word.label)
        val spoken = stringResource(R.string.color_spoken, wordLabel, stringResource(ink.label))
        Text(
            wordLabel.uppercase(LocalLocale.current.platformLocale),
            Modifier.semantics { contentDescription = spoken }.testTag("color-word"),
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
                                    val done = solved
                                    record { it.copy(solved = done) }
                                    round = draw()
                                    message = res.getString(R.string.color_next)
                                }
                            } else message = res.getString(R.string.color_wrong)
                        },
                        enabled = solved < target,
                        modifier =
                            Modifier.weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("color-${answer.name}"),
                    ) {
                        Text(stringResource(answer.label))
                    }
                }
            }
        }
        Text(
            stringResource(R.string.color_vision_note),
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
private class MovementTracker(context: Context, private val mode: MissionKind, start: Int) :
    SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var counter = ShakeCounter()
    private var baseline: Float? = null
    /** Counted before this start: an earlier start, or before the mission was left. */
    private var offset = start
    var count by mutableIntStateOf(start)
    @get:androidx.annotation.StringRes var message by mutableStateOf<Int?>(null)
    var running by mutableStateOf(false)

    fun start() {
        stop()
        counter = ShakeCounter()
        baseline = null
        offset = count
        message = null
        val sensor =
            if (mode == MissionKind.WALK)
                sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                    ?: sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            else sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            message =
                if (mode == MissionKind.WALK) R.string.movement_unavailable_steps
                else R.string.movement_unavailable_motion
            return
        }
        running =
            sensors.registerListener(
                this,
                sensor,
                if (mode == MissionKind.WALK) SensorManager.SENSOR_DELAY_NORMAL
                else SensorManager.SENSOR_DELAY_GAME,
            )
        if (!running) message = R.string.movement_unavailable_motion
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
                count = offset + (total - start).toInt()
            }
            else -> {
                // The core counts in g, as iOS's userAcceleration is.
                val (x, y, z) = event.values
                counter.sample(
                    sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH,
                    event.timestamp / 1e9,
                )
                count = offset + counter.count().toInt()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@Composable
private fun MovementMission(
    mode: MissionKind,
    target: Int,
    saved: MissionProgress?,
    record: Record,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val tracker = remember { MovementTracker(context, mode, saved.resumed(target)) }
    var finished by remember { mutableStateOf(false) }
    var savedCount by remember { mutableIntStateOf(saved.resumed(target)) }
    // The count is saved every few, and on leaving, rather than on every step.
    fun save(force: Boolean = false) {
        val count = minOf(tracker.count, target)
        if (finished || count == savedCount || (!force && count - savedCount < 5)) return
        savedCount = count
        record { it.copy(solved = count) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                tracker.stop()
                save(force = true)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            tracker.stop()
            save(force = true)
        }
    }
    LaunchedEffect(tracker.count) {
        if (tracker.count >= target && !finished) {
            finished = true
            tracker.stop()
            onComplete()
        } else save()
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tracker.start() else tracker.message = R.string.movement_allow_activity
        }
    val spoken =
        pluralStringResource(
            if (mode == MissionKind.WALK) R.plurals.movement_steps_spoken
            else R.plurals.movement_shakes_spoken,
            target,
            minOf(tracker.count, target),
            target,
        )
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
            Modifier.clearAndSetSemantics { contentDescription = spoken },
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
            Text(
                stringResource(R.string.movement_of_target, target),
                style = MaterialTheme.typography.titleLarge.numeric(),
            )
        }
        LinearProgressIndicator(
            progress = { shown / target.toFloat() },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            color = SevenColors.time,
        )
        Text(
            stringResource(
                if (mode == MissionKind.WALK) R.string.movement_walk_hint
                else R.string.movement_shake_hint
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        tracker.message?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!tracker.running && !finished)
            PrimaryButton(
                stringResource(R.string.movement_start),
                Modifier.testTag("start-challenge"),
                icon = Icons.Outlined.PlayArrow,
            ) {
                if (
                    mode == MissionKind.WALK &&
                        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) !=
                            PackageManager.PERMISSION_GRANTED
                )
                    permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                else tracker.start()
            }
        Text(
            stringResource(R.string.movement_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
