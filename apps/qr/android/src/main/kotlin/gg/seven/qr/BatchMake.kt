package gg.seven.qr

import android.content.ClipData
import android.content.Intent
import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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

/** The batch's words (iOS BatchMakeScreen): the core says what is wrong, the app says it. */
object BatchText {
    fun issue(res: Resources, kind: BatchIssue): String =
        when (kind) {
            is BatchIssue.TooLarge -> res.getString(R.string.qr_batch_too_large, kind.maxKilobytes)
            is BatchIssue.LineTooLong -> res.getString(R.string.qr_batch_line_too_long, kind.line)
            is BatchIssue.TooManyLines -> kind.max.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let { res.getQuantityString(R.plurals.qr_batch_too_many_lines, it, it) }
            BatchIssue.Empty -> res.getString(R.string.qr_batch_empty)
        }

    /** The worded issue, or the core's English sentence from an older core that has no kind. */
    fun issue(res: Resources, review: BatchReview): String? = review.issueKind?.let { issue(res, it) } ?: review.issue
}

/** Code [number] (counting from 1) of a batch couldn't be generated. */
internal class BatchCodeException(val number: Int, cause: Throwable) : IllegalArgumentException("code $number", cause)

internal fun batchPdf(items: List<String>, grid: SheetGrid, paper: PaperSize): ByteArray {
    val matrices =
        items.mapIndexed { index, text ->
            try {
                CodeGenerator.generate(text, MakeSymbology.QR, Correction.AUTO)
            } catch (e: Exception) {
                throw BatchCodeException(index + 1, e)
            }
        }
    return PdfRenderer.batch(matrices, grid, paper)
}

@Composable
fun BatchMakeForm(draft: BatchDraft, close: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val items = draft.items
    var saved by remember { mutableStateOf(false) }
    val saveFile = rememberSaveFile { ok ->
        saved = ok
        draft.issue = if (ok) null else res.getString(R.string.qr_batch_save_failed)
    }
    fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent =
                Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(res.getString(R.string.qr_batch_clip_label), uri)
            context.startActivity(Intent.createChooser(intent, res.getString(R.string.qr_batch_share_pdf)))
        } catch (_: Exception) {
            draft.issue = res.getString(R.string.qr_batch_no_app)
        }
    }
    FullScreenForm(
        title = stringResource(if (items == null) R.string.qr_batch_title else R.string.qr_batch_review),
        onClose = close,
        confirmDiscard = draft.input.isNotEmpty() && draft.prepared == null,
        busy = busy,
        saveLabel =
            stringResource(
                if (items == null) R.string.qr_batch_review
                else if (draft.prepared != null) R.string.qr_batch_share_pdf else R.string.qr_batch_create_pdf
            ),
        onSave = {
            val prepared = draft.prepared
            if (prepared != null) share(prepared)
            else if (items == null) {
                val review = batchReview(draft.input)
                draft.issue = BatchText.issue(res, review)
                if (review.issue == null && review.issueKind == null) draft.items = review.items
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
                            if (e is BatchCodeException) res.getString(R.string.qr_batch_code_failed, e.number)
                            else res.getString(R.string.qr_batch_create_failed)
                    } finally {
                        busy = false
                    }
                }
        },
    ) {
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.qr_batch_creating))
        }
        draft.issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (items == null) {
            Text(
                stringResource(R.string.qr_batch_intro)
            )
            OutlinedTextField(
                draft.input,
                {
                    draft.input = it
                    draft.issue = null
                },
                label = { Text(stringResource(R.string.qr_batch_contents)) },
                minLines = 6,
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("batch-input"),
            )
        } else {
            Text(pluralStringResource(R.plurals.qr_batch_codes, items.size, items.size), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.qr_batch_review_text)
            )
            if (!busy && draft.prepared == null) {
                DropdownField(stringResource(R.string.qr_export_paper), draft.paper, PaperSize.entries.map { it to stringResource(it.title) }) {
                    draft.paper = it
                }
                DropdownField(
                    stringResource(R.string.qr_export_labels_per_page),
                    draft.grid,
                    SheetGrid.entries.map { it to stringResource(it.title) },
                ) {
                    draft.grid = it
                }
            } else Text(stringResource(R.string.qr_batch_paper_grid, stringResource(draft.paper.title), stringResource(draft.grid.title)))
            draft.prepared?.let { file ->
                Text(stringResource(if (saved) R.string.qr_batch_pdf_saved else R.string.qr_batch_pdf_ready), style = MaterialTheme.typography.titleMedium)
                // Share PDF stays in the fixed action slot; Save puts a copy where the person picks.
                Button(
                    onClick = {
                        saved = false
                        saveFile.save(file, batchFileName(draft.preparedAt ?: java.time.LocalDateTime.now()))
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("batch-save"),
                ) {
                    Text(stringResource(R.string.qr_batch_save_pdf))
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
                Text(stringResource(R.string.qr_batch_edit_inputs))
            }
            items.forEachIndexed { index, text ->
                ListItem(
                    headlineContent = { Text(text) },
                    overlineContent = { Text(stringResource(R.string.qr_batch_code_number, index + 1)) },
                )
            }
        }
    }
}
