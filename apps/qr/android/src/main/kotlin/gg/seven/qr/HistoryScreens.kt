@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
)

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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
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
import gg.seven.kit.R as KitR
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
 * saving new scans needs the trial or the unlock. Android manners: long-press to select, swipe a
 * row to delete it, delete with Undo in a snackbar, save or share an export from the top bar's menu.
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
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val selecting = selectionMode || selection.isNotEmpty()
    val searching = query.isNotBlank()

    LaunchedEffect(query, items) { results = if (searching) model.searchHistory(query) else null }
    LaunchedEffect(items.size) { if (items.isEmpty()) selection = emptySet() }
    BackHandler(selecting) {
        selection = emptySet()
        selectionMode = false
    }

    val res = LocalResources.current
    fun delete(ids: Set<UUID>) {
        val removed = items.filter { it.id in ids }
        scope.launch {
            model.deleteHistory(ids)
            val outcome =
                snackbar.showSnackbar(
                    res.getQuantityString(R.plurals.qr_history_deleted, removed.size, removed.size),
                    actionLabel = res.getString(R.string.qr_undo),
                    duration = SnackbarDuration.Long,
                )
            if (outcome == SnackbarResult.ActionPerformed) removed.forEach { model.history.add(it) }
        }
    }

    val saveFile = rememberSaveFile { ok ->
        if (ok) scope.launch { snackbar.showSnackbar(res.getString(R.string.qr_saved)) } else exportFailed = true
    }

    /** JSON or CSV in the export folder, then saved where the person picks or shared. */
    fun export(json: Boolean, save: Boolean) {
        scope.launch {
            runCatching {
                    val bytes =
                        if (json) model.exportHistoryJson()
                        else model.exportHistoryCsv().toByteArray()
                    val file = FileShare.write(context, HistoryExportFiles.fileName(json), bytes)
                    if (save) saveFile.save(file)
                    else FileShare.share(context, file, FileShare.mimeType(file.name), file.name)
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
                    title = { Text(pluralStringResource(R.plurals.qr_history_selected, selection.size, selection.size)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                selection = emptySet()
                                selectionMode = false
                            }
                        ) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.qr_history_cancel_selection))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                selection =
                                    (if (searching) results.orEmpty() else items)
                                        .map { it.id.toString() }
                                        .toSet()
                            }
                        ) {
                            Icon(Icons.Rounded.SelectAll, stringResource(R.string.qr_history_select_all))
                        }
                        IconButton(
                            enabled = selection.isNotEmpty(),
                            onClick = {
                                delete(selection.map(UUID::fromString).toSet())
                                selection = emptySet()
                                selectionMode = false
                            },
                        ) {
                            Icon(Icons.Rounded.Delete, stringResource(R.string.qr_history_delete_selected))
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                )
            else
                TopAppBar(
                    title = { Text(stringResource(R.string.qr_settings_history)) },
                    navigationIcon = {
                        IconButton(onClick = nav::pop) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(KitR.string.seven_back))
                        }
                    },
                    actions = {
                        if (empty == HistoryEmptyState.LIST)
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(Icons.Rounded.Checklist, stringResource(R.string.qr_history_select))
                            }
                        if (empty == HistoryEmptyState.LIST)
                            Box {
                                IconButton(
                                    onClick = { exportMenu = true },
                                    Modifier.testTag("history.export"),
                                ) {
                                    Icon(Icons.Rounded.Share, stringResource(R.string.qr_history_export))
                                }
                                DropdownMenu(
                                    exportMenu,
                                    onDismissRequest = { exportMenu = false },
                                ) {
                                    listOf(true to "JSON", false to "CSV").forEach { (json, name) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.qr_history_save_as, name)) },
                                            leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                            onClick = {
                                                exportMenu = false
                                                export(json, save = true)
                                            },
                                            modifier = Modifier.testTag("history.export.save.$name"),
                                        )
                                    }
                                    HorizontalDivider()
                                    listOf(true to "JSON", false to "CSV").forEach { (json, name) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.qr_history_share_as, name)) },
                                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                            onClick = {
                                                exportMenu = false
                                                export(json, save = false)
                                            },
                                            modifier = Modifier.testTag("history.export.share.$name"),
                                        )
                                    }
                                }
                            }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (empty) {
                HistoryEmptyState.LIST -> {
                    val shown = if (searching) results.orEmpty() else items
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("history.list"),
                        contentPadding =
                            PaddingValues(
                                start = Space.m,
                                end = Space.m,
                                bottom =
                                    Space.xl +
                                        WindowInsets.navigationBars
                                            .asPaddingValues()
                                            .calculateBottomPadding(),
                            ),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        item(key = "search") {
                            SearchInput(
                                stringResource(R.string.qr_history_search),
                                Modifier.padding(bottom = Space.s).testTag("history.search"),
                                state = search,
                            )
                        }
                        if (
                            HistoryEmptyState.showsLockedNote(
                                items.size,
                                keep,
                                locked,
                                history.readOnly,
                            )
                        )
                            item(key = "locked") {
                                QrCard(Modifier.padding(bottom = Space.s)) {
                                    Text(stringResource(R.string.qr_history_locked), style = MaterialTheme.typography.bodyLarge)
                                    OutlinedButton(onClick = { gate.offer() }) { Text(stringResource(KitR.string.seven_unlock)) }
                                }
                            }
                        if (!searching && !selecting)
                            item(key = "summary") {
                                Summary(items, Modifier.padding(bottom = Space.s))
                            }
                        if (searching && results?.isEmpty() == true)
                            item(key = "none") {
                                EmptyState(
                                    stringResource(R.string.qr_history_no_results, query.trim()),
                                    stringResource(R.string.qr_history_no_results_hint),
                                    icon = Icons.Rounded.SearchOff,
                                )
                            }
                        val count = shown.size
                        itemsIndexed(shown, key = { _, it -> it.id.toString() }) { index, item ->
                            val id = item.id.toString()
                            val shapes = ListItemDefaults.segmentedShapes(index, count)
                            // Swipe toward the start deletes one row, with the same Undo as
                            // multi-select; selecting turns the gesture off.
                            SwipeToDeleteRow(
                                stringResource(R.string.qr_history_delete),
                                shapes.shape,
                                enabled = !selecting,
                                onDelete = { delete(setOf(item.id)) },
                                modifier = Modifier.animateItem(),
                            ) {
                                HistoryRow(
                                    item,
                                    shapes = shapes,
                                    selected = id in selection,
                                    selecting = selecting,
                                    onClick = {
                                        if (selecting)
                                            selection =
                                                if (id in selection) selection - id
                                                else selection + id
                                        else nav.push(Route.HistoryDetail(item.id))
                                    },
                                    onLongClick = {
                                        view.performHapticFeedback(
                                            HapticFeedbackConstants.LONG_PRESS
                                        )
                                        selection = selection + id
                                    },
                                )
                            }
                        }
                    }
                }
                HistoryEmptyState.READ_ONLY ->
                    CenteredEmpty(
                        stringResource(R.string.qr_history_unreadable),
                        stringResource(R.string.qr_history_read_only),
                        Icons.Rounded.Warning,
                    )
                HistoryEmptyState.HISTORY_OFF ->
                    CenteredEmpty(
                        stringResource(R.string.qr_history_off),
                        stringResource(R.string.qr_history_off_text),
                        Icons.Rounded.HistoryToggleOff,
                        stringResource(R.string.qr_scan_open_settings) to { nav.push(Route.Settings) },
                    )
                HistoryEmptyState.LOCKED ->
                    CenteredEmpty(
                        stringResource(R.string.qr_history_nothing_saved),
                        stringResource(R.string.qr_history_locked),
                        Icons.Rounded.Lock,
                        stringResource(KitR.string.seven_unlock) to { gate.offer() },
                    )
                HistoryEmptyState.NO_ITEMS ->
                    CenteredEmpty(
                        stringResource(R.string.qr_history_empty),
                        stringResource(R.string.qr_history_empty_text),
                        Icons.Rounded.History,
                    )
            }
        }
    }
    if (exportFailed)
        AlertDialog(
            onDismissRequest = { exportFailed = false },
            title = { Text(stringResource(R.string.qr_history_export_failed)) },
            confirmButton = { TextButton(onClick = { exportFailed = false }) { Text(stringResource(KitR.string.seven_ok)) } },
        )
}

