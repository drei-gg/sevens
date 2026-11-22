package gg.seven.bloom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.text.util.LocalePreferences
import gg.seven.bloom.core.bloomTemperatureCelsius
import gg.seven.bloom.core.bloomTemperatureDisplay
import java.text.DecimalFormat
import org.json.JSONObject

/**
 * Celsius is stored. The unit shown and entered follows the person's regional temperature
 * preference (Android 14 regional preferences, otherwise the locale's default); the Rust core
 * converts and validates.
 */
internal object BloomTemperature {
    val fahrenheit: Boolean
        get() =
            LocalePreferences.getTemperatureUnit() == LocalePreferences.TemperatureUnit.FAHRENHEIT

    val unit: String
        get() = if (fahrenheit) "°F" else "°C"

    fun display(celsius: Double): Double = bloomTemperatureDisplay(celsius, fahrenheit)

    fun number(celsius: Double): String = DecimalFormat("0.##").format(display(celsius))

    fun text(celsius: Double): String = "${number(celsius)} $unit"

    /** null for a blank field; throws with a readable reason for an unusable value. */
    fun celsius(field: String): Double? {
        val text = field.trim().replace(',', '.')
        if (text.isEmpty()) return null
        val value =
            text.toDoubleOrNull() ?: throw BloomProblem(R.string.temperature_invalid, unit)
        return bloomTemperatureCelsius(value, fahrenheit)
    }

    /** The reason a draft cannot be saved: an entered temperature that did not convert. */
    fun problem(context: android.content.Context, kind: String, row: JSONObject): String? {
        val key =
            when {
                kind == "day" -> "bbt"
                kind == "observation" && row.optString("kind") == "temperature" -> "number"
                else -> return null
            }
        val raw = row.opt(key) as? String ?: return null
        return runCatching { celsius(raw) }.exceptionOrNull()?.sentence(context)
            ?: context.getString(R.string.temperature_invalid, unit)
    }
}

/**
 * Edits a stored Celsius value in the person's unit. The field keeps what was typed; the draft
 * holds Celsius once the entry converts, or the typed text so saving can explain the problem.
 */
@Composable
internal fun TemperatureField(label: String, row: JSONObject, key: String, change: (String) -> Unit) {
    // In memory only; after recreation the field is rebuilt from the draft's stored value.
    var text by
        remember(row.optString("id"), row.optString("kind")) {
            mutableStateOf(
                when (val value = row.opt(key)) {
                    is Number -> BloomTemperature.number(value.toDouble())
                    is String -> value
                    else -> ""
                }
            )
        }
    Field(
        androidx.compose.ui.res.stringResource(R.string.temperature_field, label, BloomTemperature.unit),
        text,
        number = true,
    ) { value ->
        text = value
        val stored: Any =
            try {
                BloomTemperature.celsius(value) ?: JSONObject.NULL
            } catch (_: Exception) {
                value
            }
        change(row.put(key, stored).toString())
    }
}
