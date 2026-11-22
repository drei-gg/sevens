package gg.seven.fast

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import gg.seven.kit.*

@Composable
internal fun SettingsScreen(m: FastModel, a: FastArchive?) {
    val context = LocalContext.current
    val res = LocalResources.current
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val failure by m.loadFailure.collectAsStateWithLifecycle()
    val exportJson =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let { uri -> m.export(uri) }
        }
    val exportCsv =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
            it?.let { uri -> m.export(uri, true) }
        }
    val importJson =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(m::previewImport)
        }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted)
                m.message.value = res.getString(R.string.notification_permission_denied)
        }
    fun saveSettings(s: TrackerSettings, needsPermission: Boolean = false) {
        m.mutate(
            { it.copy(settings = s) },
            {
                if (needsPermission && Build.VERSION.SDK_INT >= 33)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }
    Page {
        if (failure != null)
            Panel(stringResource(R.string.recovery_title)) {
                Text(failure!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { exportJson.launch("fast-recovery.json") }) {
                    Text(stringResource(R.string.recovery_export))
                }
                Text(stringResource(R.string.recovery_detail))
            }
        if (a != null) {
            Section(
                title = stringResource(R.string.settings_data),
                footer = stringResource(R.string.settings_data_footer),
            ) {
                action(res.getString(R.string.settings_export_json), Icons.Outlined.Save) {
                    exportJson.launch("fast-backup.json")
                }
                action(res.getString(R.string.settings_export_csv), Icons.Outlined.TableChart) {
                    exportCsv.launch("fast-records.csv")
                }
                action(res.getString(R.string.settings_import), Icons.Outlined.FileOpen) {
                    importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                action(
                    res.getString(R.string.delete_all_records),
                    Icons.Outlined.DeleteForever,
                    destructive = true,
                ) {
                    confirmDeleteAll = true
                }
            }
            HomeWidgetSetup(
                android.content.ComponentName(context, FastWidget::class.java),
                stringResource(R.string.widget_name),
                stringResource(R.string.settings_widget_detail),
            )
            HealthImportPanel(m)
            Section(title = stringResource(R.string.settings_make_it_yours)) {
                switch(res.getString(R.string.settings_pounds), a.settings.usePounds) {
                    saveSettings(a.settings.copy(usePounds = it))
                }
                switch(res.getString(R.string.settings_fluid_ounces), a.settings.useFluidOunces) {
                    saveSettings(a.settings.copy(useFluidOunces = it))
                }
                switch(res.getString(R.string.settings_show_weight), a.settings.showWeight) {
                    saveSettings(a.settings.copy(showWeight = it))
                }
                switch(res.getString(R.string.settings_show_eating_window), a.settings.showEatingWindow) {
                    saveSettings(a.settings.copy(showEatingWindow = it))
                }
            }
            Section(
                title = stringResource(R.string.settings_reminders),
                footer = stringResource(R.string.settings_reminders_footer),
            ) {
                switch(res.getString(R.string.settings_timer_notification), a.settings.liveActivity) {
                    saveSettings(a.settings.copy(liveActivity = it), it)
                }
                switch(res.getString(R.string.settings_goal_reminder), a.settings.goalReminder) {
                    saveSettings(a.settings.copy(goalReminder = it), it)
                }
                switch(res.getString(R.string.settings_eating_reminder), a.settings.eatingReminder) {
                    saveSettings(a.settings.copy(eatingReminder = it), it)
                }
                switch(res.getString(R.string.settings_plan_reminders), a.settings.scheduleReminders) {
                    saveSettings(a.settings.copy(scheduleReminders = it), it)
                }
            }
            Section(title = stringResource(R.string.settings_weekly_reminder)) {
                content {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s)
                        ) {
                            val names = java.time.format.TextStyle.SHORT
                            (1..7).forEach { day ->
                                val label =
                                    java.time.DayOfWeek.of((day + 5) % 7 + 1).getDisplayName(names, androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                                val on = day in a.settings.reminderWeekdays
                                FilterChip(
                                    selected = on,
                                    onClick = {
                                        saveSettings(
                                            a.settings.copy(
                                                reminderWeekdays =
                                                    if (on) a.settings.reminderWeekdays - day
                                                    else (a.settings.reminderWeekdays + day).distinct()
                                            ),
                                            !on,
                                        )
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        TimeField(stringResource(R.string.settings_time), a.settings.reminderHour, a.settings.reminderMinute) { hour, minute ->
                            saveSettings(a.settings.copy(reminderHour = hour, reminderMinute = minute))
                        }
                    }
                }
            }
            Section(
                footer =
                    stringResource(
                        if (NotificationManagerCompat.from(context).areNotificationsEnabled())
                            R.string.settings_notifications_allowed
                        else R.string.settings_notifications_off
                    )
            ) {
                item(
                    res.getString(R.string.settings_notification_settings),
                    icon = Icons.Outlined.Notifications,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    },
                )
            }
        }
        Section(title = stringResource(R.string.settings_about_fasting)) {
            content { Text(stringResource(R.string.settings_about_fasting_text)) }
        }
        Section(title = stringResource(R.string.settings_network)) {
            content {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        stringResource(
                            if (BuildConfig.FLAVOR == "store") R.string.settings_network_store
                            else R.string.settings_network_none
                        )
                    )
                    Text(stringResource(R.string.settings_network_local))
                    if (BuildConfig.FLAVOR != "generic") Text(stringResource(R.string.settings_network_wear))
                    Text(stringResource(R.string.settings_network_exports))
                }
            }
        }
        SevenAbout(coreVersion = remember { coreVersion() })
    }
    if (confirmDeleteAll)
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.delete_all_title)) },
            text = { Text(stringResource(R.string.delete_all_message)) },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        m.mutate(
                            { FastArchive.empty().copy(settings = it.settings) },
                            onSaved = { m.message.value = res.getString(R.string.delete_all_done) },
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.delete_all_records))
                }
            },
        )
}
