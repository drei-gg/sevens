@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.kit

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * The Sevens' Android building blocks, all Material 3 Expressive components configured once:
 * segmented list sections, connected toggle groups, outlined form fields with Material's date and
 * time pickers, filter chips, the search bar, a full-screen form dialog and an empty state.
 * Nothing here imitates an iOS control; where an app needs a visual Material doesn't have (a
 * calendar ring, a water glass), the app draws it from its core's geometry.
 */

/** Spacing tokens, native-polish §3.3. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val ms = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** Tabular figures for anything that changes or sits in a column (§4.1 `numeric`). */
fun TextStyle.numeric(): TextStyle = copy(fontFeatureSettings = "tnum")

object SevenFormat {
    fun date(value: ZonedDateTime): String = value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    fun time(value: ZonedDateTime, h24: Boolean): String =
        value.format(if (h24) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}

// MARK: segmented list sections

/** Builds the rows of a [Section]; each row becomes one Material segmented list item. */
class SectionScope internal constructor() {
    internal val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    /** Any row; draw it with [SegmentedListItem] using the shapes you're given. */
    fun row(content: @Composable (ListItemShapes) -> Unit) {
        rows += content
    }
}

/**
 * A titled group of Material segmented list items: rounded as a whole, with the standard gap
 * between rows. The Expressive way to group settings, details and records.
 */
@Composable
fun Section(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    build: SectionScope.() -> Unit,
) {
    val rows = SectionScope().apply(build).rows
    Column(modifier.fillMaxWidth()) {
        title?.let {
            Text(
                it,
                Modifier.padding(start = Space.m, end = Space.m, bottom = Space.s),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            rows.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, rows.size)) }
        }
        footer?.let {
            Text(
                it,
                Modifier.padding(start = Space.m, end = Space.m, top = Space.s),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row with a label, optional supporting text and value; a chevron when it leads somewhere. */
fun SectionScope.item(
    label: String,
    value: String? = null,
    supporting: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = row { shapes ->
    val lead: (@Composable () -> Unit)? = icon?.let { { Icon(it, null) } }
    val tail: (@Composable () -> Unit)? =
        trailing
            ?: if (value != null || onClick != null) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        value?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium.numeric(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        }
                        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                    }
                }
            } else null
    val body: @Composable () -> Unit = { Text(label) }
    val support: (@Composable () -> Unit)? = supporting?.let { { Text(it) } }
    if (onClick != null)
        SegmentedListItem(onClick, shapes, leadingContent = lead, trailingContent = tail, supportingContent = support, content = body)
    else SegmentedListItem(shapes, leadingContent = lead, trailingContent = tail, supportingContent = support, content = body)
}

/** A labelled switch; the whole row toggles. */
fun SectionScope.switch(label: String, checked: Boolean, supporting: String? = null, onCheckedChange: (Boolean) -> Unit) =
    row { shapes ->
        // The plain clickable row: the toggleable variant highlights the row when on, which is
        // selection styling, not a setting.
        SegmentedListItem(
            onClick = { onCheckedChange(!checked) },
            shapes = shapes,
            modifier = Modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(checked) },
            supportingContent = supporting?.let { { Text(it) } },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        ) {
            Text(label)
        }
    }

/** An action, in primary or error colour, with an optional leading icon. */
fun SectionScope.action(label: String, icon: ImageVector? = null, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    row { shapes ->
        val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            enabled = enabled,
            leadingContent = icon?.let { { Icon(it, null, tint = color) } },
        ) {
            Text(label, color = color)
        }
    }

/** Free content in a segmented row (a paragraph, a field). */
fun SectionScope.content(body: @Composable () -> Unit) = row { shapes ->
    SegmentedListItem(shapes) { body() }
}

// MARK: choices

/**
 * A connected button group: the Expressive replacement for a segmented control. Exactly one
 * option is selected; each button is a radio for accessibility.
 */
@Composable
fun <T> ConnectedChoice(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val view = LocalView.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, (value, label) ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = {
                    if (value != selected) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                    onSelect(value)
                },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton }.testTag("choice-$label"),
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Single-select filter chips that wrap: presets, quick amounts. */
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

// MARK: fields

/** A number in an outlined field, with a unit suffix. */
@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, suffix: String? = null, supporting: String? = null, onValueChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value,
        { next -> onValueChange(next.filter { it.isDigit() || it == '.' || it == ',' }) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        textStyle = LocalTextStyle.current.numeric(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
    )
}

