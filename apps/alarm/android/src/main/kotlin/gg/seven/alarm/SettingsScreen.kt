@file:OptIn(ExperimentalMaterial3Api::class)

package gg.seven.alarm

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.testTag
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.alarm.core.AlarmDefaults
import gg.seven.alarm.core.AlarmTone
import gg.seven.alarm.core.coreVersion
import gg.seven.alarm.core.defaultAlarmDefaults
import gg.seven.kit.DropdownField
import gg.seven.kit.FullScreenForm
import gg.seven.kit.Section
import gg.seven.kit.SevenAbout
import gg.seven.kit.Space
import gg.seven.kit.action
import gg.seven.kit.item

/**
 * Settings (iOS SettingsScreen): delivery status first, what the phone will actually do, then
 * setup, your data, troubleshooting and About. Explanations sit under each group.
 */
@Composable
fun SettingsScreen(model: AlarmModel) {
    val engine = model.engine
    val context = LocalContext.current
    val archive by engine.archive.collectAsStateWithLifecycle()
    val scheduling by engine.scheduling.collectAsStateWithLifecycle()
    val pendingImport by model.importPreview.collectAsStateWithLifecycle()
    var defaults by rememberSaveable { mutableStateOf(false) }
    val allow = rememberAllowAlarms { model.run { synchronize() } }
    val exportJson =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let(model::exportBackup)
        }
    val exportCsv =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
            it?.let(model::exportCsv)
        }
    val exportDiagnostics =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let(model::exportDiagnostics)
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(model::previewImport)
        }
    // Read each time the screen recomposes (it does on resume), so a permission granted in system
    // settings shows at once.
    val status = engine.driver.status

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Section(
            title = "Alarm delivery",
            footer =
                "Uses your selected tone at the alarm volume. Test on your phone before relying on an alarm. The ringing alarm always has its own Stop button.",
        ) {
            item(
                status,
                supporting = scheduling.takeIf { it != status },
                icon = Icons.Outlined.NotificationsActive,
            )
            action("Ring a test alarm in 10 seconds", Icons.Outlined.Alarm) {
                model.run { testAlarm() }
            }
            action("Request permissions", Icons.Outlined.BackHand, onClick = allow)
            action("Open system settings", Icons.Outlined.Settings) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri(),
                    )
                )
            }
            action("Retry schedule registration", Icons.Outlined.Refresh) {
                model.run { synchronize() }
            }
        }
        val widgetError by engine.widgetError.collectAsStateWithLifecycle()
        Section(
            title = "Home Screen widget",
            footer =
                "Shows upcoming saved alarms and loops, including routine names. Refreshes after changes and expires after a day without an app check. A schedule is not proof of delivery.",
        ) {
            widgetError?.let { item(it, icon = Icons.Outlined.ErrorOutline) }
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) {
                action("Add schedule widget", Icons.Outlined.Widgets) {
                    manager.requestPinAppWidget(
                        android.content.ComponentName(context, AlarmWidget::class.java),
                        null,
                        null,
                    )
                }
            } else item("Add Saved schedule from your launcher’s widget picker.")
        }
        Section(title = "Less setup") {
            item("New alarm defaults", icon = Icons.Outlined.Tune, onClick = { defaults = true })
        }
        Section(
            title = "Your data",
            footer =
                "Stored on this phone. No accounts, ads or analytics. Your phone’s backup may include these records. Imports add new routines turned off; they never arm alarms on their own. JSON backups retain sound selections but not audio files; re-import missing sounds or use their built-in fallback.",
        ) {
            action("Export JSON backup", Icons.Outlined.Share) {
                exportJson.launch("alarm-loops-backup.json")
            }
            action("Export history CSV", Icons.Outlined.TableChart) {
                exportCsv.launch("alarm-loops-history.csv")
            }
            action("Import backup", Icons.Outlined.Download) {
                importer.launch(
                    arrayOf("application/json", "application/octet-stream", "text/plain")
                )
            }
        }
        Section(
            title = "Troubleshooting",
            footer =
                "A local report with app and Android versions, alarm permission, registration counts and error flags. Leaves out names, schedules, scanned codes, phrases and history. Nothing is sent automatically. Registration counts don’t prove an alarm rang.",
        ) {
            action("Export diagnostics", Icons.Outlined.MedicalServices) {
                exportDiagnostics.launch("alarm-loops-diagnostics.json")
            }
        }
        SevenAbout(coreVersion = remember { coreVersion() })
        Footer("Scanned mission codes stay on this phone and are never opened as links.")
        Spacer(Modifier.height(Space.l))
    }
    if (defaults)
        DefaultsEditor(model, archive.alarmDefaults ?: defaultAlarmDefaults()) { defaults = false }
    pendingImport?.let { incoming ->
        AlertDialog(
            onDismissRequest = { model.importPreview.value = null },
            title = {
                Text(
                    "Import ${incoming.routines.size} routines and ${incoming.events.size} history records?"
                )
            },
            text = { Text("Existing routine IDs are kept; new routines start turned off.") },
            confirmButton = {
                TextButton(
                    onClick = model::confirmImport,
                    modifier = Modifier.testTag("confirm-import"),
                ) {
                    Text("Import routines turned off")
                }
            },
            dismissButton = {
                TextButton(onClick = { model.importPreview.value = null }) { Text("Cancel") }
            },
        )
    }
}

