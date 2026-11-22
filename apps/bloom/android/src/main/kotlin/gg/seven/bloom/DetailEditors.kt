package gg.seven.bloom

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
            TimestampField(stringResource(R.string.observation_when), row.getLong("at"), row.getString("zone")) {
                at ->
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
            Muted(stringResource(R.string.observation_zone, row.getString("zone")))
            Choice(
                stringResource(R.string.observation_type),
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
                    stringResource(
                        if (type == "product") R.string.observation_product
                        else R.string.observation_name
                    ),
                    row.getString("label"),
                ) {
                    put("label", it)
                }
            if (type == "symptom")
                Choice(
                    stringResource(R.string.observation_level_field),
                    row.optInt("level", 1).toString(),
                    listOf("0", "1", "2", "3"),
                ) {
                    put("level", it.toInt())
                }
            if (type == "flow") {
                Choice(
                    stringResource(R.string.field_flow),
                    row.getString("value"),
                    listOf("none", "spotting", "light", "medium", "heavy"),
                ) {
                    put("value", it)
                }
                Toggle(stringResource(R.string.observation_period_start), row.getBoolean("period_start")) {
                    on ->
                    if (on && row.getString("value") in listOf("none", "spotting"))
                        row.put("value", "light")
                    put("period_start", on)
                }
            }
            if (type.endsWith("_test"))
                Choice(
                    stringResource(R.string.observation_result),
                    row.getString("value"),
                    listOf("negative", "positive", "unclear"),
                ) {
                    put("value", it)
                }
            if (type == "product")
                Choice(
                    stringResource(R.string.observation_fullness),
                    row.getString("value"),
                    listOf("", "light", "partly_full", "full", "overflow"),
                ) {
                    put("value", it)
                }
            if (type == "temperature")
                TemperatureField(stringResource(R.string.observation_temperature), row, "number", change)
            if (type == "product")
                Field(
                    stringResource(R.string.observation_amount),
                    if (row.isNull("number")) "" else row.getString("number"),
                    number = true,
                ) {
                    put("number", if (it.isBlank()) JSONObject.NULL else it.toDoubleOrNull() ?: it)
                }
            Muted(stringResource(R.string.observation_note))
        }
        "exclusion" -> {
            Field(stringResource(R.string.exclusion_reason), row.getString("reason")) {
                put("reason", it)
            }
            DateField(stringResource(R.string.field_from), row.getString("start")) {
                put("start", it)
            }
            Toggle(stringResource(R.string.exclusion_ongoing), row.isNull("end")) {
                put("end", if (it) JSONObject.NULL else LocalDate.now().toString())
            }
            if (!row.isNull("end"))
                DateField(stringResource(R.string.field_through), row.getString("end")) {
                    put("end", it)
                }
            Muted(stringResource(R.string.exclusion_note))
        }
        "refill" -> {
            DateField(stringResource(R.string.field_date), row.getString("day")) { put("day", it) }
            Field(stringResource(R.string.refill_quantity), row.getString("quantity"), number = true) {
                put("quantity", it.toIntOrNull() ?: it)
            }
        }
        "dose" -> {
            Text(
                stringResource(
                    R.string.dose_scheduled,
                    pretty(row.getString("day")),
                    timeLabel(row.getLong("scheduled")),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Choice(
                stringResource(R.string.dose_status),
                row.getString("status"),
                listOf("taken", "skipped", "snoozed"),
            ) { status ->
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
            Field(stringResource(R.string.appointment_title), row.getString("title")) {
                put("title", it)
            }
            TimestampField(stringResource(R.string.observation_when), row.getLong("at")) {
                put("at", it)
            }
            gg.seven.kit.DropdownField(
                stringResource(R.string.appointment_reminder),
                row.getInt("remind_minutes"),
                listOf(
                        -1 to R.string.appointment_off,
                        0 to R.string.appointment_at,
                        15 to R.string.appointment_15,
                        60 to R.string.appointment_60,
                        1440 to R.string.appointment_day,
                        10080 to R.string.appointment_week,
                    )
                    .map { (minutes, words) -> minutes to stringResource(words) },
            ) {
                put("remind_minutes", it)
            }
        }
        "pregnancy" -> {
            DateField(stringResource(R.string.pregnancy_start), row.getString("start")) {
                put("start", it)
            }
            DateField(stringResource(R.string.pregnancy_due), row.getString("due")) {
                put("due", it)
            }
            Muted(stringResource(R.string.pregnancy_due_note))
            Toggle(stringResource(R.string.pregnancy_ended_toggle), !row.isNull("ended")) {
                put("ended", if (it) LocalDate.now().toString() else JSONObject.NULL)
            }
            if (!row.isNull("ended")) {
                DateField(stringResource(R.string.pregnancy_ended), row.getString("ended")) {
                    put("ended", it)
                }
                Field(stringResource(R.string.pregnancy_outcome), row.getString("outcome")) {
                    put("outcome", it)
                }
                Muted(stringResource(R.string.pregnancy_ended_note))
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
    SectionTitle(stringResource(R.string.feel_title))
    val levels =
        listOf(
                R.string.value_none,
                R.string.level_low,
                R.string.value_medium,
                R.string.level_high,
            )
            .mapIndexed { level, words -> level to stringResource(words) }
    names.forEach { name ->
        gg.seven.kit.OptionalChoice(
            name,
            levels,
            if (symptoms.has(name)) symptoms.getInt(name) else null,
        ) { value ->
            if (value == null) symptoms.remove(name) else symptoms.put(name, value)
            change(row.put("symptoms", symptoms).toString())
        }
    }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.feel_pinned else R.string.feel_more))
    }
    Field(stringResource(R.string.feel_custom), custom) { custom = it }
    TextButton(
        enabled = custom.isNotBlank(),
        onClick = {
            symptoms.put(custom.trim(), 1)
            change(row.put("symptoms", symptoms).toString())
            custom = ""
        },
    ) {
        Text(stringResource(R.string.feel_add))
    }
    Muted(stringResource(R.string.feel_scale))
    var details by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { details = !details }) {
        Text(
            stringResource(if (details) R.string.feel_details_hide else R.string.feel_details_show)
        )
    }
    if (details) {
        TemperatureField(stringResource(R.string.feel_bbt), row, "bbt", change)
        Choice(
            stringResource(R.string.feel_mucus),
            row.getString("mucus"),
            listOf("none", "dry", "sticky", "creamy", "watery", "egg_white"),
        ) {
            change(row.put("mucus", it).toString())
        }
        listOf("ovulation_test", "pregnancy_test").forEach { key ->
            Choice(
                valueText(key),
                row.getString(key),
                listOf("none", "negative", "positive", "unclear"),
            ) {
                change(row.put(key, it).toString())
            }
        }
        Field(
            stringResource(R.string.feel_tags),
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
    SectionTitle(stringResource(R.string.layout_title))
    Toggle(stringResource(R.string.layout_contacts), contacts) {
        contacts = it
        prefs.edit { putBoolean("contacts", it) }
    }
    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(if (expanded) R.string.layout_favorites_done else R.string.layout_favorites)
        )
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
    Muted(stringResource(R.string.layout_note))
}
