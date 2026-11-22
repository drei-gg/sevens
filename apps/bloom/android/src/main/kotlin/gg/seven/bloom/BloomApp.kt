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
            gate.run("Add a record", start)
        else start()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (editor != null) editorTitle(editor)
                        else if (guides) "Guides and sources"
                        else listOf("Today", "Calendar", "Care", "Patterns", "Settings")[tab]
                    )
                },
                navigationIcon = {
                    if (editor != null)
                        IconButton(enabled = !busy, onClick = ::closeEditor) {
                            Icon(Icons.Outlined.Close, "Close")
                        }
                    else if (guides)
                        IconButton(onClick = { guides = false }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                        }
                },
                actions = {
                    if (editor != null)
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                val row = JSONObject(draft)
                                val problem = BloomTemperature.problem(editor, row)
                                if (problem != null) model.error.value = problem
                                else
                                    model.save(editor, row, open!!.revision) {
                                        model.editor = null
                                    }
                            },
                        ) {
                            Text("Save")
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
                    listOf("Today", "Calendar", "Care", "Patterns", "Settings").forEachIndexed {
                        i,
                        label ->
                        ShortNavigationBarItem(
                            selected = tab == i,
                            onClick = {
                                tab = i
                                guides = false
                            },
                            icon = { Icon(icons[i], null) },
                            label = { Text(label) },
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
                    if (error == null) "Opening your journal…"
                    else "Your journal could not be opened. Existing data has not been changed."
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
            title = { Text("Could not complete") },
            text = { Text(error!!) },
            confirmButton = { TextButton(onClick = { model.error.value = null }) { Text("OK") } },
        )
    if (remove != null)
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text(if (remove!!.startsWith("plan:")) "Pause plan?" else "Delete record?") },
            text = {
                Text(
                    "This changes your local journal. Export a backup first if you need to keep the current record."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val parts = remove!!.split(":", limit = 2)
                        model.delete("delete_${parts[0]}", parts[1])
                        remove = null
                    },
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } },
        )
    if (preview != null)
        AlertDialog(
            onDismissRequest = { model.preview.value = null },
            title = { Text("Review backup") },
            text = {
                val incoming = JSONObject(preview!!)
                fun count(key: String) = incoming.rows(key).size
                Text(
                    "${count("days")} journal days, ${count("observations")} timed observations, ${count("exclusions")} exclusions, ${count("contacts")} contacts, ${count("plans")} plans, ${count("doses")} dose records, ${count("refills")} refills, ${count("pregnancies")} pregnancies, ${count("appointments")} appointments. Merge keeps existing records and rejects conflicting IDs. Imported plans and appointment reminders start inactive. Your current preferences stay in place."
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = model::confirmImport) { Text("Merge backup") }
            },
            dismissButton = {
                TextButton(onClick = { model.preview.value = null }) { Text("Cancel") }
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
                TextButton(onClick = edit) { Text("Edit") }
                TextButton(onClick = delete) { Text("Remove") }
            }
        }
    }
}

@Composable
internal fun DoseList(model: BloomModel, archive: JSONObject, dashboard: JSONObject) {
    Text("Today’s doses", style = MaterialTheme.typography.titleLarge)
    if (dashboard.rows("today_doses").isEmpty()) Text("No doses scheduled today.")
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
                "${timeLabel(dose.getLong("at"))} · ${dose.getString("phase").replaceFirstChar(Char::uppercase)} · ${dose.getString("status").replaceFirstChar(Char::uppercase)}"
            )
            if (day > today) Muted("You can record this dose on its scheduled day.")
            else {
                gg.seven.kit.ConnectedChoice(
                    listOf("taken" to "Taken", "skipped" to "Skipped"),
                    dose.getString("status"),
                    enabled = !busy,
                    onSelect = ::record,
                )
                if (day == today && dose.getString("status") != "taken")
                    TextButton(enabled = !busy, onClick = { record("snoozed") }) {
                        Icon(Icons.Outlined.Snooze, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Snooze 10 min")
                    }
            }
        }
    }
}

