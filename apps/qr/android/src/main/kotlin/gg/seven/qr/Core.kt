package gg.seven.qr

import android.content.Context
import gg.seven.qr.core.Analysis
import gg.seven.qr.core.Analyzer
import gg.seven.qr.core.BundledData
import gg.seven.qr.core.KnownAddresses
import gg.seven.qr.core.ScannedCode
import gg.seven.qr.core.Symbology
import gg.seven.qr.core.bundledDataReload
import gg.seven.qr.core.bundledDataShared
import gg.seven.qr.core.scannedCodeFromText

/*
 * The app's way into the Rust core (apps/qr/core), the counterpart of iOS QRCore/Facade.swift: it
 * only converts and starts things. Every rule (classifier, checks, verdicts, payments, files) is in
 * the crate.
 */

/** The scam lists in use: the bundled ones, or the saved download when it is newer (SPEC §4.3). */
object QrLists {
    @Volatile private var started = false

    /** Hands the core what [ListsStore] kept, once per process. Cheap after the first call. */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            bundledDataReload(ListsStore(context.applicationContext).read())
            started = true
        }
    }

    /** The shared lists; [start] must have run (every entry point calls it in onCreate). */
    fun shared(): BundledData = bundledDataShared()

    /** Swaps in the bundled lists or a newer download, after an accepted download or a reset. */
    fun reload(downloaded: ByteArray?) = bundledDataReload(downloaded)
}

/** Offline, synchronous: the verdict for one scan, with the addresses the person knows applied. */
fun analyze(code: ScannedCode, known: KnownAddresses = KnownAddresses()): Analysis =
    Analyzer(QrLists.shared(), known).use { it.analyze(code) }

/** A text code; its bytes are the UTF-8. */
fun textCode(text: String, symbology: Symbology = Symbology.QR): ScannedCode =
    scannedCodeFromText(text, symbology)

/**
 * Value identity for a scanned code. The binding's data class compares its `bytes` by reference,
 * so de-duplication (the debouncer, photo results) goes through this key.
 */
data class CodeKey(val text: String?, val bytes: List<Byte>, val symbology: Symbology) {
    companion object {
        fun of(code: ScannedCode) = CodeKey(code.text, code.bytes.toList(), code.symbology)
    }
}

val ScannedCode.key: CodeKey
    get() = CodeKey.of(this)