/** Reusable defaults for new alarms and naps (iOS AlarmDefaultsEditor). */
@Composable
private fun DefaultsEditor(model: AlarmModel, initial: AlarmDefaults, onClose: () -> Unit) {
    var tone by rememberSaveable { mutableStateOf(initial.tone) }
    var snooze by rememberSaveable { mutableIntStateOf(initial.snoozeMinutes) }
    var limit by rememberSaveable { mutableIntStateOf(initial.snoozeLimit) }
    var followUp by rememberSaveable { mutableIntStateOf(initial.followUpMinutes) }
    val defaults =
        initial.copy(
            tone = tone,
            snoozeMinutes = snooze,
            snoozeLimit = limit,
            followUpMinutes = followUp,
        )
    fun setDefaults(value: AlarmDefaults) {
        tone = value.tone
        snooze = value.snoozeMinutes
        limit = value.snoozeLimit
        followUp = value.followUpMinutes
    }
    var saving by remember { mutableStateOf(false) }
    FullScreenForm(
        title = "New alarm defaults",
        onClose = onClose,
        onSave = {
            saving = true
            model.run {
                if (saveDefaults(defaults)) onClose()
                saving = false
            }
        },
        busy = saving,
        confirmDiscard = defaults != initial,
    ) {
        DropdownField("Tone", defaults.tone, AlarmTone.entries.map { it to it.title }) {
            setDefaults(defaults.copy(tone = it))
        }
        StepRow(
            "Snooze: ${ClockFormat.minutes(defaults.snoozeMinutes)}",
            defaults.snoozeMinutes,
            1..30,
        ) {
            setDefaults(defaults.copy(snoozeMinutes = it))
        }
        StepRow(
            if (defaults.snoozeLimit == 0) "No snoozes"
            else "Snooze limit: ${defaults.snoozeLimit}",
            defaults.snoozeLimit,
            0..10,
        ) {
            setDefaults(defaults.copy(snoozeLimit = it))
        }
        StepRow(
            if (defaults.followUpMinutes == 0) "Wake-up check: off"
            else "Check again after ${ClockFormat.minutes(defaults.followUpMinutes)}",
            defaults.followUpMinutes,
            0..30,
        ) {
            setDefaults(defaults.copy(followUpMinutes = it))
        }
        Text(
            "Applies to new alarms and naps. Existing routines and quiet reminders keep their settings. Duplicates keep the original’s settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { setDefaults(defaultAlarmDefaults()) }) {
            Icon(Icons.Outlined.Undo, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Reset defaults")
        }
    }
}
