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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    val res = LocalResources.current
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
            title = stringResource(R.string.settings_delivery),
            footer = stringResource(R.string.settings_delivery_footer),
        ) {
            item(
                status,
                supporting = scheduling.takeIf { it != status },
                icon = Icons.Outlined.NotificationsActive,
            )
            action(res.getString(R.string.settings_test_alarm), Icons.Outlined.Alarm) {
                model.run { testAlarm() }
            }
            action(
                res.getString(R.string.settings_request_permissions),
                Icons.Outlined.BackHand,
                onClick = allow,
            )
            action(res.getString(R.string.settings_open_system), Icons.Outlined.Settings) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${context.packageName}".toUri(),
                    )
                )
            }
            action(res.getString(R.string.settings_retry), Icons.Outlined.Refresh) {
                model.run { synchronize() }
            }
        }
        val widgetError by engine.widgetError.collectAsStateWithLifecycle()
        Section(
            title = stringResource(R.string.settings_widget),
            footer = stringResource(R.string.settings_widget_footer),
        ) {
            widgetError?.let { item(it, icon = Icons.Outlined.ErrorOutline) }
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) {
                action(res.getString(R.string.settings_add_widget), Icons.Outlined.Widgets) {
                    manager.requestPinAppWidget(
                        android.content.ComponentName(context, AlarmWidget::class.java),
                        null,
                        null,
                    )
                }
            } else item(res.getString(R.string.settings_widget_picker))
        }
        Section(title = stringResource(R.string.settings_less_setup)) {
            item(
                res.getString(R.string.settings_defaults),
                icon = Icons.Outlined.Tune,
                onClick = { defaults = true },
            )
        }
        Section(
            title = stringResource(R.string.settings_data),
            footer = stringResource(R.string.settings_data_footer),
        ) {
            action(res.getString(R.string.settings_export_backup), Icons.Outlined.Share) {
                exportJson.launch("alarm-loops-backup.json")
            }
            action(res.getString(R.string.settings_export_csv), Icons.Outlined.TableChart) {
                exportCsv.launch("alarm-loops-history.csv")
            }
            action(res.getString(R.string.settings_import), Icons.Outlined.Download) {
                importer.launch(
                    arrayOf("application/json", "application/octet-stream", "text/plain")
                )
            }
        }
        Section(
            title = stringResource(R.string.settings_troubleshooting),
            footer = stringResource(R.string.settings_troubleshooting_footer),
        ) {
            action(
                res.getString(R.string.settings_export_diagnostics),
                Icons.Outlined.MedicalServices,
            ) {
                exportDiagnostics.launch("alarm-loops-diagnostics.json")
            }
        }
        SevenAbout(coreVersion = remember { coreVersion() })
        Footer(stringResource(R.string.settings_codes_footer))
        Spacer(Modifier.height(Space.l))
    }
    if (defaults)
        DefaultsEditor(model, archive.alarmDefaults ?: defaultAlarmDefaults()) { defaults = false }
    pendingImport?.let { incoming ->
        AlertDialog(
            onDismissRequest = { model.importPreview.value = null },
            title = {
                val records =
                    pluralStringResource(
                        R.plurals.history_records,
                        incoming.events.size,
                        incoming.events.size,
                    )
                Text(
                    pluralStringResource(
                        R.plurals.import_routines,
                        incoming.routines.size,
                        incoming.routines.size,
                        records,
                    )
                )
            },
            text = { Text(stringResource(R.string.settings_import_body)) },
            confirmButton = {
                TextButton(
                    onClick = model::confirmImport,
                    modifier = Modifier.testTag("confirm-import"),
                ) {
                    Text(stringResource(R.string.settings_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { model.importPreview.value = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
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
        title = stringResource(R.string.settings_defaults),
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
        DropdownField(
            stringResource(R.string.defaults_tone),
            defaults.tone,
            AlarmTone.entries.map { it to it.title },
        ) {
            setDefaults(defaults.copy(tone = it))
        }
        StepRow(
            stringResource(R.string.defaults_snooze, ClockFormat.minutes(defaults.snoozeMinutes)),
            defaults.snoozeMinutes,
            1..30,
        ) {
            setDefaults(defaults.copy(snoozeMinutes = it))
        }
        StepRow(
            if (defaults.snoozeLimit == 0) stringResource(R.string.defaults_no_snoozes)
            else stringResource(R.string.defaults_snooze_limit, defaults.snoozeLimit),
            defaults.snoozeLimit,
            0..10,
        ) {
            setDefaults(defaults.copy(snoozeLimit = it))
        }
        StepRow(
            if (defaults.followUpMinutes == 0) stringResource(R.string.defaults_check_off)
            else
                stringResource(
                    R.string.defaults_check_after,
                    ClockFormat.minutes(defaults.followUpMinutes),
                ),
            defaults.followUpMinutes,
            0..30,
        ) {
            setDefaults(defaults.copy(followUpMinutes = it))
        }
        Text(
            stringResource(R.string.defaults_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { setDefaults(defaultAlarmDefaults()) }) {
            Icon(Icons.Outlined.Undo, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.defaults_reset))
        }
    }
}
