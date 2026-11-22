@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
)

package gg.seven.alarm

import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.alarm.core.*
import gg.seven.kit.EmptyState
import gg.seven.kit.SearchInput
import gg.seven.kit.Section
import gg.seven.kit.Space
import gg.seven.kit.numeric
import gg.seven.kit.switch
import java.time.Instant

/**
 * Alarms and Time loops (iOS RoutinesScreen): the next one big in a dial at the top, one way to
 * add, quick naps, then every routine as a card with its time large and its switch.
 */
@Composable
fun RoutinesScreen(model: AlarmModel, kind: RoutineKind) {
    val engine = model.engine
    val archive by engine.archive.collectAsStateWithLifecycle()
    val scheduling by engine.scheduling.collectAsStateWithLifecycle()
    val now = rememberNow()
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var deleting by remember { mutableStateOf<Routine?>(null) }
    var customNap by remember { mutableStateOf(false) }
    val view = LocalView.current
    val all = archive.routines.filter { it.kind == kind }
    val routines =
        all.filter {
                query.isEmpty() ||
                    it.name.contains(query, true) ||
                    (it.group ?: "").contains(query, true)
            }
            .sortedWith(compareBy<Routine> { it.next(now) ?: Instant.MAX }.thenBy { it.name })
    val groups = routineGroups(archive.routines)
    val allow = rememberAllowAlarms { model.run { synchronize() } }

    LazyColumn(
        Modifier.fillMaxSize().testTag("routines-${kind.name}"),
        contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item(key = "hero") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = Space.s),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Hero(kind, all, now)
                PrimaryButton(
                    if (kind == RoutineKind.ALARM) "Add alarm" else "Add loop",
                    Modifier.testTag("add-routine"),
                    icon = Icons.Outlined.Add,
                ) {
                    model.draft.value = engine.newRoutine(kind)
                }
            }
        }
        if (kind == RoutineKind.ALARM)
            item(key = "naps") {
                Column(
                    Modifier.padding(top = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Text(
                        "Quick nap",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val columns = if (largeText()) 2 else 4
                    listOf(15, 20, 30, 60).chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            row.forEach { minutes ->
                                OutlinedButton(
                                    onClick = {
                                        model.run {
                                            if (nap(minutes, model.paid)) Haptics.confirm(view)
                                        }
                                    },
                                    modifier =
                                        Modifier.weight(1f)
                                            .heightIn(min = 56.dp)
                                            .testTag("nap-$minutes"),
                                    contentPadding =
                                        PaddingValues(horizontal = Space.xs, vertical = Space.xs),
                                ) {
                                    Column(
                                        Modifier.clearAndSetSemantics {
                                            contentDescription =
                                                "Nap for ${ClockFormat.minutes(minutes)}"
                                        },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            "$minutes",
                                            style = MaterialTheme.typography.titleMedium.numeric(),
                                            maxLines = 1,
                                        )
                                        Text(
                                            "min",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = { customNap = true },
                        modifier = Modifier.testTag("custom-nap"),
                    ) {
                        Icon(Icons.Outlined.Timer, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Custom nap…")
                    }
                }
            }
        if (archive.installed.any { it.purpose == "followUp" })
            item(key = "checks") {
                OutlinedButton(
                    onClick = { model.run { clearWakeUpChecks() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("I’m awake. Cancel wake-up checks")
                }
            }
        if (groups.isNotEmpty())
            item(key = "groups") {
                Section(Modifier.padding(top = Space.s), title = "Groups") {
                    groups.forEach { group ->
                        val on =
                            archive.routines.filter { it.group?.trim() == group }.all { it.enabled }
                        switch(group, on) { value ->
                            model.run { toggleGroup(group, value, model.paid) }
                        }
                    }
                }
            }
        if (all.size >= 8)
            item(key = "search") { SearchInput("Find a routine or group", state = search) }
        if (query.isNotEmpty() && routines.isEmpty())
            item(key = "no-results") {
                EmptyState(
                    "No results for “$query”",
                    "Try another name or group.",
                    icon = Icons.Outlined.Search,
                )
            }
        items(routines, key = { it.id }) { routine ->
            RoutineCard(model, routine, now, onDelete = { deleting = routine })
        }
        item(key = "status") {
            Column(
                Modifier.padding(top = Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.ms),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.NotificationsActive,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        scheduling,
                        Modifier.testTag("scheduling-status"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!engine.isPreview)
                    TextButton(onClick = allow) { Text("Allow alarms and reminders") }
            }
        }
    }
    if (customNap) CustomNapSheet(model) { customNap = false }
    deleting?.let { routine ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this routine?") },
            text = { Text("History will stay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.run { delete(routine) }
                        deleting = null
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text("Delete routine")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** The next routine to ring, big, in a 24-hour dial. At large text sizes the dial steps aside. */
@Composable
private fun Hero(kind: RoutineKind, all: List<Routine>, now: Instant) {
    val upcoming = all.mapNotNull { r -> r.next(now)?.let { r to it } }.minByOrNull { it.second }
    val center: @Composable () -> Unit = {
        Column(
            Modifier.semantics(mergeDescendants = true) {}.testTag("next-hero"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (upcoming != null) {
                val (routine, date) = upcoming
                Text(
                    if (kind == RoutineKind.ALARM) "Next alarm" else "Next loop",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClockText(
                    ClockFormat.parts(date),
                    Modifier.testTag("next-time"),
                    role = ClockRole.Hero,
                )
                Text(
                    routine.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${ClockFormat.day(date, now)}, ${ClockFormat.countdown(date, now)}",
                    style = MaterialTheme.typography.bodySmall.numeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Icon(
                    if (kind == RoutineKind.ALARM) Icons.Outlined.Alarm else Icons.Outlined.Repeat,
                    null,
                    Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        all.isEmpty() && kind == RoutineKind.ALARM -> "No alarms yet"
                        all.isEmpty() -> "No loops yet"
                        kind == RoutineKind.ALARM -> "All alarms off"
                        else -> "All loops off"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    when {
                        all.isNotEmpty() -> "Turn one on below, or add another."
                        kind == RoutineKind.ALARM -> "Add a wake-up time and an optional mission."
                        else -> "Several times. Different weekdays. One routine."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (largeText()) center()
    else DayDial(now, upcoming?.second, Modifier.widthIn(max = 320.dp).fillMaxWidth()) { center() }
}

/**
 * One routine: its time large, its name and days under it, and its switch. Routines with several
 * times list each time with its own days. Tap to edit; long-press (or TalkBack's actions) for
 * Duplicate, Try mission, Log done and Delete.
 */
@Composable
private fun RoutineCard(model: AlarmModel, routine: Routine, now: Instant, onDelete: () -> Unit) {
    val engine = model.engine
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    var templateConsent by rememberSaveable { mutableStateOf(false) }
    val templateFile =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
            model::exportTemplate,
        )
    if (templateConsent)
        AlertDialog(
            onDismissRequest = { templateConsent = false },
            title = { Text("Save a routine template?") },
            text = {
                Text(
                    "Includes this routine's name, reason, times and mission phrase or scanned code. History, delivery status and app defaults are excluded. Import from Settings; the new copy starts turned off. Imported audio files are not included.",
                    modifier =
                        Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        templateConsent = false
                        model.prepareTemplate(routine) {
                            templateFile.launch("routine-template.json")
                        }
                    }
                ) {
                    Text("Save template")
                }
            },
            dismissButton = { TextButton(onClick = { templateConsent = false }) { Text("Cancel") } },
        )
    val single =
        routine.timing == Timing.ONCE ||
            (routine.timing == Timing.WEEKLY && routine.slots.size == 1)
    val dim = !routine.enabled
    val color =
        if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val edit = { model.draft.value = routine }
    val duplicate = { model.run { duplicate(routine, model.paid) } }
    val log = { model.run { manual(routine, Instant.now()) } }
    val tryMission = { engine.previewMission.value = routine }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("routine-card-${routine.name}"),
    ) {
        Box {
            Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(
                    verticalAlignment = if (single) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Column(
                        Modifier.weight(1f)
                            .combinedClickable(
                                onClick = edit,
                                onClickLabel = "Edit",
                                onLongClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    menu = true
                                },
                                onLongClickLabel = "More actions",
                            )
                            .semantics(mergeDescendants = true) {
                                customActions =
                                    listOfNotNull(
                                        CustomAccessibilityAction("Duplicate") {
                                            duplicate()
                                            true
                                        },
                                        if (routine.mission != MissionKind.NONE)
                                            CustomAccessibilityAction("Try mission") {
                                                tryMission()
                                                true
                                            }
                                        else null,
                                        CustomAccessibilityAction("Log done") {
                                            log()
                                            true
                                        },
                                        CustomAccessibilityAction("Delete") {
                                            onDelete()
                                            true
                                        },
                                    )
                            }
                            .testTag("edit-${routine.name}"),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Summary(routine, single, color, dim, now)
                    }
                    IconButton(
                        onClick = { menu = true },
                        modifier = Modifier.testTag("routine-options-${routine.name}"),
                    ) {
                        Icon(Icons.Outlined.MoreVert, "Actions for ${routine.name}")
                    }
                    Switch(
                        checked = routine.enabled,
                        onCheckedChange = { value ->
                            model.run { toggle(routine, value, model.paid) }
                        },
                        modifier =
                            Modifier.testTag("routine-${routine.name}").semantics {
                                contentDescription = routine.name
                            },
                    )
                }
                if (routine.mission != MissionKind.NONE || routine.kind == RoutineKind.LOOP)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (routine.mission != MissionKind.NONE)
                            AssistChip(
                                onClick = tryMission,
                                label = { Text(routine.mission.title) },
                                leadingIcon = {
                                    Icon(
                                        routine.mission.icon,
                                        null,
                                        Modifier.size(AssistChipDefaults.IconSize),
                                    )
                                },
                                modifier = Modifier.testTag("try-mission"),
                            )
                        if (routine.kind == RoutineKind.LOOP)
                            AssistChip(
                                onClick = log,
                                label = { Text("Log done") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Check,
                                        null,
                                        Modifier.size(AssistChipDefaults.IconSize),
                                    )
                                },
                                modifier = Modifier.testTag("log-done"),
                            )
                    }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    onClick = {
                        menu = false
                        edit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = {
                        menu = false
                        duplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Save routine template") },
                    leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                    onClick = {
                        menu = false
                        templateConsent = true
                    },
                )
                if (routine.mission != MissionKind.NONE)
                    DropdownMenuItem(
                        text = { Text("Try mission") },
                        leadingIcon = { Icon(routine.mission.icon, null) },
                        onClick = {
                            menu = false
                            tryMission()
                        },
                    )
                DropdownMenuItem(
                    text = { Text("Log done") },
                    leadingIcon = { Icon(Icons.Outlined.Check, null) },
                    onClick = {
                        menu = false
                        log()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun Summary(
    routine: Routine,
    single: Boolean,
    color: androidx.compose.ui.graphics.Color,
    dim: Boolean,
    now: Instant,
) {
    @Composable
    fun NameRow(bold: Boolean) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                routine.name,
                Modifier.weight(1f, fill = false),
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                    ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            routine.group
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
        }
    }
    if (single) {
        ClockText(
            if (routine.timing == Timing.ONCE) ClockFormat.parts(routine.once)
            else ClockFormat.parts(routine.slots[0].minute),
            color = color,
        )
        NameRow(false)
        if (routine.timing == Timing.ONCE)
            Text(
                ClockFormat.longDay(routine.once),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else DayLetters(routine.slots[0].weekdays, dimmed = dim)
    } else {
        NameRow(true)
        if (routine.timing == Timing.WINDOW) {
            Text(
                "${ClockFormat.time(routine.windowStart)}–${ClockFormat.time(routine.windowEnd)}",
                style = MaterialTheme.typography.titleLarge.numeric(),
                color = color,
            )
            Text(
                "Every ${ClockFormat.minutes(routine.intervalMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DayLetters(routine.windowDays, dimmed = dim)
        } else
            routine.slots.forEach { slot ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.ms),
                ) {
                    ClockText(
                        ClockFormat.parts(slot.minute),
                        role = ClockRole.Section,
                        color = color,
                    )
                    Spacer(Modifier.weight(1f))
                    DayLetters(slot.weekdays, dimmed = dim)
                }
            }
    }
    NextLabel(routine, now)
}

/** "Thursday, in 14 hr, 46 min", or Off. */
@Composable
fun NextLabel(routine: Routine, now: Instant) {
    val text =
        when {
            !routine.enabled -> "Off"
            else ->
                routine.next(now)?.let {
                    "${ClockFormat.day(it, now)}, ${ClockFormat.countdown(it, now)}"
                } ?: if (routine.timing == Timing.ONCE) "Time has passed" else null
        }
    text?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall.numeric(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A nap of any length: common presets and exact minute entry, with the ring time beneath and one
 * button to start.
 */
@Composable
fun CustomNapSheet(model: AlarmModel, onClose: () -> Unit) {
    var duration by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(25) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val valid = duration in 1..720
    val now = rememberNow()
    val view = LocalView.current
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = Space.m)
                .padding(bottom = Space.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "Custom nap",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            gg.seven.kit.ChoiceChips(
                listOf(15, 25, 45, 90).map { it to "$it min" },
                duration,
                enabled = !saving,
            ) {
                if (!saving) duration = it
            }
            gg.seven.kit.IntegerSetting(
                "Duration in minutes",
                duration,
                1..720,
                enabled = !saving,
            ) {
                if (!saving) duration = it
            }
            Column(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (valid) {
                    Text(
                        "Rings at",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ClockText(
                        ClockFormat.parts(now.plusSeconds(duration * 60L)),
                        role = ClockRole.Hero,
                    )
                } else
                    Text(
                        "Choose between 1 minute and 12 hours.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            PrimaryButton(
                "Start nap",
                Modifier.testTag("start-custom-nap"),
                icon = Icons.Outlined.Bedtime,
                enabled = valid && !saving,
            ) {
                saving = true
                model.run {
                    if (nap(duration, model.paid)) {
                        Haptics.confirm(view)
                        onClose()
                    } else {
                        failure = error.value
                        error.value = null
                    }
                    saving = false
                }
            }
            failure?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "Uses your default sound, snooze and wake-up check. You can edit the nap after starting it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
