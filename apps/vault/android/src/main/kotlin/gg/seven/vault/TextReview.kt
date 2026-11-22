package gg.seven.vault

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun TextReviewScreen(model: VaultModel, draft: Presentation.TextReview) {
    VaultForm(
        "Review",
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
        saveLabel = "Save note",
        dirty = draft.text != draft.original || draft.name != draft.initialName,
    ) {
        Text("Review the text before saving a separate encrypted note.")
        if (draft.original.isBlank())
            Text("No text was recognized. You can enter a note here or close and try another file.")
        OutlinedTextField(
            draft.name,
            { draft.name = it },
            label = { Text("Note name") },
            modifier = Modifier.fillMaxWidth().testTag("ocr-name"),
            enabled = !model.busy,
        )
        OutlinedTextField(
            draft.text,
            { draft.text = it },
            label = { Text("Recognized text") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).testTag("ocr-text"),
            enabled = !model.busy,
        )
        Text(
            "English recognition runs on this device. The original file stays unchanged.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!model.store.paidFeaturesAvailable)
            Text(
                "Start the trial or unlock to save new notes. You can still read and copy this text."
            )
    }
}

@Composable
fun PdfRangeScreen(model: VaultModel, draft: Presentation.PdfRange) {
    val plan = draft.plan
    VaultForm(
        "Pages",
        model,
        model::cleanPresentation,
        onSave = { model.recognizePdf(draft) },
        saveLabel = "Recognize",
        saveEnabled = plan.issue == null,
    ) {
        Text(
            "${draft.pageCount} pages. Choose up to ${plan.maximumPages} pages to recognize on this device."
        )
        OutlinedTextField(
            draft.first,
            { draft.first = it },
            label = { Text("First page") },
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
            label = { Text("Last page") },
            modifier = Modifier.fillMaxWidth().testTag("ocr-last"),
            singleLine = true,
            enabled = !model.busy,
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
            isError = plan.issue != null,
            supportingText = { plan.issue?.let { Text(it) } },
        )
        Text(
            "Review the result before saving a separate encrypted note. The original PDF stays unchanged.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
