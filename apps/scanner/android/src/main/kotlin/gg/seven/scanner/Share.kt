package gg.seven.scanner

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/*
 * The share sheet (iOS ShareLink / UIActivityViewController) and copying a prepared file to a
 * place the person picks (iOS SevenFileExportPicker, here the system's create-document picker).
 */

/**
 * Shares recognized text. Long text goes as a UTF-8 file instead of an Intent extra, which the
 * Binder transaction limit (about 1 MB for the whole transaction) would otherwise reject.
 */
fun shareText(context: Context, text: String, filename: String = "Recognized text.txt") {
    if (text.toByteArray(Charsets.UTF_8).size <= SHARE_TEXT_INLINE_BYTES) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null))
        return
    }
    val file = Processing.exportFile(context, filename)
    file.writeText(text, Charsets.UTF_8)
    shareFile(context, file, "text/plain")
}

/** Text up to this size travels inline; larger text is shared as a file. */
const val SHARE_TEXT_INLINE_BYTES = 100_000

/** Shares an app-owned export through the app's file provider, readable by the chosen app only. */
fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

/** Copies [file] into the document the person created with the system picker. */
fun copyTo(context: Context, file: File, target: Uri) {
    val out =
        context.contentResolver.openOutputStream(target, "wt")
            ?: throw IllegalStateException(context.getString(R.string.error_unwritable))
    out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
}
