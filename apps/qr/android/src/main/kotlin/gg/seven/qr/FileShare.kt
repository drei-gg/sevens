package gg.seven.qr

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Files leaving the app (History exports, made codes): written to `cache/exports`, then either
 * shared through the FileProvider with a read grant or saved where the person picks with the
 * system's Create document screen. The folder is swept the next time an export starts, so no copy
 * of History lingers (iOS deletes its temporary file when the sheet closes).
 *
 * Copy image keeps its file in `cache/clipboard` instead: exports never sweep it, so a later export
 * can't break a paste, and it holds only the latest copied image.
 */
object FileShare {
    private const val EXPORTS = "exports"
    private const val CLIPBOARD = "clipboard"

    private fun folder(context: Context) = File(context.cacheDir, EXPORTS)

    private fun clipboardFolder(context: Context) = File(context.cacheDir, CLIPBOARD)

    /** Removes earlier exports, then writes [bytes] as [name]. */
    fun write(context: Context, name: String, bytes: ByteArray): File =
        replaceIn(folder(context), name, bytes)

    /** The image Copy image puts on the clipboard, replacing the one copied before. */
    fun writeClipboard(context: Context, name: String, bytes: ByteArray): File =
        replaceIn(clipboardFolder(context), name, bytes)

    /** Empties [dir], then writes [bytes] as [name]: the folder only ever holds the newest file. */
    internal fun replaceIn(dir: File, name: String, bytes: ByteArray): File {
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, name)
        writeAtomically(file, bytes)
        return file
    }

    fun sweep(context: Context) {
        folder(context).listFiles()?.forEach { it.delete() }
    }

    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun share(context: Context, file: File, mime: String, title: String? = null) {
        val uri = uri(context, file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title ?: file.name, uri)
        title?.let { send.putExtra(Intent.EXTRA_TITLE, it) }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    /** Puts a PNG written by [writeClipboard] on the clipboard, for pasting into other apps. */
    fun copyImage(context: Context, file: File) {
        context
            .getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newUri(context.contentResolver, context.getString(R.string.qr_symbology_qr), uri(context, file)))
    }

    /** The type the Create document screen offers, from the file's extension. */
    fun mimeType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }

    /** Copies [file] into [target], a document the person just created. */
    fun copyTo(context: Context, file: File, target: Uri) {
        val out =
            context.contentResolver.openOutputStream(target, "wt")
                ?: throw FileNotFoundException(target.toString())
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    /** The Create document screen, typed from the suggested file name, so one launcher saves any format. */
    class CreateTypedDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).setType(mimeType(input))
    }
}

/** Hands a written file to the system's Create document screen; see [rememberSaveFile]. */
class FileSaver internal constructor(private val start: (File, String) -> Unit) {
    /** Asks where to save [file], suggesting [name]; the type follows the name's extension. */
    fun save(file: File, name: String = file.name) = start(file, name)
}

/**
 * Save with the system's Create document screen (Files, Drive, any document provider; no storage
 * permission). Pass [FileSaver.save] a file already written to the cache; the person picks where,
 * then the copy is made. [onSaved] hears true once the copy is written, false when it failed;
 * cancelling the screen says nothing. The pending file's path survives rotation.
 */
@Composable
fun rememberSaveFile(onSaved: (Boolean) -> Unit): FileSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnSaved by rememberUpdatedState(onSaved)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(FileShare.CreateTypedDocument()) { target ->
            val path = pending
            pending = null
            if (target == null || path == null) return@rememberLauncherForActivityResult
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        runCatching { FileShare.copyTo(context, File(path), target) }.isSuccess
                    }
                currentOnSaved(saved)
            }
        }
    return remember(launcher) {
        FileSaver { file, name ->
            pending = file.path
            try {
                launcher.launch(name)
            } catch (_: ActivityNotFoundException) {
                pending = null
                currentOnSaved(false)
            }
        }
    }
}
