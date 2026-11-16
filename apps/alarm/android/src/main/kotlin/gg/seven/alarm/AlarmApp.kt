@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package gg.seven.alarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.alarm.core.RoutineKind
import gg.seven.kit.EmptyState
import gg.seven.kit.SevenTopBar
import gg.seven.kit.Space
import gg.seven.kit.numeric
import java.time.Instant
import kotlinx.coroutines.delay

/** Alarms, Loops, History and Settings: the four peer areas of the iOS tab bar. */
enum class Tab(val title: String, val icon: ImageVector) {
    Alarms("Alarms", Icons.Outlined.Alarm),
    Loops("Loops", Icons.Outlined.Repeat),
    History("History", Icons.Outlined.CalendarMonth),
    Settings("Settings", Icons.Outlined.Settings),
}

@Composable
fun AlarmApp(model: AlarmModel) {
    val engine = model.engine
    val ready by engine.ready.collectAsStateWithLifecycle()
    val loadError by engine.loadError.collectAsStateWithLifecycle()
    val error by engine.error.collectAsStateWithLifecycle()
    val mission by engine.mission.collectAsStateWithLifecycle()
    val preview by engine.previewMission.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val note by model.note.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Alarms) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(note) {
        val shown = note ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(shown.text, actionLabel = if (shown.undo != null) "Undo" else null, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) shown.undo?.invoke()
        model.note.value = null
    }
    when {
        !ready && loadError != null ->
            Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        "Couldn’t open your records",
                        loadError.orEmpty(),
                        icon = Icons.Outlined.SdCardAlert,
                        action = "Try again" to { model.run { reload() } },
                    )
                }
            }
        !ready ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Local data opens in a frame; the indicator only appears if it doesn't.
                var show by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(300)
                    show = true
                }
                if (show) LoadingIndicator()
            }
        else -> {
            val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (wide)
                    NavigationRail(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.safeDrawingPadding()) {
                        Spacer(Modifier.weight(1f))
                        Tab.entries.forEach { item ->
                            NavigationRailItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Icon(item.icon, null) },
                                label = { Text(item.title) },
                                modifier = Modifier.testTag("tab-${item.title}"),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { SevenTopBar(if (tab == Tab.Loops) "Time loops" else tab.title) },
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (!wide)
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Tab.entries.forEach { item ->
                                    NavigationBarItem(
                                        selected = tab == item,
                                        onClick = { tab = item },
                                        icon = { Icon(item.icon, null) },
                                        label = { Text(item.title) },
                                        modifier = Modifier.testTag("tab-${item.title}"),
                                    )
                                }
                            }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 672.dp).fillMaxSize()) {
                            when (tab) {
                                Tab.Alarms -> RoutinesScreen(model, RoutineKind.ALARM)
                                Tab.Loops -> RoutinesScreen(model, RoutineKind.LOOP)
                                Tab.History -> HistoryScreen(model)
                                Tab.Settings -> SettingsScreen(model)
                            }
                        }
                    }
                }
            }
        }
    }
    draft?.let { RoutineEditor(model, it) }
    preview?.let { MissionScreen(model, it, null) { engine.previewMission.value = null } }
    // A real mission covers everything and can't be dismissed by back (iOS interactiveDismissDisabled).
    mission?.let { MissionScreen(model, it.routine, it) {} }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { engine.error.value = null },
            confirmButton = { TextButton(onClick = { engine.error.value = null }) { Text("OK") } },
            title = { Text("Needs attention") },
            text = { Text(message) },
        )
    }
}

// MARK: shared pieces

/** The time now, refreshed every [period] milliseconds (iOS TimelineView(.periodic)). */
@Composable
fun rememberNow(period: Long = 30_000): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(period)
        }
    }
    return now
}

/** Whether text is at an accessibility size, where layouts stack instead of sitting side by side. */
@Composable
fun largeText(): Boolean = LocalDensity.current.fontScale >= 1.5f

