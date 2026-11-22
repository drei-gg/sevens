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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import gg.seven.kit.*

@Composable
internal fun SettingsScreen(m: FastModel, a: FastArchive?) {
    val context = LocalContext.current
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
                m.message.value =
                    "Notification permission was not granted. Your tracking still works."
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
            Panel("Records need attention") {
                Text(failure!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { exportJson.launch("fast-recovery.json") }) {
                    Text("Export original file")
                }
                Text(
                    "After saving the original, recover from a valid backup in a separate installation or contact support. This app will not overwrite the unreadable file."
                )
            }
        if (a != null) {
            Section(
                title = "Your data",
                footer =
                    "Stored on this device. Android may include it in device backups. No account, developer analytics or app server. Backups contain private journal and measurement records; choose where to save them.",
            ) {
                action("Export JSON backup", Icons.Outlined.Save) { exportJson.launch("fast-backup.json") }
                action("Export CSV tables", Icons.Outlined.TableChart) { exportCsv.launch("fast-records.csv") }
                action("Import & merge backup", Icons.Outlined.FileOpen) {
                    importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                action("Delete all records", Icons.Outlined.DeleteForever, destructive = true) {
                    confirmDeleteAll = true
                }
            }
            HomeWidgetSetup(android.content.ComponentName(context, FastWidget::class.java), "Fasting timer",
                "A live timer and your planned finish. Tap to open the app; journal text and measurements stay private.")
            HealthImportPanel(m)
            Section(title = "Make it yours") {
                switch("Weight in pounds", a.settings.usePounds) { saveSettings(a.settings.copy(usePounds = it)) }
                switch("Water in US fluid ounces", a.settings.useFluidOunces) {
                    saveSettings(a.settings.copy(useFluidOunces = it))
                }
                switch("Show weight", a.settings.showWeight) { saveSettings(a.settings.copy(showWeight = it)) }
                switch("Show eating window", a.settings.showEatingWindow) {
                    saveSettings(a.settings.copy(showEatingWindow = it))
                }
            }
            Section(
                title = "Reminders you choose",
                footer =
                    "Optional local notifications. Android battery settings may delay delivery. Reminders never start or finish a fast.",
            ) {
                switch("Show current timer notification", a.settings.liveActivity) {
                    saveSettings(a.settings.copy(liveActivity = it), it)
                }
                switch("Chosen fasting time reached", a.settings.goalReminder) {
                    saveSettings(a.settings.copy(goalReminder = it), it)
                }
                switch("Eating window elapsed", a.settings.eatingReminder) {
                    saveSettings(a.settings.copy(eatingReminder = it), it)
                }
                switch("Dated plans", a.settings.scheduleReminders) {
                    saveSettings(a.settings.copy(scheduleReminders = it), it)
                }
            }
            Section(title = "Weekly start reminder") {
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
                        TimeField("Time", a.settings.reminderHour, a.settings.reminderMinute) { hour, minute ->
                            saveSettings(a.settings.copy(reminderHour = hour, reminderMinute = minute))
                        }
                    }
                }
            }
            Section(
                footer =
                    if (NotificationManagerCompat.from(context).areNotificationsEnabled())
                        "Android notifications are allowed."
                    else "Android notifications are off."
            ) {
                item(
                    "Android notification settings",
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
        Section(title = "About fasting") {
            content {
                Text(
                    "This is a record, not a treatment plan. Longer is not presented as better. Timer stages do not detect ketosis, fat burning or autophagy.\n\nFasting is not appropriate for everyone, particularly minors, pregnancy or breastfeeding, a history of eating disorders, and some conditions or medicines. Ask a clinician where appropriate."
                )
            }
        }
        Section(title = "Network contact") {
            content {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        if (BuildConfig.FLAVOR == "store")
                            "Google Play handles purchases and restores through Play Billing, and may contact Google for billing diagnostics. Your tracking and journal records are never passed to billing."
                        else "This edition has no purchase code and contacts no store."
                    )
                    Text(
                        "Tracking, journal entries, reminders and measurements stay on this device. Health Connect, notifications and the home-screen widget work on the phone."
                    )
                    if (BuildConfig.FLAVOR != "generic")
                        Text(
                            "A paired Wear OS watch receives a small timer snapshot and sends the actions you choose through Google Play services, device to device. Journal, weight and reflections never leave the phone."
                        )
                    Text("Exports go only to the destination you choose.")
                }
            }
        }
        SevenAbout(coreVersion = remember { coreVersion() })
    }
    if (confirmDeleteAll)
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete every fast, journal entry, measurement, plan and rest day?") },
            text = {
                Text(
                    "This also ends any running timer and removes custom presets. Your preferences stay. Export a backup first if you want to keep a copy. This can’t be undone."
                )
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        m.mutate(
                            { FastArchive.empty().copy(settings = it.settings) },
                            onSaved = { m.message.value = "All records deleted. Preferences were kept." },
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete all records")
                }
            },
        )
}
