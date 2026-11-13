@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.fast

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import gg.seven.kit.SevenColors
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun Double.number(digits: Int = 1) =
    String.format(Locale.getDefault(), "%.${digits}f", this)

internal fun stamp(value: Double) =
    value
        .instant()
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm"))

internal fun duration(value: Double): String {
    val mins = (value / 60_000).toLong().coerceAtLeast(0)
    return "${mins / 60}h ${mins % 60}m"
}

internal typealias OpenEditor = (String, String?) -> Unit

@Composable
fun FastApp(m: FastModel) {
    val a by m.archive.collectAsStateWithLifecycle()
    val failure by m.loadFailure.collectAsStateWithLifecycle()
    val now by m.now.collectAsStateWithLifecycle()
    val purchase by m.purchase.collectAsStateWithLifecycle()
    val busy by m.busy.collectAsStateWithLifecycle()
    val message by m.message.collectAsStateWithLifecycle()
    val incoming by m.importPreview.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Today") }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var detailFast by rememberSaveable { mutableStateOf<String?>(null) }
    var editorDate by rememberSaveable { mutableStateOf<Double?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var unlock by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("welcome", 0) }
    var welcome by rememberSaveable { mutableStateOf(!prefs.getBoolean("seen", false)) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            m.message.value = null
        }
    }
    val openAt: (String, String?, Double?) -> Unit = { type, id, date ->
        if (type == "detail") {
            detailFast = id
            page = "Fast details"
        } else if (id == null && !m.canCreate) unlock = true
        else {
            editor = type
            editId = id
            editorDate = date
        }
    }
    val open: OpenEditor = { type, id -> openAt(type, id, null) }
    fun back() {
        page = if (page in listOf("Water trends", "All water entries")) "Water" else null
    }
    BackHandler(page != null && editor == null) { back() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 700.dp && LocalDensity.current.fontScale < 1.5f
        val tabs =
            listOf(
                "Today" to Icons.Outlined.Timer,
                "History" to Icons.Outlined.CalendarMonth,
                "Journal" to Icons.Outlined.EditNote,
                "Trends" to Icons.Outlined.Insights,
            )
        Row(Modifier.fillMaxSize()) {
            if (wide)
                NavigationRail(
                    Modifier.fillMaxHeight()
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Start + WindowInsetsSides.Top
                            )
                        )
                ) {
                    Spacer(Modifier.height(28.dp))
                    tabs.forEach { (label, icon) ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("nav-$label"),
                            selected = tab == label,
                            onClick = {
                                tab = label
                                page = null
                            },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                        )
                    }
                }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(page ?: tab) },
                        navigationIcon = {
                            if (page != null)
                                IconButton(onClick = { back() }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                                }
                        },
                        actions = {
                            if (page == "Water")
                                IconButton(onClick = { open("waterSettings", "settings") }) {
                                    Icon(Icons.Outlined.Settings, "Goal, glass & reminders")
                                }
                            if (page == null)
                                IconButton(onClick = { page = "Settings" }) {
                                    Icon(Icons.Outlined.Settings, "Settings")
                                }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            ),
                    )
                },
                bottomBar = {
                    if (!wide)
                        NavigationBar {
                            tabs.forEach { (label, icon) ->
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav-$label"),
                                    selected = tab == label,
                                    onClick = {
                                        tab = label
                                        page = null
                                    },
                                    icon = { Icon(icon, null) },
                                    label = { Text(label) },
                                    alwaysShowLabel = true,
                                )
                            }
                        }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Box(
                    Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (failure != null) SettingsScreen(m, null, onUnlock = { unlock = true })
                    else if (a == null) CircularProgressIndicator(Modifier.padding(40.dp))
                    else {
                        val archive = a!!
                        when (page ?: tab) {
                            "Today" ->
                                TodayScreen(
                                    m,
                                    archive,
                                    now,
                                    open,
                                    onPage = { page = it },
                                    onUnlock = { unlock = true },
                                )
                            "History" -> HistoryScreen(m, archive, now, open)
                            "Journal" -> JournalScreen(archive, open)
                            "Trends" -> TrendsScreen(archive, now, open)
                            "Settings" -> SettingsScreen(m, archive, onUnlock = { unlock = true })
                            "Water" ->
                                WaterScreen(
                                    m,
                                    archive,
                                    now,
                                    open,
                                    onPage = { page = it },
                                    addAt = { date -> openAt("water", null, date) },
                                )
                            "Water trends" -> WaterTrendsScreen(archive, now)
                            "All water entries" -> WaterEntriesScreen(archive, open)
                            "Fast details" -> FastDetailScreen(archive, detailFast, now, open)
                            "Plans" -> PlansScreen(m, archive, open)
                        }
                    }
                    if (busy)
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
    if (editor != null && a != null)
        EditorDialog(
            m,
            a!!,
            editor!!,
            editId,
            editorDate,
            if (page == "Fast details") detailFast else null,
        ) {
            editor = null
            editId = null
        }
    if (welcome)
        AlertDialog(
            onDismissRequest = {
                welcome = false
                prefs.edit { putBoolean("seen", true) }
            },
            title = { Text("Before you begin") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Your phone’s Clock already times things. Notes keeps a journal. You may already have all you need."
                    )
                    Text(
                        "Fast brings editable timers, complete history, a private journal and water tracking together. No account, ads or subscription."
                    )
                    Text(
                        "Timers are records, not prescriptions. A shorter fast is a valid record. Nothing ends or starts automatically."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        welcome = false
                        prefs.edit { putBoolean("seen", true) }
                        if (!BuildConfig.PERSONAL_UNLOCK) m.startTrial()
                    }
                ) {
                    Text(
                        if (BuildConfig.PERSONAL_UNLOCK) "Get started" else "Start free 7-day trial"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        welcome = false
                        prefs.edit { putBoolean("seen", true) }
                    }
                ) {
                    Text("Look around")
                }
            },
        )
    if (unlock)
        AlertDialog(
            onDismissRequest = { unlock = false },
            title = { Text("Yours, paid once") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(m.access.label(purchase, now.toLong()))
                    Text(
                        "Editable fasting, complete history, private journal, water and trends. No subscription. Your existing records stay readable, editable and exportable after the trial. You can always finish a running fast."
                    )
                    if (m.access.trialStart == 0L)
                        OutlinedButton(
                            onClick = {
                                m.startTrial()
                                unlock = false
                            }
                        ) {
                            Text("Start free 7-day trial")
                        }
                    if (purchase.price != null && !purchase.owned)
                        Button(
                            onClick = { m.purchaseSource.purchase(context as Activity) },
                            enabled = !purchase.busy && !purchase.pending,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = SevenColors.unlock,
                                    contentColor = SevenColors.onUnlock,
                                ),
                        ) {
                            Text("Unlock · ${purchase.price}")
                        }
                    purchase.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (purchase.pending) Text("Awaiting payment confirmation")
                    OutlinedButton(onClick = { m.purchaseSource.refresh() }) {
                        Text("Restore purchases")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { unlock = false }) { Text("Done") } },
        )
    if (incoming != null && a != null) {
        val count =
            runCatching {
                    ArchiveCodec.count(ArchiveCodec.merge(incoming!!, a!!, now)) -
                        ArchiveCodec.count(a!!)
                }
                .getOrDefault(0)
        AlertDialog(
            onDismissRequest = { m.importPreview.value = null },
            title = { Text("Merge backup?") },
            text = {
                Text(
                    "Add $count records. Existing settings and reminder choices stay as they are. Conflicts reject the whole import; no records are overwritten."
                )
            },
            confirmButton = {
                TextButton(onClick = m::confirmImport, enabled = !busy) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = { m.importPreview.value = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 960.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

@Composable
internal fun Panel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            content()
        }
    }
}

@Composable
internal fun Empty(title: String, detail: String) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Section(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
