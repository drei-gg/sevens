@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.alarm

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.alarm.core.*
import gg.seven.kit.ConnectedChoice
import gg.seven.kit.DateField
import gg.seven.kit.DropdownField
import gg.seven.kit.FullScreenForm
import gg.seven.kit.Space
import gg.seven.kit.TextInput
import gg.seven.kit.TimeField
import gg.seven.kit.numeric
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The routine editor (iOS RoutineEditor) as Material's full-screen form: the time first and big,
 * its days under it, then the name, the sound, the mission and snooze, and the coming seven days.
 * The draft lives in the model, so it survives rotation.
 */
@Composable
fun RoutineEditor(model: AlarmModel, routine: Routine) {
    val engine = model.engine
    val archive by engine.archive.collectAsStateWithLifecycle()
    val busy by engine.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var saveError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val sounds = remember(context) { SoundFiles(context) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            sounds.prune(engine.archive.value, model.draft.value)
        }
    }
    val chooseSound =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    importing = true
                    try {
                        val sound = sounds.import(uri)
                        player?.release()
                        player = null
                        // Cancellation or a different editor must not publish a stale selection.
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        model.draft.value
                            ?.takeIf { it.id == routine.id }
                            ?.let { model.draft.value = it.copy(importedSound = sound) }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        saveError =
                            (error as? LoopException.Invalid)?.reason
                                ?: error.message
                                ?: "This sound could not be imported."
                    } finally {
                        importing = false
                        if (model.draft.value?.id != routine.id)
                            sounds.prune(engine.archive.value, model.draft.value)
                    }
                }
        }
    val original =
        androidx.compose.runtime.saveable.rememberSaveable(routine.id) { routine.toString() }
    val isNew = archive.routines.none { it.id == routine.id }
    fun set(next: Routine) {
        model.draft.value = next
    }
    FullScreenForm(
        title =
            if (isNew) (if (routine.kind == RoutineKind.ALARM) "New alarm" else "New loop")
            else "Edit routine",
        onClose = { model.draft.value = null },
        onSave = {
            model.save(
                routine,
                onSaved = { model.draft.value = null },
                onFailed = { saveError = it },
            )
        },
        busy = busy || importing,
        confirmDiscard = routine.toString() != original,
    ) {
        // Resolve these inside the form: a Dialog owns a separate Compose focus root.
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        TimeSections(routine, ::set)

        EditorHeading("Routine")
        TextInput("Name", routine.name, Modifier.testTag("routine-name")) {
            set(routine.copy(name = it))
        }
        TextInput("Why get up? (optional)", routine.reason.orEmpty(), minLines = 2) {
            set(routine.copy(reason = it))
        }
        TextInput("Group (optional)", routine.group.orEmpty()) { set(routine.copy(group = it)) }
        Text(
            "Timing",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConnectedChoice(
            listOfNotNull(
                Timing.WEEKLY to "Weekly times",
                Timing.ONCE to "Once",
                if (routine.kind == RoutineKind.LOOP) Timing.WINDOW to "Interval window" else null,
            ),
            routine.timing,
        ) {
            set(routine.copy(timing = it))
        }
        if (routine.kind == RoutineKind.LOOP) {
            Text(
                "Delivery",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectedChoice(
                listOf(Delivery.REMINDER to "Quiet reminder", Delivery.ALARM to "Alarm"),
                routine.delivery,
            ) {
                set(
                    if (it == Delivery.REMINDER)
                        routine.copy(delivery = it, mission = MissionKind.NONE, followUpMinutes = 0)
                    else routine.copy(delivery = it)
                )
            }
        }

        if (routine.delivery == Delivery.ALARM) {
            EditorHeading("Sound")
            routine.importedSound?.let { sound ->
                Text(
                    sound.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("imported-sound"),
                )
                if (!sounds.available(sound))
                    Text(
                        "This audio file is missing. The fallback tone will play; import it again to restore your sound.",
                        color = MaterialTheme.colorScheme.error,
                    )
            }
            if (
                routine.importedSound != null ||
                    (routine.tone != null && routine.tone != AlarmTone.SYSTEM)
            ) {
                FilledTonalButton(
                    onClick = {
                        focus.clearFocus(force = true)
                        keyboard?.hide()
                        if (player != null) {
                            player?.release()
                            player = null
                        } else {
                            try {
                                val next =
                                    Tones.player(
                                        context,
                                        routine.tone ?: AlarmTone.SYSTEM,
                                        ringing = false,
                                        imported = routine.importedSound,
                                    )
                                next.setOnCompletionListener { finished ->
                                    finished.release()
                                    if (player === finished) player = null
                                }
                                try {
                                    next.start()
                                    player = next
                                } catch (error: Exception) {
                                    next.release()
                                    throw error
                                }
                            } catch (error: Exception) {
                                saveError = error.message ?: "This sound could not be previewed."
                            }
                        }
                    },
                    modifier = Modifier.testTag("preview-tone"),
                ) {
                    Icon(
                        if (player == null) Icons.Outlined.PlayArrow else Icons.Outlined.Stop,
                        null,
                    )
                    Spacer(Modifier.width(Space.s))
                    Text(
                        if (player != null) "Stop preview"
                        else if (routine.importedSound != null) "Preview imported sound"
                        else "Preview tone"
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FilledTonalButton(
                    onClick = {
                        player?.release()
                        player = null
                        chooseSound.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave"))
                    },
                    enabled = !importing,
                    modifier = Modifier.testTag("import-sound"),
                ) {
                    Text(if (routine.importedSound == null) "Import WAV sound" else "Replace sound")
                }
                if (routine.importedSound != null)
                    TextButton(
                        onClick = {
                            player?.release()
                            player = null
                            set(routine.copy(importedSound = null))
                        }
                    ) {
                        Text("Use built-in tone")
                    }
                if (importing) LoadingIndicator(Modifier.size(32.dp))
            }
            DropdownField(
                if (routine.importedSound == null) "Tone" else "Fallback tone",
                routine.tone ?: AlarmTone.SYSTEM,
                AlarmTone.entries.map { it to it.title },
            ) {
                player?.release()
                player = null
                set(routine.copy(tone = it))
            }
            EditorFooter(
                "16-bit PCM WAV, mono or stereo, 8–48 kHz, up to 29 seconds and 6 MB. JSON backups and templates do not include audio. Missing audio uses the fallback tone."
            )
            EditorFooter(
                "Preview uses media volume. The ringing alarm uses the alarm volume; this app doesn’t override it."
            )

            EditorHeading("Wake-up mission")
            DropdownField(
                "Mission",
                routine.mission,
                MissionKind.entries.map { it to it.title },
                Modifier.testTag("mission-picker"),
            ) {
                set(routine.copy(mission = it))
            }
            if (
                routine.mission in
                    listOf(
                        MissionKind.MEMORY,
                        MissionKind.SHAKE,
                        MissionKind.WALK,
                        MissionKind.NUMBER_ORDER,
                        MissionKind.COLOR_WORD,
                    )
            ) {
                Text(
                    "Difficulty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConnectedChoice(
                    listOf(1 to "Easy", 2 to "Medium", 3 to "Hard"),
                    routine.difficulty,
                ) {
                    set(routine.copy(challengeLevel = it))
                }
            }
            if (routine.mission == MissionKind.ARITHMETIC)
                StepRow(
                    if (routine.mathCount == 1) "1 question" else "${routine.mathCount} questions",
                    routine.mathCount,
                    1..10,
                ) {
                    set(routine.copy(mathCount = it))
                }
            if (routine.mission == MissionKind.TYPING)
                TextInput("Phrase", routine.phrase) { set(routine.copy(phrase = it)) }
            if (routine.mission == MissionKind.CODE) {
                OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (routine.code.isEmpty()) "Scan your code" else "Replace saved code")
                }
                if (routine.code.isNotEmpty())
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Code saved on this phone",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
            }
            if (routine.mission != MissionKind.NONE)
                TextButton(onClick = { engine.previewMission.value = routine }) {
                    Icon(Icons.Outlined.PlayCircle, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Preview mission")
                }
            EditorFooter(
                "Android keeps a Stop button on the ringing alarm. A mission records completion; it can’t prevent shutdown or the system’s own dismissal."
            )

            EditorHeading("Snooze and wake-up check")
            StepRow(
                "Snooze: ${ClockFormat.minutes(routine.snoozeMinutes)}",
                routine.snoozeMinutes,
                1..30,
            ) {
                set(routine.copy(snoozeMinutes = it))
            }
            StepRow(
                if (routine.snoozeLimit == 0) "No snoozes"
                else "Snooze limit: ${routine.snoozeLimit}",
                routine.snoozeLimit,
                0..10,
            ) {
                set(routine.copy(snoozeLimit = it))
            }
            StepRow(
                if (routine.followUpMinutes == 0) "Wake-up check: off"
                else "Check again after ${ClockFormat.minutes(routine.followUpMinutes)}",
                routine.followUpMinutes,
                0..30,
            ) {
                set(routine.copy(followUpMinutes = it))
            }
            EditorFooter(
                "Open the alarm to use its mission or snooze. A wake-up check rings once after completion or a dismissal."
            )
        }

        EditorHeading("Next seven days")
        val upcoming =
            remember(routine) {
                runCatching { scheduleOccurrences(routine, Instant.now(), 7, deviceCalendar()) }
                    .getOrNull()
            }
        if (upcoming.isNullOrEmpty())
            Text(
                "No upcoming times. Check the schedule.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else {
            upcoming.take(12).forEach { date ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        ClockFormat.shortDay(date),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        ClockFormat.time(date),
                        style = MaterialTheme.typography.bodyLarge.numeric(),
                    )
                }
            }
            if (upcoming.size > 12)
                Text(
                    "${upcoming.size - 12} more this week",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        EditorFooter(
            "Clock times follow your current time zone. When the clocks change, the preview uses the next valid time and the first of a repeated time."
        )
        Spacer(Modifier.height(Space.l))
    }
    if (scanning)
        CodeScanner(onClose = { scanning = false }) { value ->
            scanning = false
            model.draft.value = model.draft.value?.copy(code = value)
        }
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text("OK") } },
            title = { Text("Couldn’t save") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun EditorHeading(text: String) {
    Text(
        text,
        Modifier.padding(top = Space.s),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EditorFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TimeSections(routine: Routine, set: (Routine) -> Unit) {
    when (routine.timing) {
        Timing.ONCE -> {
            EditorHeading("Rings once")
            val zone = ZoneId.systemDefault()
            val at = routine.once.atZone(zone)
            BigTime(at.hour, at.minute, key = "once") { h, m ->
                set(
                    routine.copy(
                        once = at.withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant()
                    )
                )
            }
            DateField("Date", at.toLocalDate()) { day ->
                set(routine.copy(once = day.atTime(at.toLocalTime()).atZone(zone).toInstant()))
            }
        }
        Timing.WEEKLY -> {
            routine.slots.forEach { slot ->
                fun update(next: TimeSlot) =
                    set(
                        routine.copy(
                            slots = routine.slots.map { if (it.id == slot.id) next else it }
                        )
                    )
                EditorHeading("${ClockFormat.time(slot.minute)} · ${WeekOrder.list(slot.weekdays)}")
                if (routine.slots.size == 1)
                    BigTime(slot.minute / 60, slot.minute % 60, key = slot.id.toString()) { h, m ->
                        update(slot.copy(minute = h * 60 + m))
                    }
                else
                    TimeField("Time", slot.minute / 60, slot.minute % 60) { h, m ->
                        update(slot.copy(minute = h * 60 + m))
                    }
                WeekdayPicker(slot.weekdays) { update(slot.copy(weekdays = it)) }
                if (routine.slots.size > 1)
                    TextButton(
                        onClick = {
                            set(routine.copy(slots = routine.slots.filter { it.id != slot.id }))
                        },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        Text("Remove time")
                    }
            }
            OutlinedButton(
                onClick = {
                    set(
                        routine.copy(
                            slots = routine.slots + newTimeSlot(720, defaultTimeSlot().weekdays)
                        )
                    )
                },
                enabled = routine.slots.size < 48,
                modifier = Modifier.testTag("add-time"),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Add another time")
            }
            EditorFooter(
                "Each time rings on its own days. Turn Wednesday off for 7:00 without changing noon or 2:00."
            )
        }
        Timing.WINDOW -> {
            EditorHeading("Time window")
            TimeField("Start", routine.windowStart / 60, routine.windowStart % 60) { h, m ->
                set(routine.copy(windowStart = h * 60 + m))
            }
            TimeField("End", routine.windowEnd / 60, routine.windowEnd % 60) { h, m ->
                set(routine.copy(windowEnd = h * 60 + m))
            }
            gg.seven.kit.IntegerSetting(
                "Interval in minutes",
                routine.intervalMinutes,
                5..720,
                step = 5,
            ) {
                set(routine.copy(intervalMinutes = it))
            }
            WeekdayPicker(routine.windowDays) { set(routine.copy(windowDays = it)) }
            EditorFooter(
                "Overnight windows belong to the day they start. The end is included only when an interval lands on it."
            )
        }
    }
}

/**
 * The time big and first, as Material's clock-face time picker inline in the form (the iOS wheel's
 * analogue); keyboard entry at large text sizes, where the dial doesn't fit.
 */
@Composable
private fun BigTime(hour: Int, minute: Int, key: String, onChange: (Int, Int) -> Unit) {
    var keyboard by
        androidx.compose.runtime.saveable.rememberSaveable(key) { mutableStateOf(false) }
    val state = key(key) { rememberTimePickerState(hour, minute, ClockFormat.h24) }
    val latest by rememberUpdatedState(onChange)
    val shown by rememberUpdatedState(hour to minute)
    LaunchedEffect(state.hour, state.minute) {
        if (state.hour to state.minute != shown) latest(state.hour, state.minute)
    }
    Column(
        Modifier.fillMaxWidth().testTag("time-picker"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (keyboard || largeText()) TimeInput(state)
        else TimePicker(state, Modifier.widthIn(max = 360.dp))
        if (!largeText())
            TextButton(onClick = { keyboard = !keyboard }) {
                Icon(if (keyboard) Icons.Outlined.Schedule else Icons.Outlined.Keyboard, null)
                Spacer(Modifier.width(Space.s))
                Text(if (keyboard) "Use clock" else "Type time")
            }
    }
}
