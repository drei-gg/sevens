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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    val res = LocalResources.current
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
                                ?: res.getString(R.string.sound_import_failed)
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
            stringResource(
                if (!isNew) R.string.editor_edit
                else if (routine.kind == RoutineKind.ALARM) R.string.editor_new_alarm
                else R.string.editor_new_loop
            ),
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

        EditorHeading(stringResource(R.string.editor_routine))
        TextInput(
            stringResource(R.string.editor_name),
            routine.name,
            Modifier.testTag("routine-name"),
        ) {
            set(routine.copy(name = it))
        }
        TextInput(
            stringResource(R.string.editor_reason),
            routine.reason.orEmpty(),
            minLines = 2,
        ) {
            set(routine.copy(reason = it))
        }
        TextInput(stringResource(R.string.editor_group), routine.group.orEmpty()) {
            set(routine.copy(group = it))
        }
        Text(
            stringResource(R.string.editor_timing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConnectedChoice(
            listOfNotNull(
                Timing.WEEKLY to stringResource(R.string.editor_timing_weekly),
                Timing.ONCE to stringResource(R.string.editor_timing_once),
                if (routine.kind == RoutineKind.LOOP)
                    Timing.WINDOW to stringResource(R.string.editor_timing_window)
                else null,
            ),
            routine.timing,
        ) {
            set(routine.copy(timing = it))
        }
        if (routine.kind == RoutineKind.LOOP) {
            Text(
                stringResource(R.string.editor_delivery),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectedChoice(
                listOf(
                    Delivery.REMINDER to stringResource(R.string.editor_delivery_reminder),
                    Delivery.ALARM to stringResource(R.string.editor_delivery_alarm),
                ),
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
            EditorHeading(stringResource(R.string.editor_sound))
            routine.importedSound?.let { sound ->
                Text(
                    sound.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("imported-sound"),
                )
                if (!sounds.available(sound))
                    Text(
                        stringResource(R.string.editor_sound_missing),
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
                                saveError =
                                    error.message ?: res.getString(R.string.sound_preview_failed)
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
                        stringResource(
                            if (player != null) R.string.editor_stop_preview
                            else if (routine.importedSound != null) R.string.editor_preview_imported
                            else R.string.editor_preview_tone
                        )
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
                    Text(
                        stringResource(
                            if (routine.importedSound == null) R.string.editor_import_sound
                            else R.string.editor_replace_sound
                        )
                    )
                }
                if (routine.importedSound != null)
                    TextButton(
                        onClick = {
                            player?.release()
                            player = null
                            set(routine.copy(importedSound = null))
                        }
                    ) {
                        Text(stringResource(R.string.editor_use_built_in))
                    }
                if (importing) LoadingIndicator(Modifier.size(32.dp))
            }
            DropdownField(
                stringResource(
                    if (routine.importedSound == null) R.string.editor_tone
                    else R.string.editor_fallback_tone
                ),
                routine.tone ?: AlarmTone.SYSTEM,
                AlarmTone.entries.map { it to it.title },
            ) {
                player?.release()
                player = null
                set(routine.copy(tone = it))
            }
            EditorFooter(stringResource(R.string.editor_sound_footer))
            EditorFooter(stringResource(R.string.editor_volume_footer))

            EditorHeading(stringResource(R.string.editor_mission_heading))
            DropdownField(
                stringResource(R.string.editor_mission),
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
                    stringResource(R.string.editor_difficulty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConnectedChoice(
                    listOf(
                        1 to stringResource(R.string.editor_easy),
                        2 to stringResource(R.string.editor_medium),
                        3 to stringResource(R.string.editor_hard),
                    ),
                    routine.difficulty,
                ) {
                    set(routine.copy(challengeLevel = it))
                }
            }
            if (routine.mission == MissionKind.ARITHMETIC)
                StepRow(
                    pluralStringResource(
                        R.plurals.editor_questions,
                        routine.mathCount,
                        routine.mathCount,
                    ),
                    routine.mathCount,
                    1..10,
                ) {
                    set(routine.copy(mathCount = it))
                }
            if (routine.mission == MissionKind.TYPING)
                TextInput(stringResource(R.string.editor_phrase), routine.phrase) {
                    set(routine.copy(phrase = it))
                }
            if (routine.mission == MissionKind.CODE) {
                OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        stringResource(
                            if (routine.code.isEmpty()) R.string.editor_scan_code
                            else R.string.editor_replace_code
                        )
                    )
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
                            stringResource(R.string.editor_code_saved),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
            }
            if (routine.mission != MissionKind.NONE)
                TextButton(onClick = { engine.previewMission.value = routine }) {
                    Icon(Icons.Outlined.PlayCircle, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.editor_preview_mission))
                }
            EditorFooter(stringResource(R.string.editor_mission_footer))

            EditorHeading(stringResource(R.string.editor_snooze_heading))
            StepRow(
                stringResource(
                    R.string.defaults_snooze,
                    ClockFormat.minutes(routine.snoozeMinutes),
                ),
                routine.snoozeMinutes,
                1..30,
            ) {
                set(routine.copy(snoozeMinutes = it))
            }
            StepRow(
                if (routine.snoozeLimit == 0) stringResource(R.string.defaults_no_snoozes)
                else stringResource(R.string.defaults_snooze_limit, routine.snoozeLimit),
                routine.snoozeLimit,
                0..10,
            ) {
                set(routine.copy(snoozeLimit = it))
            }
            StepRow(
                if (routine.followUpMinutes == 0) stringResource(R.string.defaults_check_off)
                else
                    stringResource(
                        R.string.defaults_check_after,
                        ClockFormat.minutes(routine.followUpMinutes),
                    ),
                routine.followUpMinutes,
                0..30,
            ) {
                set(routine.copy(followUpMinutes = it))
            }
            EditorFooter(stringResource(R.string.editor_snooze_footer))
        }

        EditorHeading(stringResource(R.string.editor_next_week))
        val upcoming =
            remember(routine) {
                runCatching { scheduleOccurrences(routine, Instant.now(), 7, deviceCalendar()) }
                    .getOrNull()
            }
        if (upcoming.isNullOrEmpty())
            Text(
                stringResource(R.string.editor_no_upcoming),
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
                    pluralStringResource(
                        R.plurals.editor_more_this_week,
                        upcoming.size - 12,
                        upcoming.size - 12,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        EditorFooter(stringResource(R.string.editor_zone_footer))
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
            confirmButton = {
                TextButton(onClick = { saveError = null }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            title = { Text(stringResource(R.string.editor_save_failed)) },
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
            EditorHeading(stringResource(R.string.editor_rings_once))
            val zone = ZoneId.systemDefault()
            val at = routine.once.atZone(zone)
            BigTime(at.hour, at.minute, key = "once") { h, m ->
                set(
                    routine.copy(
                        once = at.withHour(h).withMinute(m).withSecond(0).withNano(0).toInstant()
                    )
                )
            }
            DateField(stringResource(R.string.editor_date), at.toLocalDate()) { day ->
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
                EditorHeading(
                    stringResource(
                        R.string.editor_slot_heading,
                        ClockFormat.time(slot.minute),
                        WeekOrder.list(slot.weekdays),
                    )
                )
                if (routine.slots.size == 1)
                    BigTime(slot.minute / 60, slot.minute % 60, key = slot.id.toString()) { h, m ->
                        update(slot.copy(minute = h * 60 + m))
                    }
                else
                    TimeField(
                        stringResource(R.string.editor_time),
                        slot.minute / 60,
                        slot.minute % 60,
                    ) { h, m ->
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
                        Text(stringResource(R.string.editor_remove_time))
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
                Text(stringResource(R.string.editor_add_time))
            }
            EditorFooter(stringResource(R.string.editor_slots_footer))
        }
        Timing.WINDOW -> {
            EditorHeading(stringResource(R.string.editor_window))
            TimeField(
                stringResource(R.string.editor_start),
                routine.windowStart / 60,
                routine.windowStart % 60,
            ) { h, m ->
                set(routine.copy(windowStart = h * 60 + m))
            }
            TimeField(
                stringResource(R.string.editor_end),
                routine.windowEnd / 60,
                routine.windowEnd % 60,
            ) { h, m ->
                set(routine.copy(windowEnd = h * 60 + m))
            }
            gg.seven.kit.IntegerSetting(
                stringResource(R.string.editor_interval),
                routine.intervalMinutes,
                5..720,
                step = 5,
            ) {
                set(routine.copy(intervalMinutes = it))
            }
            WeekdayPicker(routine.windowDays) { set(routine.copy(windowDays = it)) }
            EditorFooter(stringResource(R.string.editor_window_footer))
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
                Text(
                    stringResource(
                        if (keyboard) R.string.editor_use_clock else R.string.editor_type_time
                    )
                )
            }
    }
}
