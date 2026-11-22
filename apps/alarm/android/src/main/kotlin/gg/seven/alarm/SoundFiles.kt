package gg.seven.alarm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import gg.seven.alarm.core.*
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owned copies work after reboot; no external provider grant is needed at alarm time. */
class SoundFiles(
    context: Context,
    internal val directory: File =
        File(context.createDeviceProtectedStorageContext().filesDir, "AlarmLoops/Sounds"),
) {
    private val resolver = context.contentResolver

    fun file(sound: ImportedSound): File = File(directory, "custom-${sound.id}.wav")

    fun available(sound: ImportedSound): Boolean =
        file(sound).let { it.isFile && it.length() in 1..importedSoundMaxBytes().toLong() }

    suspend fun import(uri: Uri): ImportedSound =
        withContext(Dispatchers.IO) {
            val name =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Imported sound"
            resolver.openInputStream(uri)?.use { import(it, name) }
                ?: error("This sound could not be opened.")
        }

    internal fun import(input: InputStream, name: String): ImportedSound {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        val limit = importedSoundMaxBytes().toInt()
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        val data = output.toByteArray()
        val prepared = importedSoundPrepare(data)
        val points = name.codePoints().filter { !Character.isISOControl(it) }.limit(80).toArray()
        val sound =
            ImportedSound(
                UUID.randomUUID(),
                String(points, 0, points.size).trim().ifBlank { "Imported sound" },
            )
        check(directory.isDirectory || directory.mkdirs()) {
            "The sound folder could not be created."
        }
        check(
            directory.listFiles().orEmpty().sumOf { it.length() } + prepared.wav.size <=
                128L * 1024 * 1024
        ) {
            "Sound storage is full. Close the editor to clear unused imports, then try again."
        }
        val atomic = AtomicFile(file(sound))
        val stream = atomic.startWrite()
        try {
            stream.write(prepared.wav)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        return sound
    }

    fun prune(archive: LoopArchive, draft: Routine? = null) {
        val retained =
            (importedSoundReferences(archive) + listOfNotNull(draft?.importedSound?.id))
                .map { "custom-$it.wav" }
                .toSet()
        directory
            .listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith("custom-") && it.extension == "wav" && it.name !in retained
            }
            .forEach { it.delete() }
    }
}
