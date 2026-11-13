@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package gg.seven.kit

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/*
 * Android counterparts of the iOS form vocabulary the Sevens use: inset-grouped sections, rows with
 * a trailing value, compact date and time pills, inline menu rows and a sheet with Cancel and Save
 * in its top bar. Material 3 has the parts (cards, menus, pickers, switches); these set them out
 * the way a grouped iOS Form does, with M3 behaviour, touch targets and font scaling kept.
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

private val RowMinHeight = 52.dp

/**
 * An inset-grouped section: optional header above, a card of rows with hairlines inset to the text
 * start, optional footer below. Children are laid out top to bottom; each child is one row.
 */
@Composable
fun SevenGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        header?.let {
            Text(
                it,
                Modifier.padding(start = Space.m, end = Space.m, bottom = Space.s),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val rule = MaterialTheme.colorScheme.outlineVariant
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val seams = remember { mutableListOf<Int>() }
        Layout(
            content = content,
            modifier =
                Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface)
                    .drawWithContent {
                        drawContent()
                        val inset = Space.m.toPx()
                        val stroke = 1.dp.toPx() / 2f
                        seams.forEach { y ->
                            val yy = y.toFloat() - stroke / 2
                            if (rtl) drawLine(rule, Offset(0f, yy), Offset(size.width - inset, yy), stroke)
                            else drawLine(rule, Offset(inset, yy), Offset(size.width, yy), stroke)
                        }
                    },
        ) { measurables, constraints ->
            val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
            val placeables = measurables.map { it.measure(loose) }.filter { it.height > 0 }
            val height = placeables.sumOf { it.height }
            seams.clear()
            var y = 0
            placeables.forEachIndexed { i, p ->
                y += p.height
                if (i < placeables.lastIndex) seams += y
            }
            layout(constraints.maxWidth, height) {
                var top = 0
                placeables.forEach {
                    it.placeRelative(0, top)
                    top += it.height
                }
            }
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

/** One row of a [SevenGroup]: a label leading, anything trailing. */
@Composable
fun SevenRow(
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        leading?.let {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    it()
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** A row that shows a value and, when it leads somewhere, a chevron. */
@Composable
fun ValueRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    SevenRow(label, modifier, supporting, leading, onClick) {
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge.numeric(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
        if (chevron)
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/** An action row in ink (or error for destructive), like a Form button on iOS. */
@Composable
fun ActionRow(
    label: String,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            destructive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        leading?.let {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides color) { it() }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A labelled switch; the whole row toggles (§6: label says what *on* means). */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val view = LocalView.current
    SevenRow(
        label,
        modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = {
                view.performHapticFeedback(
                    if (it) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
                )
                onCheckedChange(it)
            },
        ),
        supporting,
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A number typed in place, trailing and right-aligned, like an iOS form value. */
@Composable
fun NumberRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    placeholder: String = "Value",
    decimal: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    val style =
        MaterialTheme.typography.bodyLarge
            .numeric()
            .copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    SevenRow(label, modifier) {
        BasicTextField(
            value,
            { next -> onValueChange(next.filter { it.isDigit() || it == '.' || it == ',' }) },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.widthIn(min = 64.dp, max = 140.dp).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty())
                        Text(
                            placeholder,
                            style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    inner()
                }
            },
        )
        unit?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The counterpart of an iOS menu-style Picker in a Form: the current value trailing with an
 * up-down glyph; tapping opens a Material menu anchored to it.
 */
@Composable
fun <T> MenuRow(
    label: String,
    selected: T,
    choices: List<Pair<T, String>>,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val view = LocalView.current
    val current = choices.firstOrNull { it.first == selected }?.second.orEmpty()
    SevenRow(label, modifier.semantics { contentDescription = "$label, $current" }, onClick = { open = true }) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current,
                    style = MaterialTheme.typography.bodyLarge.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Icon(
                    Icons.Rounded.UnfoldMore,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                choices.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        trailingIcon =
                            if (value == selected) {
                                { Icon(Icons.Rounded.Check, null) }
                            } else null,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onSelect(value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** A compact pill, the look of iOS's compact DatePicker. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor =
            if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.heightIn(min = 36.dp).minimumInteractiveComponentSize(),
    ) {
        Box(Modifier.padding(horizontal = Space.ms, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodyLarge.numeric(), maxLines = 1)
        }
    }
}

object SevenFormat {
    fun date(value: ZonedDateTime): String =
        value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    /** Honors the device's 12/24-hour choice, not just the locale. */
    @Composable
    fun time(value: ZonedDateTime): String {
        val context = LocalContext.current
        return time(value, android.text.format.DateFormat.is24HourFormat(context))
    }

    fun time(value: ZonedDateTime, h24: Boolean): String =
        value.format(
            if (h24) DateTimeFormatter.ofPattern("HH:mm")
            else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        )
}

/**
 * Label leading, a date pill and (optionally) a time pill trailing. Each pill opens the Material 3
 * date or time picker in a dialog; the row reports epoch milliseconds in [zone].
 */
@Composable
fun DateTimeRow(
    label: String,
    millis: Double,
    modifier: Modifier = Modifier,
    withTime: Boolean = true,
    withDate: Boolean = true,
    zone: ZoneId = ZoneId.systemDefault(),
    onChange: (Double) -> Unit,
) {
    val context = LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    val at = Instant.ofEpochMilli(millis.toLong()).atZone(zone)
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    SevenRow(label, modifier) {
        if (withDate) Pill(SevenFormat.date(at), Modifier.testTag("$label-date")) { pickDate = true }
        if (withTime) Pill(SevenFormat.time(at, h24), Modifier.testTag("$label-time")) { pickTime = true }
    }
    if (pickDate) {
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    at.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            val day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            onChange(
                                (if (withTime) day.atTime(at.toLocalTime()).atZone(zone)
                                    else day.atStartOfDay(zone))
                                    .toInstant()
                                    .toEpochMilli()
                                    .toDouble()
                            )
                        }
                        pickDate = false
                    }
                ) {
                    Text("Done")
                }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state, showModeToggle = true)
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(at.hour, at.minute, h24)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange(
                            at.toLocalDate()
                                .atTime(LocalTime.of(state.hour, state.minute))
                                .atZone(zone)
                                .toInstant()
                                .toEpochMilli()
                                .toDouble()
                        )
                        pickTime = false
                    }
                ) {
                    Text("Done")
                }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            title = { Text(label) },
            text = { TimePicker(state) },
        )
    }
}

