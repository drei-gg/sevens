package gg.seven.bloom

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.SevenAbout
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
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var revision by rememberSaveable { mutableLongStateOf(0) }
    var remove by rememberSaveable { mutableStateOf<String?>(null) }
    val archive = remember(archiveText) { archiveText?.let(::JSONObject) }
    val dashboard = remember(dashboardText) { dashboardText?.let(::JSONObject) }
    BackHandler(enabled = editor != null) { editor = null }
    val gate = LocalSevenGate.current
    fun edit(kind: String, row: JSONObject?) {
        val open = {
            draft = (row ?: newRecord(kind)).toString()
            revision = archive!!.getLong("revision")
            editor = kind
        }
        if (row == null) gate.run("Add a record", open) else open()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (editor != null) "Edit ${editor}"
                        else listOf("Today", "Journal", "Care", "Insights", "Settings")[tab]
                    )
                }
            )
        },
        bottomBar = {
            if (editor == null)
                NavigationBar {
                    val icons =
                        listOf(
                            Icons.Outlined.Today,
                            Icons.Outlined.EditNote,
                            Icons.Outlined.Medication,
                            Icons.Outlined.BarChart,
                            Icons.Outlined.Settings,
                        )
                    listOf("Today", "Journal", "Care", "Insights", "Settings").forEachIndexed {
                        i,
                        label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icons[i], null) },
                            label = { Text(label) },
                        )
                    }
                }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
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
                RecordEditor(editor!!, draft, { draft = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            model.save(editor!!, JSONObject(draft), revision) { editor = null }
                        },
                    ) {
                        Text("Save")
                    }
                    TextButton(onClick = { editor = null }) { Text("Cancel") }
                }
            } else
                when (tab) {
                    0 -> {
                        Text(
                            dashboard.getString("today"),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(dashboard.getString("prediction_note"))
                        if (!dashboard.isNull("next_period"))
                            Text("Estimated next period: ${dashboard.getString("next_period")}")
                        Text(
                            "Estimates are not contraception or a diagnosis.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                val today =
                                    archive.rows("days").firstOrNull {
                                        it.getString("day") == dashboard.getString("today")
                                    }
                                edit("day", today)
                            }
                        ) {
                            Text("Log today")
                        }
                        OutlinedButton(onClick = { edit("contact", null) }) {
                            Text("Log sex/contact")
                        }
                        DoseList(model, archive, dashboard)
                    }
                    1 -> {
                        Button(onClick = { edit("day", null) }) { Text("Add day") }
                        Text("Period & symptoms", style = MaterialTheme.typography.titleLarge)
                        if (archive.rows("days").isEmpty())
                            Text("Your journal starts with one day.")
                        archive
                            .rows("days")
                            .sortedByDescending { it.getString("day") }
                            .forEach { row ->
                                RecordCard(
                                    "${row.getString("day")} · ${row.getString("flow")}",
                                    row.optString("notes"),
                                    { edit("day", row) },
                                    { remove = "day:${row.getString("id")}" },
                                )
                            }
                        Text(
                            "Private sex/contact records",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text("These stay separate from your period journal.")
                        OutlinedButton(onClick = { edit("contact", null) }) { Text("Add contact") }
                        archive
                            .rows("contacts")
                            .sortedByDescending { it.getString("day") }
                            .forEach { row ->
                                RecordCard(
                                    "${row.getString("day")} · ${row.getString("activity")}",
                                    row.getString("protection"),
                                    { edit("contact", row) },
                                    { remove = "contact:${row.getString("id")}" },
                                )
                            }
                    }
                    2 -> {
                        Text("Plans & doses", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Reminder delivery is not connected in this Android base. Keep your existing alarms running."
                        )
                        Button(onClick = { edit("plan", null) }) { Text("Add pill plan") }
                        archive.rows("plans").forEach { row ->
                            RecordCard(
                                row.getString("name"),
                                "${row.getString("pattern")} · ${if (row.getBoolean("enabled")) "active" else "paused"}",
                                { edit("plan", row) },
                                { remove = "plan:${row.getString("id")}" },
                            )
                        }
                        DoseList(model, archive, dashboard)
                        Text("Dose history", style = MaterialTheme.typography.titleLarge)
                        archive
                            .rows("doses")
                            .sortedByDescending { it.getLong("recorded") }
                            .forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "${row.getString("day")} · ${row.getString("status")}",
                                        Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { remove = "dose:${row.getString("id")}" }
                                    ) {
                                        Text("Undo")
                                    }
                                }
                            }
                    }
                    3 -> {
                        Text(
                            "Patterns, not diagnoses",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(dashboard.getString("prediction_note"))
                        Text("Cycles recorded: ${dashboard.rows("cycles").size}")
                        Text(
                            "Doses taken: ${dashboard.getInt("taken")} · skipped: ${dashboard.getInt("skipped")}"
                        )
                        dashboard.rows("symptoms").forEach {
                            Text(
                                "${it.getString("name")}: ${it.getInt("count")} days · average ${it.getDouble("average")}"
                            )
                        }
                        if (dashboard.rows("symptoms").isEmpty())
                            Text(
                                "Log symptoms to see your patterns here. Unlogged days are not treated as symptom-free."
                            )
                    }
                    4 -> BackupSettings(model, archive)
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
                Text(
                    "${incoming.rows("days").size} day records, ${incoming.rows("contacts").size} contacts, ${incoming.rows("plans").size} plans. Merge keeps existing records and rejects conflicting IDs. Imported plans are paused. Your current preferences stay in place."
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
private fun RecordCard(title: String, subtitle: String, edit: () -> Unit, delete: () -> Unit) {
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
private fun DoseList(model: BloomModel, archive: JSONObject, dashboard: JSONObject) {
    Text("Today's doses", style = MaterialTheme.typography.titleLarge)
    if (dashboard.rows("today_doses").isEmpty()) Text("No doses scheduled today.")
    dashboard.rows("today_doses").forEach { dose ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(dose.getString("title"), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Instant.ofEpochSecond(dose.getLong("at")).atZone(ZoneId.systemDefault()).toLocalTime()} · ${dose.getString("phase")} · ${dose.getString("status")}"
                )
                Row {
                    listOf("taken", "skipped").forEach { status ->
                        TextButton(
                            onClick = {
                                val record =
                                    JSONObject()
                                        .put("id", dose.getString("id"))
                                        .put("plan_id", dose.getString("plan_id"))
                                        .put("day", dose.getString("day"))
                                        .put("scheduled", dose.getLong("at"))
                                        .put("status", status)
                                        .put("recorded", Instant.now().epochSecond)
                                        .put("snooze_until", JSONObject.NULL)
                                        .put("notes", "")
                                model.save("dose", record, archive.getLong("revision")) {}
                            }
                        ) {
                            Text(status.replaceFirstChar(Char::uppercase))
                        }
                    }
                }
            }
        }
    }
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
    if (kind == "plan") {
        Field("Plan name", row.getString("name")) { put("name", it) }
        DateField("Start date", row.getString("start")) { put("start", it) }
        Choice(
            "Schedule",
            row.getString("pattern"),
            listOf("pack", "daily", "interval", "weekly", "once"),
        ) {
            put("pattern", it)
        }
        val context = LocalContext.current
        val minute = row.getInt("minute")
        OutlinedButton(
            onClick = {
                android.app
                    .TimePickerDialog(
                        context,
                        { _, hour, minutes -> put("minute", hour * 60 + minutes) },
                        minute / 60,
                        minute % 60,
                        android.text.format.DateFormat.is24HourFormat(context),
                    )
                    .show()
            }
        ) {
            Text("Scheduled time: ${java.time.LocalTime.of(minute / 60, minute % 60)}")
        }
        if (row.getString("pattern") == "pack") {
            Field("Active days", row.getString("active_days")) {
                put("active_days", it.toIntOrNull() ?: it)
            }
            Field("Break days", row.getString("break_days")) {
                put("break_days", it.toIntOrNull() ?: it)
            }
        }
        if (row.getString("pattern") == "interval")
            Field("Every N days", row.getString("every_days")) {
                put("every_days", it.toIntOrNull() ?: it)
            }
        if (row.getString("pattern") == "weekly") {
            val selected = row.getJSONArray("weekdays")
            (1..7).forEach { day ->
                val days = (0 until selected.length()).map { selected.getInt(it) }.toMutableSet()
                Toggle(
                    java.time.DayOfWeek.of(day)
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
        DateField("Date", row.getString("day")) { put("day", it) }
        if (kind == "day") {
            Choice(
                "Flow",
                row.getString("flow"),
                listOf("none", "spotting", "light", "medium", "heavy"),
            ) {
                put("flow", it)
            }
            Toggle("Period starts today", row.getBoolean("period_start")) {
                put("period_start", it)
            }
            listOf("Cramps", "Headache", "Fatigue", "Mood").forEach { name ->
                val symptoms = row.getJSONObject("symptoms")
                Choice(
                    name,
                    if (symptoms.has(name)) symptoms.getInt(name).toString() else "Unlogged",
                    listOf("Unlogged", "0", "1", "2", "3"),
                ) { value ->
                    if (value == "Unlogged") symptoms.remove(name)
                    else symptoms.put(name, value.toInt())
                    put("symptoms", symptoms)
                }
            }
            Text("Symptom scale: 0 none, 1 mild, 2 moderate, 3 severe.")
        } else {
            Field("Activity", row.getString("activity")) { put("activity", it) }
            Field("Protection", row.getString("protection")) { put("protection", it) }
        }
    }
    OutlinedTextField(
        value = row.getString("notes"),
        onValueChange = { put("notes", it) },
        label = { Text("Notes") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )
}

@Composable
private fun DateField(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    val day = LocalDate.parse(value)
    OutlinedButton(
        onClick = {
            android.app
                .DatePickerDialog(
                    context,
                    { _, year, month, date ->
                        change(LocalDate.of(year, month + 1, date).toString())
                    },
                    day.year,
                    day.monthValue - 1,
                    day.dayOfMonth,
                )
                .show()
        }
    ) {
        Text("$label: $value")
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(vertical = 12.dp))
        Switch(value, change)
    }
}

@Composable
private fun Choice(label: String, value: String, options: List<String>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $value") }
        DropdownMenu(open, { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        change(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BackupSettings(model: BloomModel, archive: JSONObject) {
    var password by remember { mutableStateOf("") }
    var encrypted by rememberSaveable { mutableStateOf(true) }
    var contacts by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var exportKind by rememberSaveable { mutableStateOf("age") }
    var erase by rememberSaveable { mutableStateOf(false) }
    // Capture export choices when opening the system picker, not after it returns.
    var pendingPassword by remember { mutableStateOf("") }
    var pendingContacts by rememberSaveable { mutableStateOf(false) }
    var pendingNotes by rememberSaveable { mutableStateOf(false) }
    val create =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            if (uri != null)
                model.exportFile(uri, exportKind, pendingPassword, pendingContacts, pendingNotes)
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) model.importFile(uri, if (encrypted) password else null)
        }
    val profile = archive.getJSONObject("profile")
    Choice(
        "Mode",
        profile.getString("mode"),
        listOf("cycle", "conceive", "pregnancy", "postpartum"),
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
    Text("Your data stays on this device", style = MaterialTheme.typography.titleLarge)
    Text(
        "Encrypted with an Android Keystore key. System cloud and device-transfer backups are excluded. Export a portable backup before changing devices."
    )
    Text(
        "Encrypted backups and JSON include all records, including sex/contact and notes. CSV includes them only when selected."
    )
    OutlinedTextField(
        password,
        { password = it },
        label = { Text("Backup passphrase") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Toggle("Include contacts in CSV", contacts) { contacts = it }
    Toggle("Include notes in CSV", notes) { notes = it }
    listOf("age" to "Encrypted backup", "json" to "Full JSON export", "csv" to "CSV export")
        .forEach { (kind, label) ->
            OutlinedButton(
                onClick = {
                    exportKind = kind
                    pendingPassword = password
                    pendingContacts = contacts
                    pendingNotes = notes
                    create.launch("bloom-${LocalDate.now()}.$kind")
                }
            ) {
                Text(label)
            }
        }
    Toggle("Import is encrypted", encrypted) { encrypted = it }
    OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Choose backup to review") }
    Text(
        "Health Connect, notification delivery and wearable support are not connected in this Android base."
    )
    Text("Archive revision ${archive.getLong("revision")}")
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
