package gg.seven.qr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Files handed to the share sheet (History exports, made codes): written to `cache/exports`, shared
 * through the FileProvider with a read grant, and swept the next time an export starts, so no copy
 * of History lingers (iOS deletes its temporary file when the sheet closes).
 */
object FileShare {
    private fun folder(context: Context) = File(context.cacheDir, "exports")

    /** Removes earlier exports, then writes [bytes] as [name]. */
    fun write(context: Context, name: String, bytes: ByteArray): File {
        val dir = folder(context)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, name)
        writeAtomically(file, bytes)
        return file
    }

    fun sweep(context: Context) {
        folder(context).listFiles()?.forEach { it.delete() }
    }

    fun share(context: Context, file: File, mime: String, title: String? = null) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title ?: file.name, uri)
        title?.let { send.putExtra(Intent.EXTRA_TITLE, it) }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