@Composable
private fun CenteredEmpty(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: Pair<String, () -> Unit>? = null,
) {
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
        StatTile(stringResource(R.string.qr_history_scanned), scanned.toString(), Icons.Rounded.QrCodeScanner, m)
        StatTile(stringResource(R.string.qr_history_made), made.toString(), Icons.Rounded.QrCode2, m)
        StatTile(stringResource(R.string.qr_history_warnings), warned.toString(), Icons.Rounded.Warning, m)
    }
    val summary = stringResource(R.string.qr_history_summary)
    if (LocalDensity.current.fontScale >= 1.5f)
        Column(
            modifier.semantics { contentDescription = summary },
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            tiles(Modifier.fillMaxWidth())
        }
    else
        Row(
            modifier.semantics(mergeDescendants = false) {},
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            tiles(Modifier.weight(1f))
        }
}

/**
 * One History row as library lists draw it: type tile, short payload, verdict at the time, note,
 * when.
 */
@Composable
private fun HistoryRow(
    item: HistoryItem,
    shapes: ListItemShapes,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val res = LocalResources.current
    val typeName = QrText.typeName(res, item.typeId)
    val payload = HistoryRowSummary.shortPayload(res, item)
    val note = HistoryRowSummary.notePreview(item.note)
    val kind = HistoryLabels.kind(res, item.kind)
    val meta = stringResource(R.string.qr_history_row_meta, typeName, kind, item.dateText())
    val label =
        buildList {
                add(QrText.verdict(res, item.level))
                add(typeName)
                add(payload)
                note?.let { add(res.getString(R.string.qr_history_row_note, it)) }
                add(kind)
                add(item.dateText())
            }
            .joinToString(". ")
    SegmentedListItem(
        onClick = onClick,
        onLongClick = onLongClick,
        shapes = shapes,
        modifier =
            Modifier.testTag("history.row").semantics(mergeDescendants = true) {
                contentDescription = label
                this.selected = selected
                if (selecting) role = androidx.compose.ui.semantics.Role.Checkbox
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
    var saving by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    val close = {
        if (!saving) {
            if (item != null && HistoryNote.isChanged(draft, item.note)) discarding = true
            else nav.pop()
        }
    }
    BackHandler { close() }
    if (discarding)
        gg.seven.kit.DiscardChangesDialog(
            { discarding = false },
            {
                discarding = false
                nav.pop()
            },
        )
    LaunchedEffect(item == null) { if (item == null && history.items.isNotEmpty()) nav.pop() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(item?.let { QrText.typeName(LocalResources.current, it.typeId) }.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = close, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(KitR.string.seven_back))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
    ) { padding ->
        if (item == null) return@Scaffold
        val signals = historySignals(item.signalIds)
        val res = LocalResources.current
        val typeName = QrText.typeName(res, item.typeId)
        Column(
            Modifier.padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m)
                .padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            ReadableColumn {
                VerdictHeader(
                    item.level,
                    typeName,
                    signals,
                    caption = stringResource(R.string.qr_history_verdict_when_saved, typeName),
                )
                val reasons = SignalText.sentences(res, signals.filter { it.severity != Severity.INFO })
                if (reasons.isNotEmpty())
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        reasons.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                ResultCard(
                    title =
                        stringResource(
                            if (item.payloadEncoding == PayloadEncoding.BASE64) R.string.qr_history_contents_base64
                            else R.string.qr_history_contents
                        )
                ) {
                    SelectionContainer {
                        Text(
                            item.payload,
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                        )
                    }
                }
                val scannedOrMade = stringResource(R.string.qr_history_scanned_or_made)
                val kind = HistoryLabels.kind(res, item.kind)
                val codeType = stringResource(R.string.qr_history_code_type)
                val symbology = HistoryLabels.symbology(res, item.symbology)
                val date = stringResource(R.string.qr_history_date)
                val checkAgain = stringResource(R.string.qr_history_check_again)
                val deleteFromHistory = stringResource(R.string.qr_history_delete_item)
                Section {
                    item(scannedOrMade, kind)
                    item(codeType, symbology)
                    item(date, item.dateText())
                }
                Section(footer = stringResource(R.string.qr_history_check_again_footer)) {
                    action(checkAgain, icon = Icons.Rounded.Refresh, enabled = !checking) {
                        checking = true
                        scope.launch {
                            val fresh = model.analysis(historyItemScannedCode(item))
                            checking = false
                            model.nav.showResult(fresh)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        stringResource(R.string.qr_history_note),
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        draft,
                        { draft = QrText.prefix(it, HistoryStore.MAX_NOTE_LENGTH) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        label = { Text(stringResource(R.string.qr_history_note)) },
                        minLines = 4,
                    )
                    FilledTonalButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val saved = model.setNote(draft, item.id)
                                saving = false
                                if (!saved) noteFailed = true
                                else snackbar.showSnackbar(res.getString(R.string.qr_history_note_saved))
                            }
                        },
                        enabled = !saving && HistoryNote.isChanged(draft, item.note),
                    ) {
                        Text(stringResource(R.string.qr_history_save_note))
                    }
                }
                Section {
                    action(deleteFromHistory, icon = Icons.Rounded.Delete, destructive = true) {
                        confirmDelete = true
                    }
                }
            }
        }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.qr_history_delete_title)) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(KitR.string.seven_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            model.deleteHistory(setOf(id))
                            nav.pop()
                        }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.qr_history_delete))
                }
            },
        )
    if (noteFailed)
        AlertDialog(
            onDismissRequest = { noteFailed = false },
            title = { Text(stringResource(R.string.qr_history_note_failed)) },
            confirmButton = { TextButton(onClick = { noteFailed = false }) { Text(stringResource(KitR.string.seven_ok)) } },
        )
}
