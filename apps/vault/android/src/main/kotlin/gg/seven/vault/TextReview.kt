package gg.seven.vault

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun TextReviewScreen(model: VaultModel, draft: Presentation.TextReview) {
    VaultForm(
        stringResource(R.string.review_title),
        model,
        model::cleanPresentation,
        onSave = {
            if (model.store.paidFeaturesAvailable) {
                val title = draft.name
                val text = draft.text
                model.update(then = model::cleanPresentation) {
                    model.repository.addNote(title, text, draft.item.folder)
                }
            }
        },
        saveEnabled =
            draft.name.isNotBlank() && draft.text.isNotBlank() && model.store.paidFeaturesAvailable,
        saveLabel = stringResource(R.string.review_save),
        dirty = draft.text != draft.original || draft.name != draft.initialName,
    ) {
        Text(stringResource(R.string.review_intro))
        if (draft.original.isBlank()) Text(stringResource(R.string.review_nothing))
        OutlinedTextField(
            draft.name,
            { draft.name = it },
            label = { Text(stringResource(R.string.note_name)) },
            modifier = Modifier.fillMaxWidth().testTag("ocr-name"),
            enabled = !model.busy,
        )
        OutlinedTextField(
            draft.text,
            { draft.text = it },
            label = { Text(stringResource(R.string.review_text)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("ocr-text"),
            enabled = !model.busy,
        )
        Text(
            stringResource(R.string.review_on_device),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!model.store.paidFeaturesAvailable)
            Text(stringResource(R.string.review_gated))
    }
}

@Composable
fun PdfRangeScreen(model: VaultModel, draft: Presentation.PdfRange) {
    val plan = draft.plan
    VaultForm(
        stringResource(R.string.pages_title),
        model,
        model::cleanPresentation,
        onSave = { model.recognizePdf(draft) },
        saveLabel = stringResource(R.string.pages_recognize),
        saveEnabled = plan.issue == null,
    ) {
        val pages = draft.pageCount.toInt()
        val maximum = plan.maximumPages.toInt()
        Text(
            stringResource(
                R.string.pages_summary,
                pluralStringResource(R.plurals.pages_count, pages, pages),
                pluralStringResource(R.plurals.pages_choose, maximum, maximum),
            )
        )
        OutlinedTextField(
            draft.first,
            { draft.first = it },
            label = { Text(stringResource(R.string.pages_first)) },
            modifier = Modifier.fillMaxWidth().testTag("ocr-first"),
            singleLine = true,
            enabled = !model.busy,
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                ),
            isError = plan.issue != null,
        )
        OutlinedTextField(
            draft.last,
            { draft.last = it },
            label = { Text(stringResource(R.string.pages_last)) },
            modifier = Modifier.fillMaxWidth().testTag("ocr-last"),
            singleLine = true,
            enabled = !model.busy,
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
            isError = plan.issue != null,
            supportingText = { plan.issue?.let { Text(it.wording.text()) } },
        )
        Text(
            stringResource(R.string.pages_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
