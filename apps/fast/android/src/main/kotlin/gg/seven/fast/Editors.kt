@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package gg.seven.fast

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import java.time.*
import kotlin.math.roundToInt

@Composable
internal fun NativeDateTime(
    label: String,
    value: Double,
    withTime: Boolean = true,
    onChange: (Double) -> Unit,
) {
    val context = LocalContext.current
    val date = value.instant().atZone(ZoneId.systemDefault())
    OutlinedButton(
        onClick = {
            DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        val selected = LocalDate.of(year, month + 1, day)
                        if (withTime)
                            TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        onChange(
                                            selected
                                                .atTime(h, m)
                                                .atZone(ZoneId.systemDefault())
                                                .toInstant()
                                                .toEpochMilli()
                                                .toDouble()
                                        )
                                    },
                                    date.hour,
                                    date.minute,
                                    DateFormat.is24HourFormat(context),
                                )
                                .show()
                        else
                            onChange(
                                selected
                                    .atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                                    .toDouble()
                            )
                    },
                    date.year,
                    date.monthValue - 1,
                    date.dayOfMonth,
                )
                .show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$label · ${if (withTime) stamp(value) else date.toLocalDate()}")
    }
}

@Composable
internal fun EditorDialog(
    m: FastModel,
    a: FastArchive,
    type: String,
    id: String?,
    initialDate: Double? = null,
    initialFastId: String? = null,
    dismiss: () -> Unit,
) {
    val busy by m.busy.collectAsStateWithLifecycle()
    val f = a.fasts.find { it.id == id }
    val j = a.journal.find { it.id == id }
    val metric = a.metrics.find { it.id == id }
    val plan = a.plans.find { it.id == id }
    val rest = a.restDays.find { it.id == id }
    val preset = a.presets.find { it.id == id }
    val baseNow = remember { initialDate ?: nowMillis() }
    var start by rememberSaveable {
        mutableDoubleStateOf(
            f?.start
                ?: j?.date
                ?: metric?.date
                ?: plan?.start
                ?: rest?.date
                ?: if (type == "past") baseNow - 16 * 3_600_000
                else if (type == "plan") baseNow + 86_400_000 else baseNow
        )
    }
    var end by rememberSaveable { mutableDoubleStateOf(f?.end ?: baseNow) }
    var openEnded by rememberSaveable {
        mutableStateOf(
            if (f != null) f.goalMinutes == null
            else if (plan != null) plan.goalMinutes == null
            else if (preset != null) preset.goalMinutes == null else false
        )
    }
    var goal by rememberSaveable {
        mutableStateOf(
            ((f?.goalMinutes ?: plan?.goalMinutes ?: preset?.goalMinutes ?: 960) / 60.0).toString()
        )
    }
    var eating by rememberSaveable {
        mutableStateOf(
            ((f?.eatingMinutes ?: plan?.eatingMinutes ?: preset?.eatingMinutes ?: 480) / 60.0)
                .toString()
        )
    }
    var intention by rememberSaveable { mutableStateOf(f?.intention ?: plan?.intention ?: "") }
    var reflection by rememberSaveable { mutableStateOf(f?.reflection ?: "") }
    var text by rememberSaveable { mutableStateOf(j?.text ?: rest?.note ?: preset?.name ?: "") }
    var tags by rememberSaveable {
        mutableStateOf((f?.tags ?: j?.tags).orEmpty().joinToString(", "))
    }
    var linkedFast by rememberSaveable {
        mutableStateOf(if (j == null) initialFastId ?: a.active?.id else j.fastID)
    }
    var mood by rememberSaveable { mutableStateOf(j?.mood?.toString().orEmpty()) }
    var energy by rememberSaveable { mutableStateOf(j?.energy?.toString().orEmpty()) }
    var hunger by rememberSaveable { mutableStateOf(j?.hunger?.toString().orEmpty()) }
    var kindText by rememberSaveable {
        mutableStateOf(metric?.kind?.name ?: if (type == "water") "water" else "weight")
    }
    val kind = MetricKind.valueOf(kindText)
    var value by rememberSaveable {
        mutableStateOf(
            metric?.let { Units.display(it.value, it.kind, a.settings).toString() }
                ?: if (type == "water")
                    Units.display(
                            a.settings.water.quickAddMilliliters,
                            MetricKind.water,
                            a.settings,
                        )
                        .toString()
                else ""
        )
    }
    var delete by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val title =
        when (type) {
            "start" -> "Start a fast"
            "past" -> "Add past fast"
            "end" -> "End fast"
            "fast" -> "Edit fast"
            "journal" -> "Journal entry"
            "water" -> "Water entry"
            "metric" -> "Measurement"
            "plan" -> "Plan a fast"
            "rest" -> "Rest day"
            "preset" -> "Saved timer"
            else -> "Water settings"
        }
    if (type == "waterSettings") {
        WaterSettingsDialog(m, a, dismiss)
        return
    }
    fun save() {
        try {
            fun integer(s: String, label: String) =
                s.toIntOrNull() ?: error("Enter a whole number for $label")
            val parsedTags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val newGoal =
                if (type in listOf("start", "past", "end", "fast", "plan", "preset")) {
                    if (openEnded) null
                    else ((goal.toDoubleOrNull() ?: error("Enter goal hours")) * 60).roundToInt()
                } else null
            val eat =
                if (type in listOf("start", "past", "end", "fast", "plan", "preset"))
                    ((eating.toDoubleOrNull() ?: error("Enter eating window hours")) * 60)
                        .roundToInt()
                else 480
            val newMetric =
                if (type in listOf("metric", "water"))
                    Units.canonical(
                        value.toDoubleOrNull() ?: error("Enter an amount"),
                        kind,
                        a.settings,
                    )
                else 0.0
            fun rating(s: String) = if (s.isBlank()) null else integer(s, "rating")
            val newMood = rating(mood)
            val newEnergy = rating(energy)
            val newHunger = rating(hunger)
            m.mutate(
                { current ->
                    when (type) {
                        "start",
                        "past",
                        "end",
                        "fast" ->
                            current.save(
                                FastRecord(
                                    id = f?.id ?: newId(),
                                    start = start,
                                    end =
                                        if (type == "past" || type == "end" || f?.end != null) end
                                        else null,
                                    goalMinutes = newGoal,
                                    eatingMinutes = eat,
                                    timeZoneID = f?.timeZoneID ?: ZoneId.systemDefault().id,
                                    intention = intention,
                                    reflection = reflection,
                                    tags = parsedTags,
                                ),
                                nowMillis(),
                            )
                        "journal" -> {
                            val entry =
                                JournalEntry(
                                    j?.id ?: newId(),
                                    start,
                                    linkedFast,
                                    text,
                                    newMood,
                                    newEnergy,
                                    newHunger,
                                    parsedTags,
                                )
                            current.copy(
                                journal = current.journal.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "metric",
                        "water" -> {
                            val entry =
                                MetricEntry(
                                    metric?.id ?: newId(),
                                    start,
                                    kind,
                                    newMetric,
                                    metric?.externalID,
                                )
                            current.copy(
                                metrics = current.metrics.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "plan" -> {
                            val entry =
                                PlannedFast(plan?.id ?: newId(), start, newGoal, eat, intention)
                            current.copy(
                                plans = current.plans.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "rest" -> {
                            val entry = RestDay(rest?.id ?: newId(), start, text)
                            current.copy(
                                restDays = current.restDays.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "preset" -> {
                            val entry = FastPreset(preset?.id ?: newId(), text, newGoal, eat)
                            current.copy(
                                presets = current.presets.filterNot { it.id == entry.id } + entry
                            )
                        }
                        else -> current
                    }
                },
                dismiss,
            )
        } catch (e: Exception) {
            localError = e.message
        }
    }
    EditorSheet(
        title,
        { if (!busy) discard = true },
        ::save,
        busy,
        saveLabel = if (type == "end") "Save completed fast" else "Save",
    ) {
        if (type != "preset")
            NativeDateTime(
                if (type in listOf("start", "past", "end", "fast", "plan")) "Start" else "Date",
                start,
                type != "rest",
            ) {
                start = it
            }
        if (type == "past" || type == "end" || f?.end != null)
            NativeDateTime("End", end) { end = it }
        if (type in listOf("start", "past", "end", "fast", "plan", "preset")) {
            if (type in listOf("start", "past", "plan"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (FastPreset.defaults + a.presets).forEach { p ->
                        FilterChip(
                            selected =
                                (if (openEnded) null
                                else goal.toDoubleOrNull()?.times(60)?.roundToInt()) ==
                                    p.goalMinutes &&
                                    eating.toDoubleOrNull()?.times(60)?.roundToInt() ==
                                        p.eatingMinutes,
                            onClick = {
                                openEnded = p.goalMinutes == null
                                goal = ((p.goalMinutes ?: 960) / 60.0).toString()
                                eating = (p.eatingMinutes / 60.0).toString()
                            },
                            label = { Text(p.name) },
                        )
                    }
                }
            ToggleRow("Set a goal", !openEnded) { openEnded = !it }
            if (!openEnded) NumberField("Goal hours", goal) { goal = it }
            NumberField("Eating window hours", eating) { eating = it }
            Text(
                "Recording limits are not recommendations.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (type != "preset")
                OutlinedTextField(
                    intention,
                    { intention = it },
                    label = { Text("Intention (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
        }
        if (type == "past" || type == "end" || f?.end != null)
            OutlinedTextField(
                reflection,
                { reflection = it },
                label = { Text("Reflection (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        if (type in listOf("journal", "rest", "preset"))
            OutlinedTextField(
                text,
                { text = it },
                label = {
                    Text(
                        if (type == "preset") "Timer name"
                        else if (type == "rest") "Note (optional)" else "Observation or reflection"
                    )
                },
                minLines = if (type == "journal") 4 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        if (type == "journal") {
            ChoiceField(
                "Linked fast",
                linkedFast.orEmpty(),
                listOf("" to "An ordinary day") +
                    a.fasts.sortedByDescending { it.start }.map { it.id to stamp(it.start) },
            ) {
                linkedFast = it.ifEmpty { null }
            }
            RatingChoice("Mood", "Low", "Good", mood) { mood = it }
            RatingChoice("Energy", "Low", "High", energy) { energy = it }
            RatingChoice("Hunger", "None", "Strong", hunger) { hunger = it }
            Text(
                "These are your observations, not a score to improve.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (type in listOf("start", "past", "end", "fast", "journal"))
            OutlinedTextField(
                tags,
                { tags = it },
                label = { Text("Tags, separated by commas") },
                modifier = Modifier.fillMaxWidth(),
            )
        if (type in listOf("metric", "water")) {
            if (metric == null && type != "water")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricKind.entries
                        .filter { a.settings.showWeight || it != MetricKind.weight }
                        .forEach { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = {
                                    kindText = k.name
                                    value = ""
                                },
                                label = { Text(k.name.replaceFirstChar { it.uppercase() }) },
                            )
                        }
                }
            NumberField("Amount · ${Units.label(kind, a.settings)}", value) { value = it }
            if (kind == MetricKind.water && metric == null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (a.settings.useFluidOunces) listOf(8, 12, 16) else listOf(150, 250, 500))
                        .forEach { amount ->
                            FilterChip(
                                selected = value.toDoubleOrNull() == amount.toDouble(),
                                onClick = { value = amount.toString() },
                                label = { Text("$amount ${Units.label(kind, a.settings)}") },
                            )
                        }
                }
        }
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val operationError by m.message.collectAsStateWithLifecycle()
        operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (id != null)
            TextButton(onClick = { delete = true }, enabled = !busy) {
                Text(
                    if (f?.end == null && f != null) "Discard fast" else "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
    }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard this draft?") },
            text = { Text("Your saved records will stay as they are.") },
            confirmButton = { TextButton(onClick = dismiss) { Text("Discard draft") } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } },
        )
    if (delete)
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Delete this record?") },
            text = {
                Text("This cannot be undone. Journal entries linked to a deleted fast are kept.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        m.mutate(
                            { current ->
                                when (type) {
                                    "fast",
                                    "end" -> current.deleteFast(id!!)
                                    "journal" ->
                                        current.copy(
                                            journal = current.journal.filterNot { it.id == id }
                                        )
                                    "metric",
                                    "water" ->
                                        current.copy(
                                            metrics = current.metrics.filterNot { it.id == id }
                                        )
                                    "plan" ->
                                        current.copy(
                                            plans = current.plans.filterNot { it.id == id }
                                        )
                                    "rest" ->
                                        current.copy(
                                            restDays = current.restDays.filterNot { it.id == id }
                                        )
                                    "preset" ->
                                        current.copy(
                                            presets = current.presets.filterNot { it.id == id }
                                        )
                                    else -> current
                                }
                            },
                            dismiss,
                        )
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Keep") } },
        )
}

@Composable
internal fun NumberField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ToggleRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun WaterSettingsDialog(m: FastModel, a: FastArchive, dismiss: () -> Unit) {
    val s = a.settings.water
    val unit = Units.label(MetricKind.water, a.settings)
    fun display(value: Double) = Units.display(value, MetricKind.water, a.settings).toString()
    var useGoal by rememberSaveable { mutableStateOf(s.goalMilliliters != null) }
    var goal by rememberSaveable { mutableStateOf(s.goalMilliliters?.let(::display).orEmpty()) }
    var glass by rememberSaveable { mutableStateOf(display(s.quickAddMilliliters)) }
    var reminders by rememberSaveable { mutableStateOf(s.remindersEnabled) }
    var paused by rememberSaveable { mutableStateOf(s.remindersPaused) }
    var start by rememberSaveable { mutableStateOf(s.startHour.toString()) }
    var end by rememberSaveable { mutableStateOf(s.endHour.toString()) }
    var interval by rememberSaveable { mutableStateOf(s.intervalHours.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!it)
                m.message.value =
                    "Notifications are off. Tracking still works; enable notifications in Android Settings when you want reminders."
        }
    val busy by m.busy.collectAsStateWithLifecycle()
    fun save() {
        try {
            fun amount(raw: String) = Units.canonical(raw.toDouble(), MetricKind.water, a.settings)
            val settings =
                WaterSettings(
                    if (!useGoal) null
                    else if (goal == s.goalMilliliters?.let(::display)) s.goalMilliliters
                    else amount(goal),
                    if (glass == display(s.quickAddMilliliters)) s.quickAddMilliliters
                    else amount(glass),
                    reminders,
                    paused,
                    start.toInt(),
                    end.toInt(),
                    interval.toInt(),
                )
            settings.validate()
            m.mutate(
                { it.copy(settings = it.settings.copy(water = settings)) },
                {
                    if (reminders && !paused && Build.VERSION.SDK_INT >= 33)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    dismiss()
                },
            )
        } catch (e: Exception) {
            error = e.message ?: "Check the amounts and hours"
        }
    }
    EditorSheet("Water settings", dismiss, ::save, busy) {
        Section("Your daily goal")
        ToggleRow("Use a personal goal", useGoal) { useGoal = it }
        if (useGoal) NumberField("Daily amount · $unit", goal) { goal = it }
        Text(
            "An optional target you choose, not a recommended intake. No streaks or pressure to catch up.",
            style = MaterialTheme.typography.bodySmall,
        )
        Section("Quick-add glass")
        NumberField("Amount · $unit", glass) { glass = it }
        Text("Change units in Settings.", style = MaterialTheme.typography.bodySmall)
        Section("Water reminders")
        ToggleRow("Remind me to drink water", reminders) { reminders = it }
        if (reminders) {
            ToggleRow("Paused until you resume", paused) { paused = it }
            fun hour(n: Int) = if (n == 24) "Midnight" else "%02d:00".format(n)
            ChoiceField("From", start, (0..23).map { it.toString() to hour(it) }) { start = it }
            ChoiceField("Until", end, (1..24).map { it.toString() to hour(it) }) { end = it }
            ChoiceField(
                "Every",
                interval,
                (1..4).map { it.toString() to if (it == 1) "1 hour" else "$it hours" },
            ) {
                interval = it
            }
            if (start.toInt() >= end.toInt())
                Text("End time must be after start time.", color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Daily at your chosen hours, up to but not including the end. Android may delay delivery. Logging water does not silence your schedule.",
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val operationError by m.message.collectAsStateWithLifecycle()
        operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Native sheet with fixed actions: the form scrolls; Save and Cancel never disappear below it. */
@Composable
internal fun EditorSheet(
    title: String,
    dismiss: () -> Unit,
    save: () -> Unit,
    busy: Boolean,
    saveLabel: String = "Save",
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestDismiss by rememberUpdatedState(dismiss)
    val state =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = {
                if (it == SheetValue.Hidden) {
                    latestDismiss()
                    false
                } else true
            },
        )
    ModalBottomSheet(
        onDismissRequest = { if (!busy) dismiss() },
        sheetState = state,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding()) {
            Text(
                title,
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            HorizontalDivider()
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = save,
                    enabled = !busy,
                    modifier = Modifier.testTag("editor-save"),
                ) {
                    Text(saveLabel)
                }
            }
        }
    }
}

@Composable
internal fun ChoiceField(
    label: String,
    selected: String,
    choices: List<Pair<String, String>>,
    change: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        change(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RatingChoice(
    title: String,
    low: String,
    high: String,
    value: String,
    change: (String) -> Unit,
) {
    ChoiceField(
        title,
        value,
        listOf("" to "Not recorded") +
            (1..5).map {
                it.toString() to "$it${if(it == 1) " · $low" else if(it == 5) " · $high" else ""}"
            },
        change,
    )
}