/** The one filled button on a screen, full width up to a comfortable measure. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(ButtonDefaults.iconSizeFor(56.dp)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(56.dp)))
        }
        Text(text, style = ButtonDefaults.textStyleFor(56.dp))
    }
}

/**
 * A number set with − and + (the iOS Stepper's job): the value is always visible and each tap is
 * exact.
 */
@Composable
fun StepRow(label: String, value: Int, range: IntRange, modifier: Modifier = Modifier, step: Int = 1, onChange: (Int) -> Unit) {
    val view = LocalView.current
    fun set(next: Int) {
        val clamped = next.coerceIn(range)
        if (clamped != value) {
            Haptics.tick(view)
            onChange(clamped)
        }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.numeric())
        FilledTonalIconButton(onClick = { set(value - step) }, enabled = value > range.first, modifier = Modifier.testTag("$label-minus")) {
            Icon(Icons.Outlined.Remove, "Decrease")
        }
        FilledTonalIconButton(onClick = { set(value + step) }, enabled = value < range.last, modifier = Modifier.testTag("$label-plus")) {
            Icon(Icons.Outlined.Add, "Increase")
        }
    }
}

/** A section heading in our own layouts (Material's list-section label, in ink). */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = Space.m, end = Space.m, top = Space.s, bottom = Space.s),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** An explanation under a group (iOS section footer). */
@Composable
fun Footer(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = Space.m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Seven round day toggles (filled on the days the time rings) and a menu of presets, as Android's
 * Clock sets alarm days. At least one day stays selected.
 */
@Composable
fun WeekdayPicker(days: List<Int>, modifier: Modifier = Modifier, onChange: (List<Int>) -> Unit) {
    val view = LocalView.current
    var presets by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            WeekOrder.days.forEach { day ->
                val on = day in days
                ToggleButton(
                    checked = on,
                    onCheckedChange = {
                        if (on && days.size == 1) return@ToggleButton
                        Haptics.select(view)
                        onChange(if (on) days - day else (days + day).sorted())
                    },
                    shapes = ToggleButtonShapes(CircleShape, CircleShape, CircleShape),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = WeekOrder.name(day) }.testTag("day-$day"),
                ) {
                    Text(WeekOrder.letter(day), textAlign = TextAlign.Center)
                }
            }
        }
        Box {
            TextButton(onClick = { presets = true }) { Text("Choose days") }
            DropdownMenu(presets, onDismissRequest = { presets = false }) {
                listOf("Every day" to listOf(1, 2, 3, 4, 5, 6, 7), "Weekdays" to listOf(2, 3, 4, 5, 6), "Weekends" to listOf(1, 7)).forEach { (label, preset) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onChange(preset)
                            presets = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Asks for what alarms need, in order: notifications (the ringing screen and reminders), exact
 * alarms, then full-screen alarms over the lock screen. Anything Android already allows is
 * skipped. [onDone] runs after the last step; the app re-checks when it resumes.
 */
@Composable
fun rememberAllowAlarms(onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("alarm-permissions", Context.MODE_PRIVATE) }
    fun settings() {
        val driver = NativeAlarmDriver(context)
        val app = "package:${context.packageName}".toUri()
        val exact = !driver.exactAllowed
        val fullScreen = !driver.fullScreenAllowed
        val intent =
            when {
                exact -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, app)
                fullScreen && Build.VERSION.SDK_INT >= 34 -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app)
                !driver.notificationsAllowed ->
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else -> null
            }
        intent?.let { runCatching { context.startActivity(it) } }
        onDone()
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            prefs.edit { putBoolean("asked", true) }
            settings()
        }
    return {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                !prefs.getBoolean("asked", false) &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        )
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else settings()
    }
}

/** Haptics that confirm (polish guide §3.8), on the constants each Android version has. */
object Haptics {
    fun confirm(view: View) =
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    fun reject(view: View) =
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    fun select(view: View) =
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
