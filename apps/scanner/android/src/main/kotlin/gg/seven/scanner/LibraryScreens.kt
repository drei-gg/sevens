@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package gg.seven.scanner

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.kit.DropdownField
import gg.seven.kit.EmptyState
import gg.seven.kit.Section
import gg.seven.kit.SevenAbout
import gg.seven.kit.Space
import gg.seven.kit.item
import gg.seven.kit.numeric
import gg.seven.scanner.core.ScanDocument
import gg.seven.scanner.core.coreVersion
import java.io.FileInputStream
import kotlinx.coroutines.launch

/**
 * Documents moved here wait until they're restored or deleted for good: a thumbnail per document,
 * Restore on the row, delete permanently from the row's menu (long-press) after a confirmation.
 */
@Composable
fun RecentlyDeletedScreen(model: ScannerModel) {
    val nav = LocalNav.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    var permanent by remember { mutableStateOf<ScanDocument?>(null) }
    var working by remember { mutableStateOf(false) }
    fun restore(document: ScanDocument) {
        working = true
        scope.launch {
            val restored = model.restore(document)
            working = false
            if (restored) nav.tell(resources.getString(R.string.restored_named, document.title))
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(nav.snackbar) },
        topBar = { ScannerTopBar(stringResource(R.string.recently_deleted), onBack = nav::pop) },
    ) { padding ->
        if (deleted.isEmpty())
            EmptyState(
                stringResource(R.string.trash_empty_title),
                stringResource(R.string.trash_empty_text),
                Modifier.padding(padding).padding(top = Space.xxl),
                icon = Icons.Outlined.Delete,
            )
        else
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().readable(),
                contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                items(deleted, key = { it.id.toString() }) { document ->
                    var menu by remember { mutableStateOf(false) }
                    val index = deleted.indexOf(document)
                    Box {
                        // Tap or long-press opens the row's actions (Restore, Delete permanently).
                        SegmentedListItem(
                            onClick = { menu = true },
                            onLongClick = { menu = true },
                            onLongClickLabel = stringResource(R.string.options),
                            enabled = !working,
                            shapes = ListItemDefaults.segmentedShapes(index, deleted.size),
                            modifier = Modifier.testTag("trash.row"),
                            leadingContent = {
                                PageThumbnail(
                                    document.pages.firstOrNull()?.let {
                                        model.repository.pageFile(
                                            document.id,
                                            it.id,
                                            deleted = true,
                                        )
                                    },
                                    document.pages.firstOrNull(),
                                    Modifier.width(44.dp),
                                    maxPixels = 160,
                                )
                            },
                            supportingContent = {
                                Text(
                                    pageCount(document.pages.size),
                                    style = LocalTextStyle.current.numeric(),
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { restore(document) },
                                        enabled = !working,
                                        modifier = Modifier.testTag("trash.restore"),
                                    ) {
                                        Text(stringResource(R.string.restore))
                                    }
                                    IconButton(onClick = { menu = true }, enabled = !working) {
                                        Icon(
                                            Icons.Outlined.MoreVert,
                                            stringResource(
                                                R.string.document_actions,
                                                document.title,
                                            ),
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.restore)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, null) },
                                onClick = {
                                    menu = false
                                    restore(document)
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.delete_permanently),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteForever,
                                        null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menu = false
                                    permanent = document
                                },
                                modifier = Modifier.testTag("trash.delete"),
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.trash_footer),
                        Modifier.padding(top = Space.s, start = Space.m, end = Space.m),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
    permanent?.let { document ->
        AlertDialog(
            onDismissRequest = { permanent = null },
            title = { Text(stringResource(R.string.delete_permanently_title, document.title)) },
            text = { Text(stringResource(R.string.cannot_undo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        permanent = null
                        working = true
                        scope.launch {
                            model.permanentlyDelete(document)
                            working = false
                        }
                    },
                    modifier = Modifier.testTag("trash.confirm"),
                ) {
                    Text(
                        stringResource(R.string.delete_permanently),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { permanent = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * Backup: what one file will hold (tiles), one large Export button, Restore beneath it, and the
 * limits in plain words.
 */
@Composable
fun BackupScreen(model: ScannerModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val documents by model.documents.collectAsStateWithLifecycle()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<Uri?>(null) }
    val pages = (documents + deleted).sumOf { it.pages.size }
    fun report(text: String, failure: Boolean) {
        message = text
        failed = failure
        if (failure) view.reject() else view.confirm()
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { target ->
            if (target == null) return@rememberLauncherForActivityResult
            working = true
            message = null
            scope.launch {
                try {
                    model.repository.io {
                        val out =
                            context.contentResolver.openOutputStream(target, "wt")
                                ?: throw IllegalStateException(
                                    resources.getString(R.string.error_unwritable)
                                )
                        out.use { exportBackup(it) }
                    }
                    report(resources.getString(R.string.backup_saved), false)
                } catch (e: Exception) {
                    runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, target)
                    }
                    report(
                        resources.getString(R.string.backup_failed, describe(resources, e)),
                        true,
                    )
                }
                working = false
            }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { candidate = it }
    fun restore(source: Uri) {
        working = true
        message = null
        scope.launch {
            try {
                val count =
                    model.repository.io {
                        val descriptor =
                            context.contentResolver.openFileDescriptor(source, "r")
                                ?: throw IllegalStateException(
                                    resources.getString(R.string.error_unopenable)
                                )
                        descriptor.use { fd ->
                            val length = fd.statSize
                            if (length < 0)
                                throw gg.seven.scanner.core.ScanException.InvalidDocument()
                            FileInputStream(fd.fileDescriptor).buffered().use {
                                restoreBackup(it, length)
                            }
                        }
                    }
                model.reload()
                report(
                    resources.getString(R.string.restored_copies, documentCount(resources, count)),
                    false,
                )
            } catch (e: Exception) {
                report(
                    resources.getString(R.string.restore_failed, describe(resources, e)),
                    true,
                )
            }
            working = false
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScannerTopBar(stringResource(R.string.backup_restore), onBack = nav::pop) },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readable()
                .padding(horizontal = Space.m, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            val tiles =
                listOf(
                    stringResource(R.string.library_title) to documents.size,
                    stringResource(R.string.stat_pages) to pages,
                    stringResource(R.string.favorites) to documents.count { it.favorite == true },
                    stringResource(R.string.recently_deleted) to deleted.size,
                )
            val perRow = if (largeText()) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                tiles.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
                        row.forEach { (title, value) ->
                            StatTile(title, "$value", Modifier.weight(1f))
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.backup_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                Button(
                    onClick = { export.launch(BACKUP_NAME) },
                    enabled = !working,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("backup.export"),
                ) {
                    if (working) LoadingIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Outlined.Backup, null)
                    Spacer(Modifier.width(Space.s))
                    Text(stringResource(if (working) R.string.working else R.string.export_backup))
                }
                FilledTonalButton(
                    onClick = { open.launch(arrayOf("*/*")) },
                    enabled = !working,
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("backup.restore"),
                ) {
                    Icon(Icons.Outlined.Restore, null)
                    Spacer(Modifier.width(Space.s))
                    Text(stringResource(R.string.restore_from_backup))
                }
            }
            message?.let {
                StatusLine(
                    it,
                    Modifier.testTag("backup.message"),
                    icon = if (failed) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    tint =
                        if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf(
                        stringResource(R.string.backup_note_restore),
                        stringResource(R.string.backup_note_cloud),
                    )
                    .forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
    candidate?.let { source ->
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        candidate = null
                        restore(source)
                    },
                    modifier = Modifier.testTag("backup.confirm"),
                ) {
                    Text(stringResource(R.string.restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { candidate = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

const val BACKUP_NAME = "Scanner backup.scannerbackup"

/**
 * Settings: appearance, privacy in plain words, then SevenKit's trial/unlock and about sections.
 */
@Composable
fun SettingsScreen(model: ScannerModel) {
    val nav = LocalNav.current
    val appearance by model.appearance.collectAsStateWithLifecycle()
    val backupLabel = stringResource(R.string.backup_restore)
    val trashLabel = stringResource(R.string.recently_deleted)
    val themeLabel = stringResource(R.string.appearance_theme)
    val themes =
        listOf(
            "system" to stringResource(R.string.theme_system),
            "light" to stringResource(R.string.theme_light),
            "dark" to stringResource(R.string.theme_dark),
        )
    val onPhone = stringResource(R.string.privacy_on_phone)
    val onPhoneText = stringResource(R.string.privacy_on_phone_text)
    val cameraScanning = stringResource(R.string.camera_scanning)
    val engineNote = stringResource(R.string.scanner_engine_note)
    val searchableText = stringResource(R.string.searchable_text)
    val searchableNote = stringResource(R.string.searchable_text_note)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScannerTopBar(stringResource(R.string.settings), onBack = nav::pop) },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .readable()
                .padding(horizontal = Space.m, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Section(title = stringResource(R.string.your_documents)) {
                item(
                    backupLabel,
                    icon = Icons.Outlined.SettingsBackupRestore,
                    onClick = { nav.push(Route.Backup) },
                )
                item(
                    trashLabel,
                    icon = Icons.Outlined.Delete,
                    onClick = { nav.push(Route.Deleted) },
                )
            }
            Section(title = stringResource(R.string.appearance)) {
                row { shapes ->
                    SegmentedListItem(shapes) {
                        DropdownField(
                            themeLabel,
                            appearance,
                            themes,
                            Modifier.fillMaxWidth().testTag("settings.theme"),
                        ) {
                            model.setAppearance(it)
                        }
                    }
                }
            }
            Section(title = stringResource(R.string.privacy)) {
                item(onPhone, supporting = onPhoneText, icon = Icons.Outlined.Lock)
                item(cameraScanning, supporting = engineNote, icon = Icons.Outlined.CameraAlt)
                item(
                    searchableText,
                    supporting = searchableNote,
                    icon = Icons.Outlined.DocumentScanner,
                )
            }
            SevenAbout(coreVersion = remember { coreVersion() })
        }
    }
}