/** Free text in an outlined field; multi-line when [minLines] > 1. */
@Composable
fun TextInput(label: String, value: String, modifier: Modifier = Modifier, minLines: Int = 1, supporting: String? = null, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        minLines = minLines,
        singleLine = minLines == 1,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

/** A read-only outlined field that opens a picker when tapped. */
@Composable
private fun PickerField(label: String, value: String, icon: ImageVector, modifier: Modifier, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) onOpen() }
    }
    OutlinedTextField(
        value,
        {},
        modifier = modifier,
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { Icon(icon, null) },
        singleLine = true,
        textStyle = LocalTextStyle.current.numeric(),
        interactionSource = interaction,
    )
}

/**
 * Date and time as two outlined fields, opening Material's date picker and time picker. Reports
 * epoch milliseconds in [zone]; follows the device's 12/24-hour setting.
 */
@Composable
fun DateTimeField(label: String, millis: Double, modifier: Modifier = Modifier, withTime: Boolean = true, zone: ZoneId = ZoneId.systemDefault(), onChange: (Double) -> Unit) {
    val context = LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    val at = Instant.ofEpochMilli(millis.toLong()).atZone(zone)
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        PickerField(label, SevenFormat.date(at), Icons.Outlined.CalendarMonth, Modifier.weight(1.4f).testTag("$label-date")) { pickDate = true }
        if (withTime) PickerField("Time", SevenFormat.time(at, h24), Icons.Outlined.Schedule, Modifier.weight(1f).testTag("$label-time")) { pickTime = true }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = at.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        val next = if (withTime) day.atTime(at.toLocalTime()).atZone(zone) else day.atStartOfDay(zone)
                        onChange(next.toInstant().toEpochMilli().toDouble())
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state)
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(at.hour, at.minute, h24)
        TimePickerDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(label) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(at.toLocalDate().atTime(LocalTime.of(state.hour, state.minute)).atZone(zone).toInstant().toEpochMilli().toDouble())
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        ) {
            TimePicker(state)
        }
    }
}

/** A time of day only, opening Material's time picker. */
@Composable
fun TimeField(label: String, hour: Int, minute: Int, modifier: Modifier = Modifier, onChange: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    var pick by rememberSaveable { mutableStateOf(false) }
    val shown = SevenFormat.time(LocalDate.now().atTime(hour, minute).atZone(ZoneId.systemDefault()), h24)
    PickerField(label, shown, Icons.Outlined.Schedule, modifier.fillMaxWidth()) { pick = true }
    if (pick) {
        val state = rememberTimePickerState(hour, minute, h24)
        TimePickerDialog(
            onDismissRequest = { pick = false },
            title = { Text(label) },
            confirmButton = { TextButton(onClick = { onChange(state.hour, state.minute); pick = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pick = false }) { Text("Cancel") } },
        ) {
            TimePicker(state)
        }
    }
}

/** A date only. */
@Composable
fun DateField(label: String, date: LocalDate, modifier: Modifier = Modifier, onChange: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault()
    DateTimeField(label, date.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(), modifier, withTime = false, zone = zone) {
        onChange(Instant.ofEpochMilli(it.toLong()).atZone(zone).toLocalDate())
    }
}

/** Material's exposed dropdown: an outlined field showing the choice, with a menu. */
@Composable
fun <T> DropdownField(label: String, selected: T, choices: List<Pair<T, String>>, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = choices.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}

/** Material's search bar, collapsed, as an in-page filter. */
@Composable
fun SearchInput(placeholder: String, modifier: Modifier = Modifier, state: TextFieldState = rememberTextFieldState()) {
    val bar = rememberSearchBarState()
    SearchBar(
        state = bar,
        modifier = modifier.fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = state,
                searchBarState = bar,
                onSearch = {},
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
            )
        },
    )
}

// MARK: forms

/**
 * Material's full-screen dialog for a form: close on the leading edge, the title, and the
 * confirming text action on the trailing edge of its top app bar. The form scrolls; the keyboard
 * never covers the focused field.
 */
@Composable
fun FullScreenForm(
    title: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    busy: Boolean = false,
    saveEnabled: Boolean = true,
    saveLabel: String = "Save",
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("editor-cancel")) {
                            Icon(Icons.Outlined.Close, "Close")
                        }
                    },
                    actions = {
                        TextButton(onClick = onSave, enabled = !busy && saveEnabled, modifier = Modifier.testTag("editor-save")) { Text(saveLabel) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.m),
                content = content,
            )
        }
    }
}

/** A form section heading inside [FullScreenForm]. */
@Composable
fun FormHeading(text: String) {
    Text(text, Modifier.padding(top = Space.s), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** Empty state, §8.2: glyph, title, one sentence, at most one action. */
@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, icon: ImageVector? = null, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Space.xl, horizontal = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        icon?.let { Icon(it, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, run) ->
            Spacer(Modifier.height(Space.xs))
            FilledTonalButton(onClick = run) { Text(label) }
        }
    }
}
