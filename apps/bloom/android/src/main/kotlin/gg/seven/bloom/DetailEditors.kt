package gg.seven.bloom

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import java.time.*
import org.json.JSONObject

/**
 * Editors keep complete JSON drafts across recreation, including fields this screen doesn't edit.
 */
@Composable
internal fun DetailEditor(kind: String, row: JSONObject, change: (String) -> Unit) {
    fun put(key: String, value: Any) {
        change(row.put(key, value).toString())
    }
    when (kind) {
        "observation" -> {
            TimestampField("When", row.getLong("at"), row.getString("zone")) { at ->
                row.put("at", at)
                    .put(
                        "day",
                        Instant.ofEpochSecond(at)
                            .atZone(ZoneId.of(row.getString("zone")))
                            .toLocalDate()
                            .toString(),
                    )
                change(row.toString())
            }
            Muted("Recorded time zone: ${row.getString("zone")}")
            Choice(
                "Type",
                row.getString("kind"),
                listOf(
                    "symptom",
                    "flow",
                    "temperature",
                    "ovulation_test",
                    "pregnancy_test",
                    "product",
                ),
            ) { type ->
                row.put("kind", type)
                    .put("period_start", false)
                    .put("number", JSONObject.NULL)
                    .put("level", if (type == "symptom") 1 else JSONObject.NULL)
                    .put("label", if (type == "symptom") "Cramps" else "")
                    .put(
                        "value",
                        if (type == "flow") "light"
                        else if (type.endsWith("_test")) "negative" else "",
                    )
                change(row.toString())
            }
            val type = row.getString("kind")
            if (type in listOf("symptom", "product"))
                Field(
                    if (type == "product") "Product, e.g. pad or cup" else "Observation name",
                    row.getString("label"),
                ) {
                    put("label", it)
                }
            if (type == "symptom")
                Choice("Level", row.optInt("level", 1).toString(), listOf("0", "1", "2", "3")) {
                    put("level", it.toInt())
                }
            if (type == "flow") {
                Choice(
                    "Flow",
                    row.getString("value"),
                    listOf("none", "spotting", "light", "medium", "heavy"),
                ) {
                    put("value", it)
                }
                Toggle("Start of a new period", row.getBoolean("period_start")) { on ->
                    if (on && row.getString("value") in listOf("none", "spotting"))
                        row.put("value", "light")
                    put("period_start", on)
                }
            }
            if (type.endsWith("_test"))
                Choice(
                    "Result",
                    row.getString("value"),
                    listOf("negative", "positive", "unclear"),
                ) {
                    put("value", it)
                }
            if (type == "product")
                Choice(
                    "Fullness",
                    row.getString("value"),
                    listOf("", "light", "partly_full", "full", "overflow"),
                ) {
                    put("value", it)
                }
            if (type == "temperature") TemperatureField("Temperature", row, "number", change)
            if (type == "product")
                Field(
                    "Measured amount, mL (optional)",
                    if (row.isNull("number")) "" else row.getString("number"),
                    number = true,
                ) {
                    put("number", if (it.isBlank()) JSONObject.NULL else it.toDoubleOrNull() ?: it)
                }
            Muted(
                "Multiple entries keep their own times. Daily summaries use the highest flow or symptom level; every entry stays in the timeline."
            )
        }
        "exclusion" -> {
            Field("Reason", row.getString("reason")) { put("reason", it) }
            DateField("From", row.getString("start")) { put("start", it) }
            Toggle("Ongoing", row.isNull("end")) {
                put("end", if (it) JSONObject.NULL else LocalDate.now().toString())
            }
            if (!row.isNull("end")) DateField("Through", row.getString("end")) { put("end", it) }
            Muted(
                "Overlapping cycles stay in history but are not used for estimates. Correcting this interval never deletes observations."
            )
        }
        "refill" -> {
            DateField("Date", row.getString("day")) { put("day", it) }
            Field("Quantity added", row.getString("quantity"), number = true) {
                put("quantity", it.toIntOrNull() ?: it)
            }
        }
        "dose" -> {
            Text(
                "${pretty(row.getString("day"))} · scheduled ${timeLabel(row.getLong("scheduled"))}",
                style = MaterialTheme.typography.titleMedium,
            )
            Choice("Status", row.getString("status"), listOf("taken", "skipped", "snoozed")) {
                status ->
                row.put("recorded", Instant.now().epochSecond)
                    .put(
                        "snooze_until",
                        if (status == "snoozed") Instant.now().epochSecond + 600
                        else JSONObject.NULL,
                    )
                put("status", status)
            }
        }
        "appointment" -> {
            Field("Title", row.getString("title")) { put("title", it) }
            TimestampField("When", row.getLong("at")) { put("at", it) }
            gg.seven.kit.DropdownField(
                "Reminder",
                row.getInt("remind_minutes"),
                listOf(
                    -1 to "Off",
                    0 to "At the appointment",
                    15 to "15 minutes before",
                    60 to "1 hour before",
                    1440 to "1 day before",
                    10080 to "1 week before",
                ),
            ) {
                put("remind_minutes", it)
            }
        }
        "pregnancy" -> {
            DateField("Record starts", row.getString("start")) { put("start", it) }
            DateField("Estimated due date", row.getString("due")) { put("due", it) }
            Muted(
                "Use the date from your care team. The counter cannot confirm dating or pregnancy health."
            )
            Toggle("This pregnancy has ended", !row.isNull("ended")) {
                put("ended", if (it) LocalDate.now().toString() else JSONObject.NULL)
            }
            if (!row.isNull("ended")) {
                DateField("Ended", row.getString("ended")) { put("ended", it) }
                Field("Outcome (optional)", row.getString("outcome")) { put("outcome", it) }
                Muted(
                    "The record stays in history. Choose postpartum or lactation in Settings if that fits your current tracking needs."
                )
            }
        }
    }
}

