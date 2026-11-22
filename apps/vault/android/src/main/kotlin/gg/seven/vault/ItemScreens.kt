@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.vault

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gg.seven.kit.*
import gg.seven.vault.core.*
import java.util.UUID

/**
 * Material's full-screen form with Vault's Lock beside the confirming action (iOS sheets carry Lock
 * in their toolbar).
 */
@Composable
fun VaultForm(
    title: String,
    model: VaultModel,
    onClose: () -> Unit,
    onSave: (() -> Unit)?,
    saveEnabled: Boolean = true,
    saveLabel: String = "Save",
    dirty: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var discarding by remember { mutableStateOf(false) }
    val close = {
        if (!model.busy) {
            if (dirty) discarding = true else onClose()
        }
    }
    Dialog(
        onDismissRequest = close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(
                            onClick = close,
                            enabled = !model.busy,
                            modifier = Modifier.testTag("editor-cancel"),
                        ) {
                            Icon(Icons.Outlined.Close, "Close")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = model::lock,
                            modifier = Modifier.testTag("editor-lock"),
                        ) {
                            Icon(Icons.Outlined.Lock, "Lock")
                        }
                        if (onSave != null)
                            TextButton(
                                onClick = onSave,
                                enabled = saveEnabled && !model.busy,
                                modifier = Modifier.testTag("editor-save"),
                            ) {
                                Text(saveLabel)
                            }
                    },
                )
            }
        ) { padding ->
            Column(
                Modifier.padding(padding)
                    .consumeWindowInsets(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.m, vertical = Space.s),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    content()
                    if (model.busy) Progress(model.progress)
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
    if (discarding)
        DiscardChangesDialog(onKeepEditing = { discarding = false }, onDiscard = onClose)
}

/** iOS `NoteSheet`. */
@Composable
fun NoteScreen(model: VaultModel, folder: UUID?, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    VaultForm(
        "New note",
        model,
        onClose,
        onSave = {
            if (!model.store.paidFeaturesAvailable) return@VaultForm
            val title = name
            val content = text
            model.update(then = onClose) { model.repository.addNote(title, content, folder) }
        },
        saveEnabled = name.isNotBlank(),
        dirty = name.isNotEmpty() || text.isNotEmpty(),
    ) {
        TextInput("Note name", name, Modifier.testTag("vault.noteName")) { name = it }
        OutlinedTextField(
            text,
            { text = it },
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = androidx.compose.ui.unit.Dp(240f))
                    .testTag("vault.noteText"),
            label = { Text("Note text") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

/**
 * iOS `ItemSheet` (File details): name and favorite, size and date, preview and export, the note
 * editor for plain text, manual order, and trash or restore. One Save covers details and note.
 */
@Composable
fun ItemDetailsScreen(model: VaultModel, item: VaultItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable(item.id) { mutableStateOf(item.name) }
    var favorite by rememberSaveable(item.id) { mutableStateOf(item.favorite) }
    val isNote = item.contentType == VaultRepository.NOTE_TYPE && item.bytes <= 5_000_000
    var noteText by remember(item.id) { mutableStateOf<String?>(null) }
    var noteOriginal by remember(item.id) { mutableStateOf<String?>(null) }
    if (isNote)
        LaunchedEffect(item.id) {
            try {
                val text = model.repository.readNote(item.id)
                noteText = text
                noteOriginal = text
            } catch (e: Exception) {
                model.error = e.vaultMessage
            }
        }
    val changedDetails = name != item.name || favorite != item.favorite
    val changedNote = noteText != null && noteText != noteOriginal
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun leave(action: () -> Unit) {
        if (changedDetails || changedNote) pendingAction = action else action()
    }
    pendingAction?.let { action ->
        DiscardChangesDialog(
            onKeepEditing = { pendingAction = null },
            onDiscard = {
                pendingAction = null
                action()
            },
        )
    }
    VaultForm(
        "File details",
        model,
        onClose,
        onSave = {
            val newName = name.takeIf { it != item.name }
            val newFavorite = favorite.takeIf { it != item.favorite }
            val text = noteText.takeIf { changedNote }
            model.update(then = onClose) {
                if (newName != null || newFavorite != null)
                    model.repository.updateItem(item.id, name = newName, favorite = newFavorite)
                if (text != null) model.repository.editNote(item.id, text)
            }
        },
        saveEnabled = (changedDetails || changedNote) && name.isNotBlank(),
        dirty = changedDetails || changedNote,
    ) {
        TextInput("Name", name) { name = it }
        Section {
            switch("Favorite", favorite) { favorite = it }
            item("Size", Formatter.formatFileSize(context, item.bytes))
            item(
                "Added",
                DateUtils.formatDateTime(
                    context,
                    item.created.toEpochMilli(),
                    DateUtils.FORMAT_SHOW_DATE or
                        DateUtils.FORMAT_SHOW_TIME or
                        DateUtils.FORMAT_ABBREV_MONTH or
                        DateUtils.FORMAT_SHOW_YEAR,
                ),
            )
        }
        Section(
            footer =
                "Previews use a temporary decrypted copy on this device. Exported copies are no longer protected by Vault."
        ) {
            action("Preview", Icons.Outlined.Visibility, enabled = !model.busy) {
                leave {
                    onClose()
                    model.preview(item)
                }
            }
            if (
                Types.isImage(item.contentType, item.name) ||
                    Types.isPdf(item.contentType, item.name)
            ) {
                action("Recognize text", Icons.Outlined.TextFields, enabled = !model.busy) {
                    leave {
                        onClose()
                        model.recognizeText(item)
                    }
                }
            }
            action("Export original", Icons.Outlined.Share, enabled = !model.busy) {
                leave {
                    onClose()
                    model.export(setOf(item.id))
                }
            }
        }
        if (isNote) {
            FormHeading("Edit note")
            val text = noteText
            if (text == null) LoadingIndicator()
            else
                OutlinedTextField(
                    text,
                    { noteText = it },
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = androidx.compose.ui.unit.Dp(160f))
                            .testTag("vault.editNote"),
                    label = { Text("Note text") },
                    keyboardOptions =
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
        }
        if (item.deleted == null)
            Section(title = "Manual order") {
                action("Move earlier", Icons.Outlined.ArrowUpward, enabled = !model.busy) {
                    shift(model, item, -1)
                }
                action("Move later", Icons.Outlined.ArrowDownward, enabled = !model.busy) {
                    shift(model, item, 1)
                }
            }
        Section {
            if (item.deleted == null)
                action(
                    "Move to Recently Deleted",
                    Icons.Outlined.Delete,
                    destructive = true,
                    enabled = !model.busy,
                ) {
                    leave {
                        model.update(
                            then = {
                                onClose()
                                model.showNotice("Moved to Recently Deleted")
                            }
                        ) {
                            model.repository.updateItem(item.id, trashed = true)
                        }
                    }
                }
            else
                action("Restore file", Icons.Outlined.RestoreFromTrash, enabled = !model.busy) {
                    leave {
                        model.update(then = onClose) {
                            model.repository.updateItem(item.id, trashed = false)
                        }
                    }
                }
        }
    }
}

/**
 * iOS `ItemSheet.shift`: swap with the neighbour in the folder's current order, then sort manually.
 */
private fun shift(model: VaultModel, item: VaultItem, delta: Int) {
    val library = model.library ?: return
    val ids =
        vaultOrderSorted(
                library.sort,
                library.items.filter { it.folder == item.folder && it.deleted == null },
            )
            .map { it.id }
            .toMutableList()
    val index = ids.indexOf(item.id)
    if (index < 0 || index + delta !in ids.indices) return
    ids[index] = ids[index + delta].also { ids[index + delta] = ids[index] }
    model.update { model.repository.reorder(ids) }
}
