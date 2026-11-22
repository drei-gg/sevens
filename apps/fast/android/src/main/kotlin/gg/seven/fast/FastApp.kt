@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package gg.seven.fast

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.fast.core.*
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.Space
import gg.seven.kit.EmptyState

internal typealias OpenEditor = (String, java.util.UUID?) -> Unit

/** A tab or page key (state and test tags stay in English) as its title in the locale. */
@Composable
@ReadOnlyComposable
private fun title(key: String): String =
    stringResource(
        when (key) {
            "Today" -> R.string.tab_today
            "History" -> R.string.tab_history
            "Journal" -> R.string.tab_journal
            "Trends" -> R.string.tab_trends
            "Settings" -> R.string.page_settings
            "Water" -> R.string.page_water
            "Water trends" -> R.string.page_water_trends
            "All water entries" -> R.string.page_water_entries
            "Fast details" -> R.string.page_fast_details
            else -> R.string.page_plans
        }
    )

@Composable
fun FastApp(m: FastModel) {
    val a by m.archive.collectAsStateWithLifecycle()
    val failure by m.loadFailure.collectAsStateWithLifecycle()
    val now by m.now.collectAsStateWithLifecycle()
    val busy by m.working.collectAsStateWithLifecycle()
    val message by m.message.collectAsStateWithLifecycle()
    val incoming by m.importPreview.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Today") }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var detailFast by rememberSaveable { mutableStateOf<java.util.UUID?>(null) }
    var editorDate by rememberSaveable { mutableStateOf<Double?>(null) }
    var editId by rememberSaveable { mutableStateOf<java.util.UUID?>(null) }
    // SevenRoot (above this) shows Before you buy, the trial-ended sheet and the unlock sheet.
    val gate = LocalSevenGate.current
    val offerUnlock = { gate.offer() }
    val context = LocalContext.current
    use24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val snackbar = remember { SnackbarHostState() }
    val undoable by m.undoable.collectAsStateWithLifecycle()
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(undoable) {
        undoable?.let {
            // Cleared only after it's shown: clearing first would restart this effect and
            // cancel the snackbar.
            val result = snackbar.showSnackbar(it.text, actionLabel = undoLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) it.undo()
            if (m.undoable.value === it) m.undoable.value = null
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            m.message.value = null
        }
    }
    val openAt: (String, java.util.UUID?, Double?) -> Unit = { type, id, date ->
        if (type == "detail") {
            detailFast = id
            page = "Fast details"
        } else if (id == null && !m.canCreate) offerUnlock()
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
                    tabs.forEach { (key, icon) ->
                        NavigationRailItem(
                            modifier = Modifier.testTag("nav-$key"),
                            selected = tab == key,
                            onClick = {
                                tab = key
                                page = null
                            },
                            icon = { Icon(icon, null) },
                            label = { Text(title(key)) },
                        )
                    }
                }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(
                        title = { Text(title(page ?: tab), maxLines = 1) },
                        navigationIcon = {
                            if (page != null)
                                IconButton(onClick = { back() }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                                }
                        },
                        actions = {
                            if (page == "Water")
                                IconButton(onClick = { open("waterSettings", null) }) {
                                    Icon(Icons.Outlined.Settings, stringResource(R.string.water_settings_action))
                                }
                            if (page == null)
                                IconButton(onClick = { page = "Settings" }) {
                                    Icon(Icons.Outlined.Settings, stringResource(R.string.page_settings))
                                }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                scrolledContainerColor = MaterialTheme.colorScheme.background,
                            ),
                    )
                },
                bottomBar = {
                    if (!wide)
                        ShortNavigationBar {
                            tabs.forEach { (key, icon) ->
                                ShortNavigationBarItem(
                                    modifier = Modifier.testTag("nav-$key"),
                                    selected = tab == key,
                                    onClick = {
                                        tab = key
                                        page = null
                                    },
                                    icon = { Icon(icon, null) },
                                    label = { Text(title(key)) },
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
                    if (failure != null) SettingsScreen(m, null)
                    else if (a == null) LoadingIndicator(Modifier.padding(40.dp))
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
                                    onUnlock = offerUnlock,
                                )
                            "History" ->
                                HistoryScreen(m, archive, now, open) { type, date ->
                                    openAt(type, null, date)
                                }
                            "Journal" -> JournalScreen(archive, open)
                            "Trends" -> TrendsScreen(archive, now, open)
                            "Settings" -> SettingsScreen(m, archive)
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
    if (incoming != null && a != null) {
        val count =
            runCatching {
                    ArchiveCodec.count(ArchiveCodec.merge(incoming!!, a!!, now)) -
                        ArchiveCodec.count(a!!)
                }
                .getOrDefault(0)
        AlertDialog(
            onDismissRequest = { m.importPreview.value = null },
            title = { Text(stringResource(R.string.merge_title)) },
            text = { Text(pluralStringResource(R.plurals.merge_message, count, count)) },
            confirmButton = {
                TextButton(onClick = m::confirmImport, enabled = !busy) {
                    Text(stringResource(R.string.merge))
                }
            },
            dismissButton = {
                TextButton(onClick = { m.importPreview.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 720.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.m)
            .padding(top = Space.s, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m),
        content = content,
    )
}

/** A Material card for a block of the app's own content: a glass, a chart, stats. */
@Composable
internal fun Panel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.ms)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

@Composable
internal fun Empty(title: String, detail: String) = EmptyState(title, detail)

/** A Material list subheader: title small, primary. */
@Composable
internal fun Heading(title: String) {
    Text(
        title,
        Modifier.padding(start = Space.m, end = Space.m, top = Space.s),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
