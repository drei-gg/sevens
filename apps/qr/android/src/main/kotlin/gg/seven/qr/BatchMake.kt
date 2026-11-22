package gg.seven.qr

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import gg.seven.kit.DropdownField
import gg.seven.kit.FullScreenForm
import gg.seven.qr.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Route-owned memory, like MakeDraft: rotation preserves inputs without persisting secrets. */
class BatchDraft {
    var input by mutableStateOf("")
    var items by mutableStateOf<List<String>?>(null)
    var issue by mutableStateOf<String?>(null)
    var prepared by mutableStateOf<File?>(null)
    /** When [prepared] was made: the saved copy's suggested name carries it. */
    var preparedAt by mutableStateOf<java.time.LocalDateTime?>(null)
    var grid by mutableStateOf(SheetGrid.GRID2X3)
    var paper by mutableStateOf(paperSizeForRegion(java.util.Locale.getDefault().country))
}

/** `qr-batch-20261122-1405.pdf`: what Save PDF suggests. Fixed calendar and digits. */
internal fun batchFileName(at: java.time.LocalDateTime): String =
    "qr-batch-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(at) + ".pdf"

internal fun batchPdf(items: List<String>, grid: SheetGrid, paper: PaperSize): ByteArray {
    val matrices =
        items.mapIndexed { index, text ->
            try {
                CodeGenerator.generate(text, MakeSymbology.QR, Correction.AUTO)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Code ${index + 1} could not be generated. Shorten that line and review again.",
                    e,
                )
            }
        }
    return PdfRenderer.batch(matrices, grid, paper)
}

@Composable
fun BatchMakeForm(draft: BatchDraft, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val items = draft.items
    var saved by remember { mutableStateOf(false) }
    val saveFile = rememberSaveFile { ok ->
        saved = ok
        draft.issue = if (ok) null else "The PDF couldn’t be saved there. Your file and inputs are still here."
    }
    fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent =
                Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("QR batch", uri)
            context.startActivity(Intent.createChooser(intent, "Share PDF"))
        } catch (_: Exception) {
            draft.issue = "No app could open the PDF. Your file and inputs are still here."
        }
    }
    FullScreenForm(
        title = if (items == null) "Batch codes" else "Review",
        onClose = close,
        confirmDiscard = draft.input.isNotEmpty() && draft.prepared == null,
        busy = busy,
        saveLabel =
            if (items == null) "Review"
            else if (draft.prepared != null) "Share PDF" else "Create PDF",
        onSave = {
            val prepared = draft.prepared
            if (prepared != null) share(prepared)
            else if (items == null) {
                val review = batchReview(draft.input)
                draft.issue = review.issue
                if (review.issue == null) draft.items = review.items
            } else
                scope.launch {
                    busy = true
                    draft.issue = null
                    try {
                        val grid = draft.grid
                        val paper = draft.paper
                        draft.preparedAt = java.time.LocalDateTime.now()
                        draft.prepared =
                            withContext(Dispatchers.IO) {
                                val bytes = batchPdf(items, grid, paper)
                                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                File(dir, "qr-batch-${UUID.randomUUID()}.pdf").also { file ->
                                    try {
                                        file.writeBytes(bytes)
                                    } catch (e: Exception) {
                                        file.delete()
                                        throw e
                                    }
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        draft.issue =
                            e.message ?: "Could not create the PDF. Your inputs are still here."
                    } finally {
                        busy = false
                    }
                }
        },
    ) {
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Creating PDF…")
        }
        draft.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (items == null) {
            Text(
                "One text or link per line, up to 100 codes. Blank lines are skipped; other characters and duplicate lines stay unchanged."
            )
            OutlinedTextField(
                draft.input,
                {
                    draft.input = it
                    draft.issue = null
                },
                label = { Text("Code contents") },
                minLines = 6,
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("batch-input"),
            )
        } else {
            Text("${items.size} codes", style = MaterialTheme.typography.titleLarge)
            Text(
                "Review each code below. PDF labels keep this order and exact text. Nothing is added to History."
            )
            if (!busy && draft.prepared == null) {
                DropdownField("Paper", draft.paper, PaperSize.entries.map { it to it.title }) {
                    draft.paper = it
                }
                DropdownField(
                    "Labels per page",
                    draft.grid,
                    SheetGrid.entries.map { it to it.title },
                ) {
                    draft.grid = it
                }
            } else Text("${draft.paper.title} · ${draft.grid.title}")
            draft.prepared?.let { file ->
                Text(if (saved) "PDF saved" else "PDF ready", style = MaterialTheme.typography.titleMedium)
                // Share PDF stays in the fixed action slot; Save puts a copy where the person picks.
                Button(
                    onClick = {
                        saved = false
                        saveFile.save(file, batchFileName(draft.preparedAt ?: java.time.LocalDateTime.now()))
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("batch-save"),
                ) {
                    Text("Save PDF")
                }
            }

            OutlinedButton(
                onClick = {
                    draft.items = null
                    draft.prepared = null
                    saved = false
                    draft.issue = null
                },
                enabled = !busy,
            ) {
                Text("Edit inputs")
            }
            items.forEachIndexed { index, text ->
                ListItem(
                    headlineContent = { Text(text) },
                    overlineContent = { Text("Code ${index + 1}") },
                )
            }
        }
    }
}