/** Editor titles name the record, as on iOS. */
private fun editorTitle(kind: String): String =
    when (kind) {
        "day" -> "Daily journal"
        "observation" -> "Timed observation"
        "contact" -> "Sex and contact"
        "plan" -> "Reminder plan"
        "dose" -> "Dose record"
        "refill" -> "Record refill"
        "appointment" -> "Appointment"
        "pregnancy" -> "Pregnancy record"
        "exclusion" -> "Estimate exclusion"
        else -> kind.replaceFirstChar(Char::uppercase)
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
        Field("Plan name", row.getString("name")) { put("name", it) }
        Choice(
            "Method",
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
        Toggle("End date", !row.isNull("end")) {
            put("end", if (it) LocalDate.now().toString() else JSONObject.NULL)
        }
        if (!row.isNull("end")) DateField("Ends", row.getString("end")) { put("end", it) }
        Field(
            "Starting supply (optional)",
            if (row.isNull("initial_stock")) "" else row.getString("initial_stock"),
            number = true,
        ) {
            put("initial_stock", if (it.isBlank()) JSONObject.NULL else it.toIntOrNull() ?: it)
        }
        DateField("Start date", row.getString("start")) { put("start", it) }
        Choice(
            "Schedule",
            row.getString("pattern"),
            listOf("pack", "daily", "interval", "weekly", "once"),
        ) {
            put("pattern", it)
        }
        val minute = row.getInt("minute")
        gg.seven.kit.TimeField("Scheduled time", minute / 60, minute % 60) { hour, minutes ->
            put("minute", hour * 60 + minutes)
        }
        if (row.getString("pattern") == "pack") {
            Field("Active days", row.getString("active_days"), number = true) {
                put("active_days", it.toIntOrNull() ?: it)
            }
            Toggle("Remind during break/placebo", row.getBoolean("remind_break")) {
                put("remind_break", it)
            }
            Field("Break days", row.getString("break_days"), number = true) {
                put("break_days", it.toIntOrNull() ?: it)
            }
        }
        if (row.getString("pattern") == "interval")
            Field("Every N days", row.getString("every_days"), number = true) {
                put("every_days", it.toIntOrNull() ?: it)
            }
        if (row.getString("pattern") == "weekly") {
            val selected = row.getJSONArray("weekdays")
            (1..7).forEach { day ->
                val days = (0 until selected.length()).map { selected.getInt(it) }.toMutableSet()
                Check(
                    java.time.DayOfWeek.of(if (day == 1) 7 else day - 1)
                        .toString()
                        .lowercase()
                        .replaceFirstChar(Char::uppercase),
                    day in days,
                ) { on ->
                    if (on) days.add(day) else days.remove(day)
                    put("weekdays", JSONArray(days.sorted()))
                }
            }
        }
        Text("Time zone: ${row.getString("zone")}")
        Toggle("Keep this time zone when travelling", row.getBoolean("fixed_zone")) {
            put("fixed_zone", it)
        }
        Toggle("Hormonal contraception", row.getBoolean("hormonal")) { put("hormonal", it) }
        Toggle("Plan active", row.getBoolean("enabled")) { put("enabled", it) }
    } else {
        if (kind == "day") DateField("Date", row.getString("day")) { put("day", it) }
        if (kind == "day") {
            Choice(
                "Flow",
                row.getString("flow"),
                listOf("none", "spotting", "light", "medium", "heavy"),
            ) {
                put("flow", it)
            }
            Toggle("Period starts today", row.getBoolean("period_start")) {
                if (it && row.getString("flow") in listOf("none", "spotting"))
                    row.put("flow", "light")
                put("period_start", it)
            }
            WellbeingFields(row, change)
        } else {
            TimestampField("Contact time", row.getLong("at")) { at ->
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
            listOf(
                    "libido" to listOf(0 to "None", 1 to "Low", 2 to "Medium", 3 to "High"),
                    "pain" to listOf(0 to "None", 1 to "Mild", 2 to "Moderate", 3 to "Severe"),
                )
                .forEach { (key, levels) ->
                    gg.seven.kit.OptionalChoice(
                        key.replaceFirstChar(Char::uppercase),
                        levels,
                        if (row.isNull(key)) null else row.getInt(key),
                    ) {
                        put(key, it ?: JSONObject.NULL)
                    }
                }
            Choice(
                "Orgasm",
                if (row.isNull("orgasm")) "Unlogged"
                else if (row.getBoolean("orgasm")) "Yes" else "No",
                listOf("Unlogged", "Yes", "No"),
            ) {
                put("orgasm", if (it == "Unlogged") JSONObject.NULL else it == "Yes")
            }
            Field("Activity", row.getString("activity")) { put("activity", it) }
            Field("Protection", row.getString("protection")) { put("protection", it) }
        }
    }
    if (row.has("notes"))
        OutlinedTextField(
            value = row.getString("notes"),
            onValueChange = { put("notes", it) },
            label = { Text("Notes") },
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
    val choices =
        options.map {
            it to it.ifBlank { "Not recorded" }.replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
    if (options.size <= 3 || options.all { it.toIntOrNull() != null || it == "Unlogged" }) {
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
    val passphraseHint = "Use at least 12 characters for an encrypted backup."
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
        "Mode",
        profile.getString("mode"),
        listOf("cycle", "conceive", "pregnancy", "postpartum", "perimenopause"),
    ) { mode ->
        model.save(
            "profile",
            JSONObject(profile.toString()).put("mode", mode),
            archive.getLong("revision"),
        ) {}
    }
    Toggle("Lactating", profile.getBoolean("lactating")) { on ->
        model.save(
            "profile",
            JSONObject(profile.toString()).put("lactating", on),
            archive.getLong("revision"),
        ) {}
    }
    // The same ranges as iOS and the core's profile validation.
    listOf(
            Triple("typical_cycle", "Usual cycle", 15..90),
            Triple("typical_period", "Usual period", 1..20),
        )
        .forEach { (key, label, range) ->
            gg.seven.kit.DropdownField(
                label,
                profile.getInt(key),
                range.map { it to if (it == 1) "1 day" else "$it days" },
            ) { days ->
                model.save(
                    "profile",
                    JSONObject(profile.toString()).put(key, days),
                    archive.getLong("revision"),
                ) {}
            }
        }
    Muted(
        "Estimates use your recorded cycles first; the usual cycle fills in until there is enough history."
    )
    listOf(
            "discreet" to "Discreet reminders and widgets",
            "period_reminder" to "Estimated period reminder",
            "widgets_enabled" to "Enable home-screen widget",
        )
        .plus(if (BuildConfig.WEAR_ENABLED) listOf("watch_enabled" to "Enable watch companion") else emptyList())
        .forEach { (key, label) ->
            Toggle(label, profile.getBoolean(key)) {
                model.save(
                    "profile",
                    JSONObject(profile.toString()).put(key, it),
                    archive.getLong("revision"),
                ) {}
            }
        }
    gg.seven.kit.HomeWidgetSetup(
        android.content.ComponentName(LocalContext.current, BloomWidget::class.java),
        "Your day",
        "Shows a reminder time only. Turn off discreet mode to also show the cycle day. Journal text and contact records never appear.",
        enabled = profile.getBoolean("widgets_enabled"),
    )
    Text("Your data stays on this device", style = MaterialTheme.typography.titleLarge)
    Text(
        "Encrypted with an Android Keystore key. System cloud and device-transfer backups are excluded. Export a portable backup before changing devices."
    )
    Text(
        "Encrypted backups and JSON include all records, including sex/contact and notes. CSV includes them only when selected."
    )
    // Keep the complete field reachable in a short window above the IME. The guidance remains
    // in the scrollable content instead of making the field taller than the available viewport.
    if (shortKeyboard) Text(passphraseHint, style = MaterialTheme.typography.bodySmall)
    TextField(
        password,
        {
            password = it
            passphraseProblem = null
        },
        label = { Text("Backup passphrase") },
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
                    if (passwordVisible) "Hide backup passphrase" else "Show backup passphrase",
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
        label = { Text("Repeat backup passphrase") },
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
    Check("Include contacts in CSV", contacts) { contacts = it }
    Check("Include notes in CSV", notes) { notes = it }
    listOf("age" to "Encrypted backup", "json" to "Full JSON export", "csv" to "CSV export")
        .forEach { (kind, label) ->
            OutlinedButton(
                onClick = {
                    // Check the passphrase before the system picker creates a file.
                    passphraseProblem =
                        when {
                            kind != "age" -> null
                            password.codePointCount(0, password.length) < 12 -> passphraseHint
                            password != confirmation -> "The passphrases do not match."
                            else -> null
                        }
                    // The export is prepared (sealed) before the picker opens.
                    if (passphraseProblem == null) model.exportFile(kind, password, contacts, notes)
                }
            ) {
                Text(label)
            }
        }
    Toggle("Import is encrypted", encrypted) { encrypted = it }
    // The picker's answer is handled by the Activity, so locking meanwhile does not lose it.
    OutlinedButton(onClick = { model.requestImport(if (encrypted) password else null) }) {
        Text("Choose backup to review")
    }
    Text(
        "Health Connect transfer is manual and permission-based. Care reminders use Android notifications and your alarm permissions."
    )
    TextButton(onClick = openGuides) { Text("Guides and sources") }
    OutlinedButton(onClick = { erase = true }) { Text("Erase local journal") }
    SevenAbout()
    if (erase)
        AlertDialog(
            onDismissRequest = { erase = false },
            title = { Text("Erase this journal?") },
            text = {
                Text(
                    "All local records will be removed. Export a backup first if you want to recover them."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.erase()
                        erase = false
                    }
                ) {
                    Text("Erase")
                }
            },
            dismissButton = { TextButton(onClick = { erase = false }) { Text("Cancel") } },
        )
}

private val guideSources =
    listOf(
        "NHS · Periods and the menstrual cycle" to
            "https://www.nhs.uk/conditions/periods/fertility-in-the-menstrual-cycle/",
        "NHS · Period problems and when to seek care" to
            "https://www.nhs.uk/conditions/periods/period-problems/",
        "NHS · Missed or late periods" to "https://www.nhs.uk/symptoms/missed-or-late-periods/",
        "NHS · Pregnancy tests" to
            "https://www.nhs.uk/pregnancy/trying-for-a-baby/doing-a-pregnancy-test/",
        "NHS · Perimenopause symptoms" to
            "https://www.nhs.uk/conditions/menopause-and-perimenopause/symptoms/",
    )

/** The same guidance and independent NHS sources as iOS; links open in the browser. */
@Composable
private fun GuidesScreen() {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    SectionTitle("Know your records")
    Text(
        "Cycle day starts with the first day of a period. Log a new period start when needed; spotting is recorded separately. Missing days are unknown, not symptom-free days."
    )
    Text(
        "Your estimates use recent logged cycles. Variable cycles, hormonal contraception and life-stage changes make calendar predictions less useful. No date in this app can establish contraceptive protection."
    )
    Text(
        "Use symptom and dose reports to prepare for a conversation with a clinician. This app does not assess PCOS, endometriosis, pregnancy health or menopause."
    )
    SectionTitle("Read the source")
    guideSources.forEach { (label, link) ->
        TextButton(onClick = { uri.openUri(link) }) {
            Text(label, Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Opens in your browser")
        }
    }
    SectionTitle("Medication reminders")
    Text(
        "Use the medicine leaflet or your clinician’s instructions to configure your schedule. Follow the leaflet or ask a pharmacist about missed doses, interactions or a change of time zone. Reminders record your choices; they do not prescribe a dose."
    )
    SectionTitle("Private lives")
    Text(
        "Each person keeps a separate journal on their own device. There is no family dashboard, parent access or automatic partner sharing."
    )
}
