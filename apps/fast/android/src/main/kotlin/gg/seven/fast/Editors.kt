@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import gg.seven.kit.*
import java.time.*
import kotlin.math.roundToInt

/** A date (and time) field outside a form. */
@Composable
internal fun NativeDateTime(
    label: String,
    value: Double,
    withTime: Boolean = true,
    onChange: (Double) -> Unit,
) {
    DateTimeField(label, value, withTime = withTime, onChange = onChange)
}

/** A switch with its label; the whole line toggles. */
@Composable
internal fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Aligned with the fields around it: a form line, not an inset list row.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A form value: no trailing ".0", "." as the separator so parsing stays locale-proof. */
internal fun plain(value: Double): String =
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

internal fun String.decimal(): Double? = trim().replace(',', '.').toDoubleOrNull()

@Composable
internal fun EditorDialog(
    m: FastModel,
    a: FastArchive,
    type: String,
    id: java.util.UUID?,
    initialDate: Double? = null,
    initialFastId: java.util.UUID? = null,
    dismiss: () -> Unit,
) {
    val busy by m.working.collectAsStateWithLifecycle()
    val f = a.fasts.find { it.id == id }
    val j = a.journal.find { it.id == id }
    val metric = a.metrics.find { it.id == id }
    val plan = a.plans.find { it.id == id }
    val rest = a.restDays.find { it.id == id }
    val preset = a.presets.find { it.id == id }
    val baseNow = remember { initialDate ?: nowMillis() }
    var start by rememberSaveable {
        mutableDoubleStateOf(
            (f?.start ?: j?.date ?: metric?.date ?: plan?.start ?: rest?.date)?.millis()
                ?: if (type == "past") baseNow - 16 * 3_600_000
                else if (type == "plan") baseNow + 86_400_000 else baseNow
        )
    }
    var end by rememberSaveable { mutableDoubleStateOf(f?.end?.millis() ?: baseNow) }
    var openEnded by rememberSaveable {
        mutableStateOf(
            if (f != null) f.goalMinutes == null
            else if (plan != null) plan.goalMinutes == null
            else if (preset != null) preset.goalMinutes == null else false
        )
    }
    var goal by rememberSaveable {
        mutableStateOf(
            plain((f?.goalMinutes ?: plan?.goalMinutes ?: preset?.goalMinutes ?: 960) / 60.0)
        )
    }
    var eating by rememberSaveable {
        mutableStateOf(
            plain((f?.eatingMinutes ?: plan?.eatingMinutes ?: preset?.eatingMinutes ?: 480) / 60.0)
        )
    }
    var intention by rememberSaveable { mutableStateOf(f?.intention ?: plan?.intention ?: "") }
    var reflection by rememberSaveable { mutableStateOf(f?.reflection ?: "") }
    var text by rememberSaveable { mutableStateOf(j?.text ?: rest?.note ?: preset?.name ?: "") }
    var tags by rememberSaveable {
        mutableStateOf((f?.tags ?: j?.tags).orEmpty().joinToString(", "))
    }
    var linkedFast by rememberSaveable {
        mutableStateOf((if (j == null) initialFastId ?: a.active?.id else j.fastId)?.toString())
    }
    var mood by rememberSaveable { mutableStateOf(j?.mood?.toString().orEmpty()) }
    var energy by rememberSaveable { mutableStateOf(j?.energy?.toString().orEmpty()) }
    var hunger by rememberSaveable { mutableStateOf(j?.hunger?.toString().orEmpty()) }
    var kindText by rememberSaveable {
        mutableStateOf(metric?.kind?.key ?: if (type == "water") "water" else "weight")
    }
    val kind = metricKind(kindText)
    var value by rememberSaveable {
        mutableStateOf(
            metric?.let { plain(Units.display(it.value, it.kind, a.settings)) }
                ?: if (type == "water")
                    plain(
                        Units.display(
                            a.settings.water.quickAddMilliliters,
                            MetricKind.WATER,
                            a.settings,
                        )
                    )
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
            "journal" -> if (j == null) "Check in" else "Journal entry"
            "water" -> "Water entry"
            "metric" -> "Measurement"
            "plan" -> "Plan a fast"
            "rest" -> "Rest day"
            "preset" -> "Custom preset"
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
                    else ((goal.decimal() ?: error("Enter goal hours")) * 60).roundToInt()
                } else null
            val eat =
                if (type in listOf("start", "past", "end", "fast", "plan", "preset"))
                    ((eating.decimal() ?: error("Enter eating window hours")) * 60)
                        .roundToInt()
                else 480
            val newMetric =
                if (type in listOf("metric", "water"))
                    Units.canonical(
                        value.decimal() ?: error("Enter an amount"),
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
                                fastRecord(
                                    id = f?.id ?: newId(),
                                    start = start,
                                    end =
                                        if (type == "past" || type == "end" || f?.end != null) end
                                        else null,
                                    goalMinutes = newGoal,
                                    eatingMinutes = eat,
                                    timeZoneId = f?.timeZoneId ?: ZoneId.systemDefault().id,
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
                                    start.instant(),
                                    linkedFast?.let(java.util.UUID::fromString),
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
                                    start.instant(),
                                    kind,
                                    newMetric,
                                    metric?.externalId,
                                )
                            current.copy(
                                metrics = current.metrics.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "plan" -> {
                            val entry =
                                PlannedFast(plan?.id ?: newId(), start.instant(), newGoal, eat, intention)
                            current.copy(
                                plans = current.plans.filterNot { it.id == entry.id } + entry
                            )
                        }
                        "rest" -> {
                            val entry = RestDay(rest?.id ?: newId(), start.instant(), text)
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
            localError = e.explanation
        }
    }
    val draft =
        listOf(start, end, openEnded, goal, eating, intention, reflection, text, tags, linkedFast, mood, energy, hunger, kindText, value)
            .joinToString("\u0000")
    val pristine = rememberSaveable { draft }
    val fastLike = type in listOf("start", "past", "end", "fast", "plan", "preset")
    val hasEnd = type == "past" || type == "end" || f?.end != null
    val goalMinutes = if (openEnded) null else goal.decimal()?.times(60)?.roundToInt()
    val eatingMinutes = eating.decimal()?.times(60)?.roundToInt()
    FullScreenForm(
        title,
        { if (!busy) if (draft == pristine) dismiss() else discard = true },
        ::save,
        busy = busy,
    ) {
        if (type in listOf("start", "past", "plan")) {
            FormHeading("Rhythm")
            val presets = FastPreset.defaults + a.presets
            ChoiceChips(
                presets.map { it to it.name },
                presets.firstOrNull { it.goalMinutes == goalMinutes && it.eatingMinutes == eatingMinutes },
            ) { p ->
                openEnded = p.goalMinutes == null
                goal = plain((p.goalMinutes ?: 960) / 60.0)
                eating = plain(p.eatingMinutes / 60.0)
            }
        }
        if (type == "preset") TextInput("Preset name", text) { text = it }
        if (type != "preset") {
            DateTimeField(
                when {
                    type == "plan" -> "Planned start"
                    fastLike -> "Started"
                    type == "rest" -> "Day"
                    else -> "When"
                },
                start,
                withTime = type != "rest",
            ) {
                start = it
            }
            if (hasEnd) {
                DateTimeField("Finished", end) { end = it }
                Text(
                    "Duration: ${duration(end - start)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (fastLike) {
            SwitchLine("Set a goal", !openEnded) { openEnded = !it }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (!openEnded)
                    NumberField("Goal", goal, Modifier.weight(1f), suffix = "hr") { goal = it }
                NumberField("Eating window", eating, Modifier.weight(1f), suffix = "hr") { eating = it }
            }
            Text(
                "Recording limits are not recommendations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fastLike && type != "preset")
            TextInput("Intention", intention, minLines = 2, supporting = "What would you like to make room for?") {
                intention = it
            }
        if (hasEnd)
            TextInput("Reflection", reflection, minLines = 2, supporting = "What did you notice? What would you change?") {
                reflection = it
            }
        if (type == "rest") TextInput("Note (optional)", text) { text = it }
        if (type == "journal") {
            DropdownField(
                "Linked fast",
                linkedFast.orEmpty(),
                listOf("" to "An ordinary day") +
                    a.fasts.sortedByDescending { it.start }.map { it.id.toString() to stamp(it.start) },
            ) {
                linkedFast = it.ifEmpty { null }
            }
            TextInput(
                "Observation",
                text,
                minLines = 5,
                supporting = "What’s happening in your body, your day, or your thoughts?",
            ) {
                text = it
            }
            FormHeading("Optional check-in")
            RatingField("Mood", "Low", "Good", mood) { mood = it }
            RatingField("Energy", "Low", "High", energy) { energy = it }
            RatingField("Hunger", "None", "Strong", hunger) { hunger = it }
            Text(
                "These are your observations, not a score to improve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (type in listOf("start", "past", "end", "fast", "journal"))
            TextInput("Tags", tags, supporting = "Separate with commas") { tags = it }
        if (type in listOf("metric", "water")) {
            if (metric == null && type != "water") {
                val kinds = MetricKind.entries.filter { a.settings.showWeight || it != MetricKind.WEIGHT }
                if (kinds.size > 1)
                    ConnectedChoice(kinds.map { it to it.key.replaceFirstChar { c -> c.uppercase() } }, kind) {
                        kindText = it.key
                        value = ""
                    }
            }
            NumberField(
                "Amount",
                value,
                suffix = Units.label(kind, a.settings),
                supporting =
                    if (kind == MetricKind.WATER) "Log your own observations. There is no target you have to meet."
                    else null,
            ) {
                value = it
            }
            if (kind == MetricKind.WATER && metric == null) {
                val amounts = if (a.settings.useFluidOunces) listOf(8, 12, 16) else listOf(150, 250, 500)
                ChoiceChips(
                    amounts.map { it to "$it ${Units.label(kind, a.settings)}" },
                    amounts.firstOrNull { value.decimal() == it.toDouble() },
                ) {
                    value = it.toString()
                }
            }
        }
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val operationError by m.message.collectAsStateWithLifecycle()
        operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (type == "past" || type == "start")
            Text(
                "An early finish is still a record. This timer does not measure ketosis, autophagy or fat burning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (id != null)
            OutlinedButton(
                onClick = { delete = true },
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (f?.end == null && f != null) "Discard fast" else "Delete")
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
internal fun ToggleRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) = SwitchLine(label, enabled, onChange)

@Composable
private fun WaterSettingsDialog(m: FastModel, a: FastArchive, dismiss: () -> Unit) {
    val s = a.settings.water
    val unit = Units.label(MetricKind.WATER, a.settings)
    fun display(value: Double) = plain(Units.display(value, MetricKind.WATER, a.settings))
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
    val busy by m.working.collectAsStateWithLifecycle()
    fun save() {
        try {
            fun amount(raw: String) =
                Units.canonical(raw.decimal() ?: error("Enter an amount"), MetricKind.WATER, a.settings)
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
            error = e.explanation
        }
    }
    FullScreenForm("Water settings", dismiss, ::save, busy = busy) {
        FormHeading("Your daily goal")
        SwitchLine("Use a personal goal", useGoal) { useGoal = it }
        if (useGoal) NumberField("Daily amount", goal, suffix = unit) { goal = it }
        Text(
            "An optional target you choose, not a recommended intake. No streaks or pressure to catch up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormHeading("Quick-add glass")
        NumberField("Glass", glass, suffix = unit, supporting = "Change units in Settings.") { glass = it }
        fun hour(n: Int) =
            if (n == 24) "Midnight"
            else SevenFormat.time(java.time.LocalDate.now().atTime(n, 0).atZone(ZoneId.systemDefault()), use24Hour)
        FormHeading("Water reminders")
        SwitchLine("Remind me to drink water", reminders) { reminders = it }
        if (reminders) {
            SwitchLine("Paused until you resume", paused) { paused = it }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                DropdownField("From", start, (0..23).map { it.toString() to hour(it) }, Modifier.weight(1f)) { start = it }
                DropdownField("Until", end, (1..24).map { it.toString() to hour(it) }, Modifier.weight(1f)) { end = it }
            }
            DropdownField("Every", interval, (1..4).map { it.toString() to if (it == 1) "1 hour" else "$it hours" }) {
                interval = it
            }
        }
        Text(
            "Daily at your chosen hours, up to but not including the end. Android may delay delivery. Logging water does not silence your schedule.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reminders && start.toInt() >= end.toInt())
            Text("End time must be after start time.", color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val operationError by m.message.collectAsStateWithLifecycle()
        operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** A 1–5 rating as a connected button group; tapping the selected value clears it. */
@Composable
private fun RatingField(title: String, low: String, high: String, value: String, change: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                "1 $low · 5 $high",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            (1..5).forEach { n ->
                ToggleButton(
                    checked = value == n.toString(),
                    onCheckedChange = { change(if (it) n.toString() else "") },
                    shapes =
                        when (n) {
                            1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            5 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "$title $n" },
                ) {
                    Text("$n")
                }
            }
        }
    }
}
