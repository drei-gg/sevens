@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.bloom

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.SevenAbout
import gg.seven.kit.checkbox
import gg.seven.kit.switch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BloomApp(model: BloomModel) {
    val archiveText by model.archive.collectAsStateWithLifecycle()
    val dashboardText by model.dashboard.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Drafts can hold private notes: they live in the ViewModel, never the saved-state Bundle, and
    // close on lock and erase. Process death discards an unsaved draft.
    val open = model.editor
    val editor = open?.kind
    val draft = open?.text ?: ""
    var discarding by remember { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    var guides by rememberSaveable { mutableStateOf(false) }
    val archive = remember(archiveText) { archiveText?.let(::JSONObject) }
    val dashboard = remember(dashboardText) { dashboardText?.let(::JSONObject) }
    fun closeEditor() {
        if (!busy) {
            if (open != null && open.text != open.original) discarding = true
            else model.editor = null
        }
    }
    BackHandler(enabled = editor != null) { closeEditor() }
    BackHandler(enabled = editor == null && guides) { guides = false }
    if (discarding && editor != null)
        gg.seven.kit.DiscardChangesDialog(
            { discarding = false },
            {
                discarding = false
                model.editor = null
            },
        )
    val gate = LocalSevenGate.current
    val context = LocalContext.current
    val addRecord = stringResource(R.string.gate_add_record)
    val tabs =
        listOf(
            R.string.tab_today,
            R.string.tab_calendar,
            R.string.tab_care,
            R.string.tab_patterns,
            R.string.tab_settings,
        )
    val openCare by model.openCare.collectAsStateWithLifecycle()
    LaunchedEffect(openCare) {
        if (openCare) {
            tab = 2
            model.openCare.value = false
        }
    }
    fun edit(kind: String, row: JSONObject?) {
        val start = {
            val text = (row ?: newRecord(kind)).toString()
            model.editor = EditorDraft(kind, text, text, archive!!.getLong("revision"))
        }
        val key =
            when (kind) {
                "day" -> "days"
                "pregnancy" -> "pregnancies"
                else -> "${kind}s"
            }
        val exists =
            row != null &&
                archive?.rows(key)?.any { it.getString("id") == row.getString("id") } == true
        if (!exists && kind !in listOf("dose", "refill", "exclusion"))
            gate.run(addRecord, start)
        else start()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (editor != null) stringResource(editorTitle(editor))
                        else if (guides) stringResource(R.string.guides_title)
                        else stringResource(tabs[tab])
                    )
                },
                navigationIcon = {
                    if (editor != null)
                        IconButton(enabled = !busy, onClick = ::closeEditor) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                        }
                    else if (guides)
                        IconButton(onClick = { guides = false }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                stringResource(R.string.action_back),
                            )
                        }
                },
                actions = {
                    if (editor != null)
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                val row = JSONObject(draft)
                                val problem = BloomTemperature.problem(context, editor, row)
                                if (problem != null) model.error.value = problem
                                else
                                    model.save(editor, row, open!!.revision) {
                                        model.editor = null
                                    }
                            },
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
                },
            )
        },
        bottomBar = {
            if (editor == null && !guides)
                ShortNavigationBar {
                    val icons =
                        listOf(
                            Icons.Outlined.Today,
                            Icons.Outlined.CalendarMonth,
                            Icons.Outlined.Medication,
                            Icons.Outlined.BarChart,
                            Icons.Outlined.Settings,
                        )
                    tabs.forEachIndexed { i, label ->
                        ShortNavigationBarItem(
                            selected = tab == i,
                            onClick = {
                                tab = i
                                guides = false
                            },
                            icon = { Icon(icons[i], null) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .consumeWindowInsets(padding)
                .widthIn(max = 840.dp)
                .imePadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(
                    rememberSaveable(
                        tab,
                        editor,
                        saver = androidx.compose.foundation.ScrollState.Saver,
                    ) {
                        androidx.compose.foundation.ScrollState(0)
                    }
                )
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (archive == null || dashboard == null) {
                Text(
                    stringResource(
                        if (error == null) R.string.journal_opening else R.string.journal_open_failed
                    )
                )
            } else if (editor != null) {
                RecordEditor(editor, draft) { text -> model.editor = model.editor?.copy(text = text) }
            } else if (guides) {
                GuidesScreen()
            } else
                when (tab) {
                    0 -> BloomToday(model, archive, dashboard, ::edit) { guides = true }
                    1 -> BloomCalendar(model, archive, dashboard, ::edit) { remove = it }
                    2 -> BloomCare(model, archive, dashboard, ::edit) { remove = it }
                    3 -> BloomPatterns(model, archive, dashboard, ::edit) { remove = it }
                    4 -> BackupSettings(model, archive) { guides = true }
                }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (error != null)
        AlertDialog(
            onDismissRequest = { model.error.value = null },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(error!!) },
            confirmButton = {
                TextButton(onClick = { model.error.value = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    if (remove != null)
        AlertDialog(
            onDismissRequest = { remove = null },
            title = {
                Text(
                    stringResource(
                        if (remove!!.startsWith("plan:")) R.string.confirm_pause_plan
                        else R.string.confirm_delete_record
                    )
                )
            },
            text = { Text(stringResource(R.string.confirm_delete_body)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val parts = remove!!.split(":", limit = 2)
                        model.delete("delete_${parts[0]}", parts[1])
                        remove = null
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { remove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    if (preview != null)
        AlertDialog(
            onDismissRequest = { model.preview.value = null },
            title = { Text(stringResource(R.string.import_review_title)) },
            text = {
                val incoming = JSONObject(preview!!)
                val counts =
                    listOf(
                            "days" to R.plurals.import_days,
                            "observations" to R.plurals.import_observations,
                            "exclusions" to R.plurals.import_exclusions,
                            "contacts" to R.plurals.import_contacts,
                            "plans" to R.plurals.import_plans,
                            "doses" to R.plurals.import_doses,
                            "refills" to R.plurals.import_refills,
                            "pregnancies" to R.plurals.import_pregnancies,
                            "appointments" to R.plurals.import_appointments,
                        )
                        .map { (key, words) ->
                            val count = incoming.rows(key).size
                            pluralStringResource(words, count, count)
                        }
                Text(stringResource(R.string.import_review_body, listText(counts)))
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = model::confirmImport) {
                    Text(stringResource(R.string.import_merge))
                }
            },
            dismissButton = {
                TextButton(onClick = { model.preview.value = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
}

@Composable
internal fun RecordCard(title: String, subtitle: String, edit: () -> Unit, delete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle)
            Row {
                TextButton(onClick = edit) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = delete) { Text(stringResource(R.string.action_remove)) }
            }
        }
    }
}

@Composable
internal fun DoseList(model: BloomModel, archive: JSONObject, dashboard: JSONObject) {
    Text(stringResource(R.string.doses_today_title), style = MaterialTheme.typography.titleLarge)
    if (dashboard.rows("today_doses").isEmpty()) Text(stringResource(R.string.doses_none_today))
    dashboard.rows("today_doses").forEach { DoseCard(model, archive, it) }
}

/**
 * One scheduled dose with explicit Taken/Skipped. A later day's dose shows why it cannot be
 * recorded yet; snoozing is offered only on the dose's own day.
 */
@Composable
internal fun DoseCard(model: BloomModel, archive: JSONObject, dose: JSONObject) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val today = LocalDate.now().toString()
    val day = dose.getString("day")
    fun record(status: String) {
        val entry =
            JSONObject()
                .put("id", dose.getString("id"))
                .put("plan_id", dose.getString("plan_id"))
                .put("day", day)
                .put("scheduled", dose.getLong("at"))
                .put("status", status)
                .put("recorded", Instant.now().epochSecond)
                .put(
                    "snooze_until",
                    if (status == "snoozed") Instant.now().epochSecond + 600 else JSONObject.NULL,
                )
                .put("notes", "")
        model.save("dose", entry, archive.getLong("revision")) {}
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dose.getString("title"), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.dose_line,
                    timeLabel(dose.getLong("at")),
                    valueText(dose.getString("phase")),
                    valueText(dose.getString("status")),
                )
            )
            if (day > today) Muted(stringResource(R.string.dose_future))
            else {
                gg.seven.kit.ConnectedChoice(
                    listOf("taken", "skipped").map { it to valueText(it) },
                    dose.getString("status"),
                    enabled = !busy,
                    onSelect = ::record,
                )
                if (day == today && dose.getString("status") != "taken")
                    TextButton(enabled = !busy, onClick = { record("snoozed") }) {
                        Icon(Icons.Outlined.Snooze, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dose_snooze))
                    }
            }
        }
    }
}

/** Editor titles name the record, as on iOS. */
@androidx.annotation.StringRes
private fun editorTitle(kind: String): Int =
    when (kind) {
        "day" -> R.string.editor_day
        "observation" -> R.string.editor_observation
        "contact" -> R.string.editor_contact
        "plan" -> R.string.editor_plan
        "dose" -> R.string.editor_dose
        "refill" -> R.string.editor_refill
        "appointment" -> R.string.editor_appointment
        "pregnancy" -> R.string.editor_pregnancy
        else -> R.string.editor_exclusion
    }

internal fun newRecord(kind: String): JSONObject {
    val row = JSONObject().put("id", UUID.randomUUID().toString()).put("notes", "")
    val today = LocalDate.now().toString()
    return when (kind) {
        "day" ->
            row.put("day", today)
                .put("flow", "none")
                .put("period_start", false)
                .put("symptoms", JSONObject())
                .put("tags", JSONArray())
                .put("bbt", JSONObject.NULL)
                .put("mucus", "none")
                .put("ovulation_test", "none")
                .put("pregnancy_test", "none")
        "observation" ->
            row.put("day", today)
                .put("at", Instant.now().epochSecond)
                .put("zone", ZoneId.systemDefault().id)
                .put("kind", "symptom")
                .put("label", "Cramps")
                .put("value", "")
                .put("level", 1)
                .put("number", JSONObject.NULL)
                .put("period_start", false)
        "exclusion" ->
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("start", today)
                .put("end", JSONObject.NULL)
                .put("reason", "")
                .put("source", "manual")
        "refill" ->
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("day", today)
                .put("plan_id", "")
                .put("quantity", 28)
        "appointment" ->
            row.put("title", "")
                .put("at", Instant.now().epochSecond + 3600)
                .put("remind_minutes", 60)
                .put("kind", "Appointment")
        "pregnancy" ->
            row.put("start", today)
                .put("due", LocalDate.now().plusDays(280).toString())
                .put("ended", JSONObject.NULL)
                .put("outcome", "")
        "contact" ->
            row.put("day", today)
                .put("at", Instant.now().epochSecond)
                .put("activity", "Sex")
                .put("protection", "Not specified")
                .put("libido", JSONObject.NULL)
                .put("orgasm", JSONObject.NULL)
                .put("pain", JSONObject.NULL)
                .put("tags", JSONArray())
        else ->
            row.put("name", "")
                .put("method", "pill")
                .put("hormonal", true)
                .put("start", today)
                .put("end", JSONObject.NULL)
                .put("minute", 480)
                .put("zone", ZoneId.systemDefault().id)
                .put("fixed_zone", false)
                .put("pattern", "pack")
                .put("active_days", 21)
                .put("break_days", 7)
                .put("every_days", 1)
                .put("weekdays", JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
                .put("remind_break", false)
                .put("enabled", true)
                .put("initial_stock", JSONObject.NULL)
    }
}

@Composable
private fun RecordEditor(kind: String, text: String, change: (String) -> Unit) {
    val row = JSONObject(text)
    fun put(key: String, value: Any) {
        change(row.put(key, value).toString())
    }
    if (kind in listOf("observation", "exclusion", "refill", "dose", "appointment", "pregnancy")) {
        DetailEditor(kind, row, change)
    } else if (kind == "plan") {
        Field(stringResource(R.string.plan_name), row.getString("name")) { put("name", it) }
        Choice(
            stringResource(R.string.plan_method),
            row.getString("method"),
            listOf(
                "pill",
                "ring",
                "patch",
                "injection",
                "implant",
                "iud",
                "medication",
                "emergency",
            ),
        ) {
            put("method", it)
        }
        Toggle(stringResource(R.string.plan_end_date), !row.isNull("end")) {
            put("end", if (it) LocalDate.now().toString() else JSONObject.NULL)
        }
        if (!row.isNull("end"))
            DateField(stringResource(R.string.plan_ends), row.getString("end")) { put("end", it) }
        Field(
            stringResource(R.string.plan_supply),
            if (row.isNull("initial_stock")) "" else row.getString("initial_stock"),
            number = true,
        ) {
            put("initial_stock", if (it.isBlank()) JSONObject.NULL else it.toIntOrNull() ?: it)
        }
        DateField(stringResource(R.string.plan_start), row.getString("start")) { put("start", it) }
        Choice(
            stringResource(R.string.plan_schedule),
            row.getString("pattern"),
            listOf("pack", "daily", "interval", "weekly", "once"),
        ) {
            put("pattern", it)
        }
        val minute = row.getInt("minute")
        gg.seven.kit.TimeField(stringResource(R.string.plan_time), minute / 60, minute % 60) {
            hour,
            minutes ->
            put("minute", hour * 60 + minutes)
        }
        if (row.getString("pattern") == "pack") {
            Field(stringResource(R.string.plan_active_days), row.getString("active_days"), number = true) {
                put("active_days", it.toIntOrNull() ?: it)
            }
            Toggle(stringResource(R.string.plan_remind_break), row.getBoolean("remind_break")) {
                put("remind_break", it)
            }
            Field(stringResource(R.string.plan_break_days), row.getString("break_days"), number = true) {
                put("break_days", it.toIntOrNull() ?: it)
            }
        }
        if (row.getString("pattern") == "interval")
            Field(stringResource(R.string.plan_every), row.getString("every_days"), number = true) {
                put("every_days", it.toIntOrNull() ?: it)
            }
        if (row.getString("pattern") == "weekly") {
            val selected = row.getJSONArray("weekdays")
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            (1..7).forEach { day ->
                val days = (0 until selected.length()).map { selected.getInt(it) }.toMutableSet()
                Check(
                    java.time.DayOfWeek.of(if (day == 1) 7 else day - 1)
                        .getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale),
                    day in days,
                ) { on ->
                    if (on) days.add(day) else days.remove(day)
                    put("weekdays", JSONArray(days.sorted()))
                }
            }
        }
        Text(stringResource(R.string.plan_zone, row.getString("zone")))
        Toggle(stringResource(R.string.plan_fixed_zone), row.getBoolean("fixed_zone")) {
            put("fixed_zone", it)
        }
        Toggle(stringResource(R.string.plan_hormonal), row.getBoolean("hormonal")) {
            put("hormonal", it)
        }
        Toggle(stringResource(R.string.plan_enabled), row.getBoolean("enabled")) {
            put("enabled", it)
        }
    } else {
        if (kind == "day")
            DateField(stringResource(R.string.field_date), row.getString("day")) { put("day", it) }
        if (kind == "day") {
            Choice(
                stringResource(R.string.field_flow),
                row.getString("flow"),
                listOf("none", "spotting", "light", "medium", "heavy"),
            ) {
                put("flow", it)
            }
            Toggle(stringResource(R.string.day_period_start), row.getBoolean("period_start")) {
                if (it && row.getString("flow") in listOf("none", "spotting"))
                    row.put("flow", "light")
                put("period_start", it)
            }
            WellbeingFields(row, change)
        } else {
            TimestampField(stringResource(R.string.contact_time), row.getLong("at")) { at ->
                row.put("at", at)
                    .put(
                        "day",
                        Instant.ofEpochSecond(at)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString(),
                    )
                change(row.toString())
            }
            val none = stringResource(R.string.value_none)
            listOf(
                    Triple(
                        "libido",
                        R.string.contact_libido,
                        listOf(R.string.level_low, R.string.value_medium, R.string.level_high),
                    ),
                    Triple(
                        "pain",
                        R.string.contact_pain,
                        listOf(R.string.pain_mild, R.string.pain_moderate, R.string.pain_severe),
                    ),
                )
                .forEach { (key, label, words) ->
                    gg.seven.kit.OptionalChoice(
                        stringResource(label),
                        listOf(0 to none) +
                            words.mapIndexed { index, word -> index + 1 to stringResource(word) },
                        if (row.isNull(key)) null else row.getInt(key),
                    ) {
                        put(key, it ?: JSONObject.NULL)
                    }
                }
            Choice(
                stringResource(R.string.contact_orgasm),
                if (row.isNull("orgasm")) "unlogged"
                else if (row.getBoolean("orgasm")) "yes" else "no",
                listOf("unlogged", "yes", "no"),
            ) {
                put("orgasm", if (it == "unlogged") JSONObject.NULL else it == "yes")
            }
            Field(stringResource(R.string.contact_activity), row.getString("activity")) {
                put("activity", it)
            }
            Field(stringResource(R.string.contact_protection), row.getString("protection")) {
                put("protection", it)
            }
        }
    }
    if (row.has("notes"))
        OutlinedTextField(
            value = row.getString("notes"),
            onValueChange = { put("notes", it) },
            label = { Text(stringResource(R.string.field_notes)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
}

@Composable
internal fun DateField(label: String, value: String, change: (String) -> Unit) {
    gg.seven.kit.DateField(label, LocalDate.parse(value)) { change(it.toString()) }
}

@Composable
internal fun Field(
    label: String,
    value: String,
    number: Boolean = false,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                keyboardType =
                    if (number) androidx.compose.ui.text.input.KeyboardType.Decimal
                    else androidx.compose.ui.text.input.KeyboardType.Text
            ),
    )
}

@Composable
internal fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    gg.seven.kit.Section { switch(label, value, onCheckedChange = change) }
}

@Composable
internal fun Check(label: String, value: Boolean, change: (Boolean) -> Unit) {
    gg.seven.kit.Section { checkbox(label, value, onCheckedChange = change) }
}

@Composable
internal fun Choice(label: String, value: String, options: List<String>, change: (String) -> Unit) {
    val choices = options.map { it to valueText(it) }
    if (options.size <= 3 || options.all { it.toIntOrNull() != null }) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            gg.seven.kit.ConnectedChoice(choices, value, onSelect = change)
        }
    } else gg.seven.kit.DropdownField(label, value, choices, onSelect = change)
}

@Composable
private fun BackupSettings(model: BloomModel, archive: JSONObject, openGuides: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shortKeyboard =
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height <
            with(density) { 480.dp.roundToPx() } && WindowInsets.ime.getBottom(density) > 0
    val passphraseHint = stringResource(R.string.passphrase_hint)
    val passphraseMismatch = stringResource(R.string.passphrase_mismatch)
    // Passphrases stay in memory only, never in the saved-state Bundle.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var passphraseProblem by remember { mutableStateOf<String?>(null) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var encrypted by rememberSaveable { mutableStateOf(true) }
    var contacts by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var erase by rememberSaveable { mutableStateOf(false) }
    LayoutPreferences()
    JournalLockSettings(model)
    HealthFlowSettings(model)
    val profile = archive.getJSONObject("profile")
    Choice(
        stringResource(R.string.settings_mode),
        profile.getString("mode"),
        listOf("cycle", "conceive", "pregnancy", "postpartum", "perimenopause"),
    ) { mode ->
        model.save(
            "profile",
            JSONObject(profile.toString()).put("mode", mode),
            archive.getLong("revision"),
        ) {}
    }
    Toggle(stringResource(R.string.settings_lactating), profile.getBoolean("lactating")) { on ->
        model.save(
            "profile",
            JSONObject(profile.toString()).put("lactating", on),
            archive.getLong("revision"),
        ) {}
    }
    // The same ranges as iOS and the core's profile validation.
    listOf(
            Triple("typical_cycle", R.string.settings_usual_cycle, 15..90),
            Triple("typical_period", R.string.settings_usual_period, 1..20),
        )
        .forEach { (key, label, range) ->
            gg.seven.kit.DropdownField(
                stringResource(label),
                profile.getInt(key),
                range.map { it to pluralStringResource(R.plurals.days_count, it, it) },
            ) { days ->
                model.save(
                    "profile",
                    JSONObject(profile.toString()).put(key, days),
                    archive.getLong("revision"),
                ) {}
            }
        }
    Muted(stringResource(R.string.settings_usual_note))
    listOf(
            "discreet" to R.string.settings_discreet,
            "period_reminder" to R.string.settings_period_reminder,
            "widgets_enabled" to R.string.settings_widgets,
        )
        .plus(
            if (BuildConfig.WEAR_ENABLED) listOf("watch_enabled" to R.string.settings_watch)
            else emptyList()
        )
        .forEach { (key, label) ->
            Toggle(stringResource(label), profile.getBoolean(key)) {
                model.save(
                    "profile",
                    JSONObject(profile.toString()).put(key, it),
                    archive.getLong("revision"),
                ) {}
            }
        }
    gg.seven.kit.HomeWidgetSetup(
        android.content.ComponentName(LocalContext.current, BloomWidget::class.java),
        stringResource(R.string.widget_name),
        stringResource(R.string.settings_widget_setup),
        enabled = profile.getBoolean("widgets_enabled"),
    )
    Text(stringResource(R.string.settings_device_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.settings_device_body))
    Text(stringResource(R.string.settings_exports_body))
    // Keep the complete field reachable in a short window above the IME. The guidance remains
    // in the scrollable content instead of making the field taller than the available viewport.
    if (shortKeyboard) Text(passphraseHint, style = MaterialTheme.typography.bodySmall)
    TextField(
        password,
        {
            password = it
            passphraseProblem = null
        },
        label = { Text(stringResource(R.string.passphrase)) },
        singleLine = true,
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        supportingText = if (shortKeyboard) null else ({ Text(passphraseHint) }),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    if (passwordVisible) Icons.Outlined.VisibilityOff
                    else Icons.Outlined.Visibility,
                    stringResource(
                        if (passwordVisible) R.string.passphrase_hide else R.string.passphrase_show
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    TextField(
        confirmation,
        {
            confirmation = it
            passphraseProblem = null
        },
        label = { Text(stringResource(R.string.passphrase_repeat)) },
        singleLine = true,
        isError = passphraseProblem != null,
        supportingText = passphraseProblem?.let { { Text(it) } },
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
    Check(stringResource(R.string.csv_contacts), contacts) { contacts = it }
    Check(stringResource(R.string.csv_notes), notes) { notes = it }
    listOf(
            "age" to R.string.export_age,
            "json" to R.string.export_json,
            "csv" to R.string.export_csv,
        )
        .forEach { (kind, label) ->
            OutlinedButton(
                onClick = {
                    // Check the passphrase before the system picker creates a file.
                    passphraseProblem =
                        when {
                            kind != "age" -> null
                            password.codePointCount(0, password.length) < 12 -> passphraseHint
                            password != confirmation -> passphraseMismatch
                            else -> null
                        }
                    // The export is prepared (sealed) before the picker opens.
                    if (passphraseProblem == null) model.exportFile(kind, password, contacts, notes)
                }
            ) {
                Text(stringResource(label))
            }
        }
    Toggle(stringResource(R.string.import_encrypted), encrypted) { encrypted = it }
    // The picker's answer is handled by the Activity, so locking meanwhile does not lose it.
    OutlinedButton(onClick = { model.requestImport(if (encrypted) password else null) }) {
        Text(stringResource(R.string.import_choose))
    }
    Text(stringResource(R.string.settings_transfer_note))
    TextButton(onClick = openGuides) { Text(stringResource(R.string.guides_title)) }
    OutlinedButton(onClick = { erase = true }) { Text(stringResource(R.string.erase_action)) }
    SevenAbout()
    if (erase)
        AlertDialog(
            onDismissRequest = { erase = false },
            title = { Text(stringResource(R.string.erase_title)) },
            text = { Text(stringResource(R.string.erase_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.erase()
                        erase = false
                    }
                ) {
                    Text(stringResource(R.string.erase_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { erase = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
}

private val guideSources =
    listOf(
        R.string.guide_periods to
            "https://www.nhs.uk/conditions/periods/fertility-in-the-menstrual-cycle/",
        R.string.guide_problems to "https://www.nhs.uk/conditions/periods/period-problems/",
        R.string.guide_missed to "https://www.nhs.uk/symptoms/missed-or-late-periods/",
        R.string.guide_tests to
            "https://www.nhs.uk/pregnancy/trying-for-a-baby/doing-a-pregnancy-test/",
        R.string.guide_perimenopause to
            "https://www.nhs.uk/conditions/menopause-and-perimenopause/symptoms/",
    )

/** The same guidance and independent NHS sources as iOS; links open in the browser. */
@Composable
private fun GuidesScreen() {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    SectionTitle(stringResource(R.string.guides_records))
    Text(stringResource(R.string.guides_cycle_day))
    Text(stringResource(R.string.guides_estimates))
    Text(stringResource(R.string.guides_reports))
    SectionTitle(stringResource(R.string.guides_sources))
    guideSources.forEach { (label, link) ->
        TextButton(onClick = { uri.openUri(link) }) {
            Text(stringResource(label), Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                stringResource(R.string.guides_opens_browser),
            )
        }
    }
    SectionTitle(stringResource(R.string.guides_medication))
    Text(stringResource(R.string.guides_medication_body))
    SectionTitle(stringResource(R.string.guides_private))
    Text(stringResource(R.string.guides_private_body))
}