/** Date only, as a pill row. */
@Composable
fun DateRow(
    label: String,
    date: LocalDate,
    modifier: Modifier = Modifier,
    onChange: (LocalDate) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    DateTimeRow(
        label,
        date.atStartOfDay(zone).toInstant().toEpochMilli().toDouble(),
        modifier,
        withTime = false,
        zone = zone,
    ) {
        onChange(Instant.ofEpochMilli(it.toLong()).atZone(zone).toLocalDate())
    }
}

/** Free text inside a group, placeholder in muted, no outline (iOS Form text field). */
@Composable
fun TextRow(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value,
        onValueChange,
        textStyle = style,
        minLines = minLines,
        singleLine = minLines == 1,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions =
            KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                imeAction = if (minLines == 1) ImeAction.Done else ImeAction.Default,
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(
                Modifier.padding(horizontal = Space.m, vertical = Space.ms),
                contentAlignment = if (minLines == 1) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty())
                    Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                inner()
            }
        },
    )
}

/** A rounded search field, the iOS `.searchable` look in a Material TextField. */
@Composable
fun SearchField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    TextField(
        value,
        onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon =
            if (value.isNotEmpty()) {
                {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Rounded.Cancel, "Clear search")
                    }
                }
            } else null,
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    )
}

/**
 * The Material segmented button set out like iOS's segmented Picker: full width, no check glyph,
 * a selection tick haptic.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val view = LocalView.current
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = {
                    if (value != selected) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                    onSelect(value)
                },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                modifier = Modifier.testTag("segment-$label"),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Capsule choices that wrap, for presets and quick amounts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> CapsuleChoices(
    options: List<Pair<T, String>>,
    selected: T?,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    val view = LocalView.current
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        options.forEach { (value, label) ->
            Pill(label, selected = value == selected) {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                onSelect(value)
            }
        }
    }
}

/**
 * A task sheet with the commit in its top bar: Cancel leading, title centred, Save trailing (the
 * M3 full-screen-dialog order, and the iOS sheet toolbar). The form scrolls under it; the actions
 * never move below the keyboard.
 */
@Composable
fun FormSheet(
    title: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    saveEnabled: Boolean = true,
    saveLabel: String = "Save",
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestCancel by rememberUpdatedState(onCancel)
    val state =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = {
                if (it == SheetValue.Hidden) {
                    latestCancel()
                    false
                } else true
            },
        )
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onCancel() },
        sheetState = state,
        sheetMaxWidth = 640.dp,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding()) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.s),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterStart).testTag("editor-cancel"),
                ) {
                    Text("Cancel")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 112.dp),
                )
                Button(
                    onClick = onSave,
                    enabled = !busy && saveEnabled,
                    contentPadding = PaddingValues(horizontal = Space.m),
                    modifier = Modifier.align(Alignment.CenterEnd).testTag("editor-save"),
                ) {
                    Text(saveLabel, maxLines = 1)
                }
            }
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.m)
                    .padding(top = Space.s, bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
                content = content,
            )
        }
    }
}

/** Empty state, §8.2: glyph, title, one sentence, at most one action. */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Space.xl, horizontal = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let { (label, run) ->
            Spacer(Modifier.height(Space.xs))
            FilledTonalButton(onClick = run) { Text(label) }
        }
    }
}
