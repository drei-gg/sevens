package gg.seven.bloom

/** What an export writes, prepared while the journal was open. Passphrases are never kept. */
internal class PendingExport(val name: String, val bytes: ByteArray)

/** A durable record of the document being written, so an interrupted write is found on start. */
internal interface ExportMarker {
    fun read(): String?

    fun write(uri: String?)
}

/**
 * System-picker exports that survive the app lock and process death, without exempting the picker
 * from the lock. The bytes are prepared before the picker opens, so completing the export reads no
 * locked state and needs no passphrase. Documents are URI strings here; Android types stay out.
 *
 * - [prepare] holds one export and returns the file name for the picker.
 * - [picked] with null (cancelled) forgets it; the picker created nothing.
 * - [picked] with a document records it durably, then asks for the write, or for its deletion when
 *   the prepared bytes were lost (the process was recreated while the picker was open).
 * - [finished] releases the record once the write succeeded or the document was deleted.
 * - [recover], on start, asks to delete a document whose write never finished.
 */
internal class ExportFlow(private val marker: ExportMarker) {
    sealed interface Step {
        data object None : Step

        class Write(val uri: String, val bytes: ByteArray) : Step

        /** [interrupted]: the person asked for this export but it cannot be completed. */
        data class Discard(val uri: String, val interrupted: Boolean) : Step
    }

    private var prepared: PendingExport? = null

    val waiting: Boolean
        get() = prepared != null

    fun prepare(export: PendingExport): String {
        prepared = export
        return export.name
    }

    fun picked(uri: String?): Step {
        val export = prepared
        prepared = null
        if (uri == null) return Step.None
        marker.write(uri)
        return if (export == null) Step.Discard(uri, interrupted = true)
        else Step.Write(uri, export.bytes)
    }

    fun finished(uri: String) {
        if (marker.read() == uri) marker.write(null)
    }

    fun recover(): Step = marker.read()?.let { Step.Discard(it, interrupted = false) } ?: Step.None
}