@Composable
internal fun TimestampField(
    label: String,
    at: Long,
    zone: String = ZoneId.systemDefault().id,
    change: (Long) -> Unit,
) {
    gg.seven.kit.DateTimeField(label, at * 1000.0, zone = ZoneId.of(zone)) {
        change((it / 1000).toLong())
    }
}

@Composable
internal fun WellbeingFields(row: JSONObject, change: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = preference(context)
    var expanded by rememberSaveable { mutableStateOf(false) }
    // A private draft: kept in memory only, like the rest of the editor.
    var custom by remember { mutableStateOf("") }
    val symptoms = row.getJSONObject("symptoms")
    val pinned =
        prefs.getString("favorites", "Cramps,Headache,Fatigue,Calm")!!.split(',').filter {
            it.isNotBlank()
        }
    val names =
        ((if (expanded) categories else pinned) + symptoms.keys().asSequence().toList()).distinct()
    SectionTitle("How you feel")
    names.forEach { name ->
        gg.seven.kit.OptionalChoice(
            name,
            listOf(0 to "None", 1 to "Low", 2 to "Medium", 3 to "High"),
            if (symptoms.has(name)) symptoms.getInt(name) else null,
        ) { value ->
            if (value == null) symptoms.remove(name) else symptoms.put(name, value)
            change(row.put("symptoms", symptoms).toString())
        }
    }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Only pinned observations" else "More observations")
    }
    Field("Custom observation", custom) { custom = it }
    TextButton(
        enabled = custom.isNotBlank(),
        onClick = {
            symptoms.put(custom.trim(), 1)
            change(row.put("symptoms", symptoms).toString())
            custom = ""
        },
    ) {
        Text("Add observation")
    }
    Muted("0 none · 1 low · 2 medium · 3 high. Unlogged is different from none.")
    var details by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { details = !details }) {
        Text(if (details) "Hide optional details" else "Temperature, tests & tags")
    }
    if (details) {
        TemperatureField("Basal temperature", row, "bbt", change)
        Choice(
            "Cervical mucus",
            row.getString("mucus"),
            listOf("none", "dry", "sticky", "creamy", "watery", "egg_white"),
        ) {
            change(row.put("mucus", it).toString())
        }
        listOf("ovulation_test", "pregnancy_test").forEach { key ->
            Choice(
                key.replace('_', ' '),
                row.getString(key),
                listOf("none", "negative", "positive", "unclear"),
            ) {
                change(row.put(key, it).toString())
            }
        }
        Field(
            "Tags, comma separated",
            (0 until row.getJSONArray("tags").length()).joinToString(", ") {
                row.getJSONArray("tags").getString(it)
            },
        ) {
            change(
                row.put(
                        "tags",
                        org.json.JSONArray(
                            it.split(',').map(String::trim).filter(String::isNotBlank)
                        ),
                    )
                    .toString()
            )
        }
    }
}

@Composable
internal fun LayoutPreferences() {
    val prefs = preference(LocalContext.current)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var favorites by rememberSaveable {
        mutableStateOf(prefs.getString("favorites", "Cramps,Headache,Fatigue,Calm")!!)
    }
    var contacts by rememberSaveable { mutableStateOf(prefs.getBoolean("contacts", false)) }
    SectionTitle("Make it yours")
    Toggle("Show sex/contact shortcut on Today", contacts) {
        contacts = it
        prefs.edit { putBoolean("contacts", it) }
    }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Done choosing favorites" else "Choose quick-log favorites")
    }
    if (expanded)
        categories.forEach { name ->
            Check(name, name in favorites.split(',')) { on ->
                val set = favorites.split(',').filter(String::isNotBlank).toMutableSet()
                if (on) set.add(name) else set.remove(name)
                favorites = set.joinToString(",")
                prefs.edit { putString("favorites", favorites) }
            }
        }
    Muted(
        "Hiding a category never removes its records. Contact history stays available in Calendar."
    )
}
