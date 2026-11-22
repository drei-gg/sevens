package gg.seven.kit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun DiscardChangesDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved changes will be lost.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep editing") } },
    )
}

/** Exact numeric entry for quantities where repeated increment taps are cumbersome. */
@Composable
fun IntegerSetting(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { editing = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        }
        Icon(Icons.Outlined.Edit, "Edit $label")
    }
    if (editing) {
        var draft by rememberSaveable { mutableStateOf(value.toString()) }
        val number = draft.toIntOrNull()
        val valid = number != null && number in range && (number - range.first) % step == 0
        val field: @Composable () -> Unit = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.filter(Char::isDigit).take(9) },
                label = { Text(label) },
                singleLine = true,
                isError = draft.isNotEmpty() && !valid,
                supportingText = {
                    Text(
                        "${range.first}–${range.last}" +
                            if (step > 1) "; increments of $step" else ""
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("integer-setting"),
            )
        }
        val commit = {
            if (valid && enabled) {
                onChange(number!!)
                editing = false
            }
        }
        // An AlertDialog cannot keep its field and action row above a landscape IME, especially
        // with enlarged text. Reuse the native full-screen form's fixed actions and IME insets.
        if (
            LocalConfiguration.current.screenHeightDp < 480 ||
                LocalDensity.current.fontScale >= 1.5f
        ) {
            FullScreenForm(
                title = label,
                onClose = { editing = false },
                onSave = commit,
                saveEnabled = valid && enabled,
                saveLabel = "Set",
            ) {
                field()
            }
        } else {
            AlertDialog(
                onDismissRequest = { editing = false },
                title = { Text(label) },
                text = field,
                confirmButton = {
                    TextButton(enabled = valid && enabled, onClick = commit) { Text("Set") }
                },
                dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
            )
        }
    }
}

/** A nullable single choice with an explicit clear action; zero remains a recorded value. */
@Composable
fun <T : Any> OptionalChoice(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    supporting: String? = null,
    onChange: (T?) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    supporting
                        ?: options.firstOrNull { it.first == selected }?.second
                        ?: "Not recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onChange(null) },
                enabled = selected != null,
                modifier = Modifier.semantics { contentDescription = "Clear $title" },
            ) {
                Text("Clear")
            }
        }
        ConnectedChoice<T?>(
            options,
            selected,
            optionDescription = { value ->
                "$title ${options.firstOrNull { it.first == value }?.second.orEmpty()}"
            },
            onSelect = onChange,
        )
    }
}
