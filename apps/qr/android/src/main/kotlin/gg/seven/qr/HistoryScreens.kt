@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package gg.seven.qr

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.EmptyState
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.LocalSevenStore
import gg.seven.kit.SearchInput
import gg.seven.kit.Section
import gg.seven.kit.Space
import gg.seven.kit.action
import gg.seven.kit.item
import gg.seven.kit.numeric
import gg.seven.qr.core.HistoryItem
import gg.seven.qr.core.HistoryKind
import gg.seven.qr.core.Level
import gg.seven.qr.core.PayloadEncoding
import gg.seven.qr.core.Severity
import gg.seven.qr.core.historyItemScannedCode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlinx.coroutines.launch

/*
 * History (SPEC §10). Free to read: search, notes, export and delete work in every state; only
 * saving new scans needs the trial or the unlock. Android manners: long-press to select, delete
 * with Undo in a snackbar, export from the top bar's menu.
 */

private val rowDate = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun HistoryItem.dateText(): String = rowDate.format(date.atZone(ZoneId.systemDefault()))

@Composable
fun HistoryScreen(model: QrModel, nav: QrNav) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val gate = LocalSevenGate.current
    val history by model.historyState.collectAsStateWithLifecycle()
    val keep by model.settings.keepHistory.collectAsStateWithLifecycle()
    val entitlement by LocalSevenStore.current.entitlement.collectAsStateWithLifecycle()
    val locked = !entitlement.paidFeaturesAvailable
    val items = history.items
    val empty = HistoryEmptyState.resolve(items.size, keep, locked, history.readOnly)
    val search = rememberTextFieldState()
    val query = search.text.toString()
    var results by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var selection by rememberSaveable { mutableStateOf(setOf<String>()) }
    var exportMenu by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val selecting = selection.isNotEmpty()
    val searching = query.isNotBlank()

    LaunchedEffect(query, items) { results = if (searching) model.searchHistory(query) else null }
    LaunchedEffect(items.size) { if (items.isEmpty()) selection = emptySet() }
    BackHandler(selecting) { selection = emptySet() }

    fun delete(ids: Set<UUID>) {
        val removed = items.filter { it.id in ids }
        scope.launch {
            model.deleteHistory(ids)
            val outcome = snackbar.showSnackbar(if (removed.size == 1) "Deleted from history" else "Deleted ${removed.size} items", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (outcome == SnackbarResult.ActionPerformed) removed.forEach { model.history.add(it) }
        }
    }

    fun export(json: Boolean) {
        scope.launch {
            runCatching {
                    val bytes = if (json) model.exportHistoryJson() else model.exportHistoryCsv().toByteArray()
                    val file = FileShare.write(context, HistoryExportFiles.fileName(json), bytes)
                    FileShare.share(context, file, if (json) "application/json" else "text/csv", file.name)
                }
                .onFailure { exportFailed = true }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting)
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, "Cancel selection") } },
                    actions = {
                        IconButton(onClick = {
                            delete(selection.map(UUID::fromString).toSet())
                            selection = emptySet()
                        }) {
                            Icon(Icons.Rounded.Delete, "Delete selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            else
                TopAppBar(
                    title = { Text("History") },
                    navigationIcon = { IconButton(onClick = nav::pop) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    actions = {
                        if (empty == HistoryEmptyState.LIST)
                            Box {
                                IconButton(onClick = { exportMenu = true }, Modifier.testTag("history.export")) { Icon(Icons.Rounded.Share, "Export") }
                                DropdownMenu(exportMenu, onDismissRequest = { exportMenu = false }) {
                                    DropdownMenuItem(text = { Text("Export as JSON") }, onClick = {
                                        exportMenu = false
                                        export(json = true)
                                    })
                                    DropdownMenuItem(text = { Text("Export as CSV") }, onClick = {
                                        exportMenu = false
                                        export(json = false)
                                    })
                                }
                            }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (empty) {
                HistoryEmptyState.LIST -> {
                    val shown = if (searching) results.orEmpty() else items
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("history.list"),
                        contentPadding = PaddingValues(start = Space.m, end = Space.m, bottom = Space.xl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        item(key = "search") {
                            SearchInput("Search history", Modifier.padding(bottom = Space.s).testTag("history.search"), state = search)
                        }
                        if (HistoryEmptyState.showsLockedNote(items.size, keep, locked, history.readOnly))
                            item(key = "locked") {
                                QrCard(Modifier.padding(bottom = Space.s)) {
                                    Text(LOCKED_TEXT, style = MaterialTheme.typography.bodyLarge)
                                    OutlinedButton(onClick = { gate.offer() }) { Text("Unlock") }
                                }
                            }
                        if (!searching && !selecting) item(key = "summary") { Summary(items, Modifier.padding(bottom = Space.s)) }
                        if (searching && results?.isEmpty() == true)
                            item(key = "none") {
                                EmptyState("No results for “${query.trim()}”", "Check the spelling or try another word.", icon = Icons.Rounded.SearchOff)
                            }
                        val count = shown.size
                        itemsIndexed(shown, key = { _, it -> it.id.toString() }) { index, item ->
                            val id = item.id.toString()
                            HistoryRow(
                                item,
                                shapes = ListItemDefaults.segmentedShapes(index, count),
                                selected = id in selection,
                                selecting = selecting,
                                onClick = {
                                    if (selecting) selection = if (id in selection) selection - id else selection + id
                                    else nav.push(Route.HistoryDetail(item.id))
                                },
                                onLongClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    selection = selection + id
                                },
                            )
                        }
                    }
                }
                HistoryEmptyState.READ_ONLY ->
                    CenteredEmpty("History can’t be read", history.readOnlyReason ?: "This version can’t read the saved history, so it is left as it is.", Icons.Rounded.Warning)
                HistoryEmptyState.HISTORY_OFF ->
                    CenteredEmpty("Keep history is off", "Nothing you scan or make is being saved.", Icons.Rounded.HistoryToggleOff, "Open Settings" to { nav.push(Route.Settings) })
                HistoryEmptyState.LOCKED -> CenteredEmpty("Nothing saved", LOCKED_TEXT, Icons.Rounded.Lock, "Unlock" to { gate.offer() })
                HistoryEmptyState.NO_ITEMS -> CenteredEmpty("Nothing here yet", "Codes you scan or make will show up here.", Icons.Rounded.History)
            }
        }
    }
    if (exportFailed)
        AlertDialog(
            onDismissRequest = { exportFailed = false },
            title = { Text("The export didn’t work. Try again.") },
            confirmButton = { TextButton(onClick = { exportFailed = false }) { Text("OK") } },
        )
}

private const val LOCKED_TEXT = "Your history stays here. Saving new scans needs the unlock."

@Composable
private fun CenteredEmpty(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: Pair<String, () -> Unit>? = null) {
    Box(Modifier.fillMaxSize().testTag("history.list"), contentAlignment = Alignment.Center) {
        EmptyState(title, detail, icon = icon, action = action)
    }
}

/** Summary tiles over the list: what was scanned, what was made, how many warned. */
@Composable
private fun Summary(items: List<HistoryItem>, modifier: Modifier = Modifier) {
    val scanned = items.count { it.kind == HistoryKind.SCANNED }
    val made = items.size - scanned
    val warned = items.count { it.level != Level.NORMAL }
    val tiles: @Composable (Modifier) -> Unit = { m ->
        StatTile("Scanned", scanned.toString(), Icons.Rounded.QrCodeScanner, m)
        StatTile("Made", made.toString(), Icons.Rounded.QrCode2, m)
        StatTile("Warnings", warned.toString(), Icons.Rounded.Warning, m)
    }
    if (LocalDensity.current.fontScale >= 1.5f)
        Column(modifier.semantics { contentDescription = "Summary" }, verticalArrangement = Arrangement.spacedBy(Space.s)) { tiles(Modifier.fillMaxWidth()) }
    else Row(modifier.semantics(mergeDescendants = false) {}, horizontalArrangement = Arrangement.spacedBy(Space.s)) { tiles(Modifier.weight(1f)) }
}

/** One History row as library lists draw it: type tile, short payload, verdict at the time, note, when. */
@Composable
private fun HistoryRow(item: HistoryItem, shapes: ListItemShapes, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val typeName = QrText.typeName(item.typeId)
    val payload = HistoryRowSummary.shortPayload(item)
    val note = HistoryRowSummary.notePreview(item.note)
    val meta = "$typeName · ${HistoryLabels.kind(item.kind)} · ${item.dateText()}"
    val label = buildList {
        add(QrText.verdict(item.level))
        add(typeName)
        add(payload)
        note?.let { add("Note: $it") }
        add(HistoryLabels.kind(item.kind))
        add(item.dateText())
    }.joinToString(". ")
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        shapes = shapes,
        modifier = Modifier.testTag("history.row").clearAndSetSemantics {
            contentDescription = label
            this.selected = selected
        },
        leadingContent = {
            if (selecting) Checkbox(checked = selected, onCheckedChange = null)
            else TypeTile(VerdictIcons.type(item.typeId))
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                VerdictLabel(item.level)
                note?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(meta, style = MaterialTheme.typography.bodySmall.numeric())
            }
        },
    ) {
        Text(payload, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One saved code: the verdict it got at the time, the payload, when and how, a note, and Check
 * again, which runs today's checks. Notes and delete work in every state.
 */
@Composable
fun HistoryDetail(model: QrModel, nav: QrNav, id: UUID) {
    val history by model.historyState.collectAsStateWithLifecycle()
    val item = history.items.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(id) { mutableStateOf(item?.note.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var noteFailed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(item == null) { if (item == null && history.items.isNotEmpty()) nav.pop() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(item?.let { QrText.typeName(it.typeId) }.orEmpty()) },
                navigationIcon = { IconButton(onClick = nav::pop) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (item == null) return@Scaffold
        val signals = historySignals(item.signalIds)
        val typeName = QrText.typeName(item.typeId)
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.m).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                VerdictHeader(item.level, typeName, signals, caption = "$typeName · the verdict when it was saved")
                val reasons = SignalText.sentences(signals.filter { it.severity != Severity.INFO })
                if (reasons.isNotEmpty())
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) { reasons.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) } }
                ResultCard(title = if (item.payloadEncoding == PayloadEncoding.BASE64) "Contents (binary, shown as base64)" else "Contents") {
                    SelectionContainer { Text(item.payload, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)) }
                }
                Section {
                    item("Scanned or made", HistoryLabels.kind(item.kind))
                    item("Code type", HistoryLabels.symbology(item.symbology))
                    item("Date", item.dateText())
                }
                Section(footer = "Runs today’s checks on this code. The verdict above is the one it got when it was saved.") {
                    action("Check again", icon = Icons.Rounded.Refresh, enabled = !checking) {
                        checking = true
                        scope.launch {
                            val fresh = model.analysis(historyItemScannedCode(item))
                            checking = false
                            model.nav.showResult(fresh)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Note", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        draft,
                        { draft = QrText.prefix(it, HistoryStore.MAX_NOTE_LENGTH) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        label = { Text("Note") },
                        minLines = 4,
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                if (!model.setNote(draft, item.id)) noteFailed = true else snackbar.showSnackbar("Note saved")
                            }
                        },
                        enabled = HistoryNote.isChanged(draft, item.note),
                    ) {
                        Text("Save note")
                    }
                }
                Section { action("Delete from history", icon = Icons.Rounded.Delete, destructive = true) { confirmDelete = true } }
            }
        }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this item?") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            model.deleteHistory(setOf(id))
                            nav.pop()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
        )
    if (noteFailed)
        AlertDialog(
            onDismissRequest = { noteFailed = false },
            title = { Text("The note wasn’t saved. Try again.") },
            confirmButton = { TextButton(onClick = { noteFailed = false }) { Text("OK") } },
        )
}

