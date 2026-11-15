package gg.seven.fast

import android.Manifest
import android.content.Intent
import android.os.Build
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import gg.seven.kit.*

@Composable
internal fun SettingsScreen(m: FastModel, a: FastArchive?, onUnlock: () -> Unit) {
    val context = LocalContext.current
    var notices by rememberSaveable { mutableStateOf(false) }
    val failure by m.loadFailure.collectAsStateWithLifecycle()
    val now by m.now.collectAsStateWithLifecycle()
    val purchase by m.purchase.collectAsStateWithLifecycle()
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
        Section(title = "Paid once. Yours to keep.") {
            item("Fast", m.access.label(purchase, now.toLong()), onClick = onUnlock)
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
            }
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
                                    java.time.DayOfWeek.of((day + 5) % 7 + 1).getDisplayName(names, java.util.Locale.getDefault())
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
        Panel("About ${stringResource(R.string.app_name)}") {
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.FLAVOR}"
            )
            Text(
                "A private fasting tracker with a history worth keeping. Native Kotlin and Compose. No ads or subscription."
            )
            Text("Open-source notices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sevens · GPL-3.0\nKotlin & kotlinx.serialization · Apache-2.0\nKizitonwose Calendar · MIT\nVico charts · Apache-2.0\nAndroidX & Jetpack Compose · Apache-2.0\nGoogle Play Billing (store edition only) · Android SDK terms",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { notices = true }) { Text("Read open-source licenses") }
            if (BuildConfig.FLAVOR == "store")
                Text(
                    "Google Play handles purchases and may contact Google for billing-related diagnostics. Your tracking and journal records are never passed to billing.",
                    style = MaterialTheme.typography.bodySmall,
                )
            Text(
                "Code license and full dependency notices are included in the source repository. The official name and icon are separately licensed trademarks.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (notices)
        AlertDialog(
            onDismissRequest = { notices = false },
            title = { Text("Open-source licenses") },
            text = {
                val text = remember {
                    context.assets.list("notices").orEmpty().sorted().joinToString("\n\n") { name ->
                        name +
                            "\n" +
                            context.assets.open("notices/$name").bufferedReader().use {
                                it.readText()
                            }
                    }
                }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text,
                        Modifier.then(Modifier.heightIn(max = 500.dp))
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { notices = false }) { Text("Done") } },
        )
}
