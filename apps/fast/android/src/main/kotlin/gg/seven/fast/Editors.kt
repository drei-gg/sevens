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
import gg.seven.kit.*
import java.time.*
import kotlin.math.roundToInt

/** A single date/time row in its own group, for screens outside a form. */
@Composable
internal fun NativeDateTime(
    label: String,
    value: Double,
    withTime: Boolean = true,
    onChange: (Double) -> Unit,
) {
    SevenGroup { DateTimeRow(label, value, withTime = withTime, onChange = onChange) }
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
    FormSheet(
        title,
        { if (!busy) if (draft == pristine) dismiss() else discard = true },
        ::save,
        busy = busy,
        saveLabel = if (type == "end") "Save" else "Save",
    ) {
        if (type in listOf("start", "past", "plan"))
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    "Choose a rhythm",
                    Modifier.padding(horizontal = Space.m),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val presets = FastPreset.defaults + a.presets
                CapsuleChoices(
                    presets.map { it to it.name },
                    presets.firstOrNull {
                        it.goalMinutes == goalMinutes && it.eatingMinutes == eatingMinutes
                    },
                ) { p ->
                    openEnded = p.goalMinutes == null
                    goal = plain((p.goalMinutes ?: 960) / 60.0)
                    eating = plain(p.eatingMinutes / 60.0)
                }
            }
        if (type != "preset")
            SevenGroup(header = if (fastLike) "Times" else null) {
                DateTimeRow(
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
                if (hasEnd) DateTimeRow("Finished", end) { end = it }
                if (hasEnd) ValueRow("Duration", duration(end - start))
                if (type == "journal")
                    MenuRow(
                        "Linked fast",
                        linkedFast.orEmpty(),
                        listOf("" to "An ordinary day") +
                            a.fasts.sortedByDescending { it.start }.map { it.id.toString() to stamp(it.start) },
                    ) {
                        linkedFast = it.ifEmpty { null }
                    }
            }
        if (type == "preset") SevenGroup { TextRow(text, "Preset name") { text = it } }
        if (fastLike)
            SevenGroup(footer = "Recording limits are not recommendations.") {
                SwitchRow("Set a goal", !openEnded) { openEnded = !it }
                if (!openEnded) NumberRow("Goal hours", goal, unit = "hr") { goal = it }
                NumberRow("Eating window hours", eating, unit = "hr") { eating = it }
            }
        if (fastLike && type != "preset")
            SevenGroup(header = "Intention") {
                TextRow(intention, "What would you like to make room for?", minLines = 2) {
                    intention = it
                }
            }
        if (hasEnd)
            SevenGroup(header = "Reflection") {
                TextRow(reflection, "What did you notice? What would you change?", minLines = 2) {
                    reflection = it
                }
            }
        if (type == "rest") SevenGroup(header = "Note") { TextRow(text, "Optional") { text = it } }
        if (type == "journal") {
            SevenGroup(header = "Observation") {
                TextRow(text, "What’s happening in your body, your day, or your thoughts?", minLines = 5) {
                    text = it
                }
            }
            SevenGroup(
                header = "Optional check-in",
                footer = "These are your observations, not a score to improve.",
            ) {
                RatingRow("Mood", "Low", "Good", mood) { mood = it }
                RatingRow("Energy", "Low", "High", energy) { energy = it }
                RatingRow("Hunger", "None", "Strong", hunger) { hunger = it }
            }
        }
        if (type in listOf("start", "past", "end", "fast", "journal"))
            SevenGroup { TextRow(tags, "Tags, separated by commas") { tags = it } }
        if (type in listOf("metric", "water")) {
            if (metric == null && type != "water") {
                val kinds = MetricKind.entries.filter { a.settings.showWeight || it != MetricKind.WEIGHT }
                if (kinds.size > 1)
                    Segmented(
                        kinds.map { it to it.key.replaceFirstChar { c -> c.uppercase() } },
                        kind,
                    ) {
                        kindText = it.key
                        value = ""
                    }
            }
            SevenGroup(
                footer =
                    if (kind == MetricKind.WATER)
                        "Log your own observations. There is no target you have to meet."
                    else null
            ) {
                NumberRow(Units.label(kind, a.settings), value) { value = it }
            }
            if (kind == MetricKind.WATER && metric == null) {
                val amounts =
                    if (a.settings.useFluidOunces) listOf(8, 12, 16) else listOf(150, 250, 500)
                CapsuleChoices(
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
                Modifier.padding(horizontal = Space.m),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (id != null)
            SevenGroup {
                ActionRow(
                    if (f?.end == null && f != null) "Discard fast" else "Delete",
                    destructive = true,
                    enabled = !busy,
                ) {
                    delete = true
                }
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
internal fun ToggleRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(label, enabled, onCheckedChange = onChange)
}

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
    val busy by m.busy.collectAsStateWithLifecycle()
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
    FormSheet("Water settings", dismiss, ::save, busy = busy) {
        SevenGroup(
            header = "Your daily goal",
            footer =
                "An optional target you choose, not a recommended intake. No streaks or pressure to catch up.",
        ) {
            SwitchRow("Use a personal goal", useGoal) { useGoal = it }
            if (useGoal) NumberRow("Daily amount", goal, unit = unit) { goal = it }
        }
        SevenGroup(
            header = "Quick-add glass",
            footer = "Amounts use $unit. Change units in Settings.",
        ) {
            NumberRow("Glass", glass, unit = unit) { glass = it }
        }
        fun hour(n: Int) =
            if (n == 24) "Midnight"
            else SevenFormat.time(java.time.LocalDate.now().atTime(n, 0).atZone(ZoneId.systemDefault()), use24Hour)
        SevenGroup(
            header = "Water reminders",
            footer =
                "Daily at your chosen hours, up to but not including the end. Android may delay delivery. Logging water does not silence your schedule.",
        ) {
            SwitchRow("Remind me to drink water", reminders) { reminders = it }
            if (reminders) {
                SwitchRow("Paused until you resume", paused) { paused = it }
                MenuRow("From", start, (0..23).map { it.toString() to hour(it) }) { start = it }
                MenuRow("Until", end, (1..24).map { it.toString() to hour(it) }) { end = it }
                MenuRow(
                    "Every",
                    interval,
                    (1..4).map { it.toString() to if (it == 1) "1 hour" else "$it hours" },
                ) {
                    interval = it
                }
            }
        }
        if (reminders && start.toInt() >= end.toInt())
            Text("End time must be after start time.", color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val operationError by m.message.collectAsStateWithLifecycle()
        operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RatingRow(
    title: String,
    low: String,
    high: String,
    value: String,
    change: (String) -> Unit,
) {
    MenuRow(
        title,
        value,
        listOf("" to "Not recorded") +
            (1..5).map {
                it.toString() to "$it${if (it == 1) " · $low" else if (it == 5) " · $high" else ""}"
            },
        onSelect = change,
    )
}
