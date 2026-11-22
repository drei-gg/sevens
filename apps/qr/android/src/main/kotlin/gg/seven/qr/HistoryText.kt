package gg.seven.qr

import android.content.res.Resources
import android.util.Base64
import gg.seven.qr.core.HistoryItem
import gg.seven.qr.core.HistoryKind
import gg.seven.qr.core.PayloadEncoding
import gg.seven.qr.core.Signal
import gg.seven.qr.core.Symbology
import gg.seven.qr.core.signalIdFromRaw
import gg.seven.qr.core.signalsInStableOrder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * History's pure text (iOS HistoryRowSummary, HistoryLabels, HistoryEmptyState, HistoryNote,
 * HistoryExportFiles, HistoryVerdictSignals). Payloads come from scanned codes, so control
 * characters are dropped and everything is length-capped. Display only: no decision is made here.
 */

object HistoryRowSummary {
    const val MAX_PAYLOAD_LENGTH = 60
    const val MAX_NOTE_LENGTH = 80

    fun shortPayload(res: Resources, item: HistoryItem): String {
        if (item.payloadEncoding == PayloadEncoding.BASE64) {
            val bytes = runCatching { Base64.decode(item.payload, Base64.DEFAULT) }.getOrNull() ?: return res.getString(R.string.qr_history_binary)
            return res.getQuantityString(R.plurals.qr_history_binary_bytes, bytes.size, bytes.size)
        }
        if (item.typeId == "link" || item.typeId == "appLink") host(item.payload)?.let { return truncated(it, MAX_PAYLOAD_LENGTH) }
        if (item.typeId == "wifi") field("S", item.payload, ';')?.let { return truncated(it, MAX_PAYLOAD_LENGTH) }
        if (item.typeId == "contact") contactName(item.payload)?.let { return truncated(it, MAX_PAYLOAD_LENGTH) }
        return truncated(item.payload, MAX_PAYLOAD_LENGTH)
    }

    /** A `KEY:value` field after the first colon, with `\;`-style escapes undone. Null when blank. */
    fun field(key: String, payload: String, separator: Char): String? {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        val body = payload.substringAfter(':', "")
        for (ch in body) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == separator -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        parts += current.toString()
        val value = parts.firstOrNull { it.startsWith("$key:") }?.drop(key.length + 1)
        return value?.takeIf { it.isNotBlank() }
    }

    /** A vCard's `FN` (or `N`) line, or a MeCard's `N:`. */
    fun contactName(payload: String): String? {
        if (payload.uppercase().startsWith("MECARD:")) return field("N", payload, ';')?.replace(",", " ")
        val lines = payload.split(Regex("\\r\\n|\\r|\\n"))
        fun value(name: String): String? {
            val line = lines.firstOrNull {
                val head = it.substringBefore(':').uppercase()
                it.contains(':') && (head == name || head.startsWith("$name;"))
            } ?: return null
            return line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
        }
        value("FN")?.let { return it }
        return value("N")?.split(';')?.reversed()?.filter { it.isNotEmpty() }?.joinToString(" ")
    }

    fun notePreview(note: String?): String? = note?.let { truncated(it, MAX_NOTE_LENGTH) }?.takeIf { it.isNotEmpty() }

    /** The host of a `scheme://authority/…` string: userinfo and port removed, lowercased. */
    fun host(payload: String): String? {
        val text = payload.trim()
        val marker = text.indexOf("://")
        if (marker < 0) return null
        val scheme = text.substring(0, marker)
        val first = scheme.firstOrNull() ?: return null
        if (!(first in 'a'..'z' || first in 'A'..'Z')) return null
        if (!scheme.all { it.isLetterOrDigit() && it.code < 128 || it in "+.-" }) return null
        var authority = text.substring(marker + 3).takeWhile { it !in "/?#\\" }
        authority.lastIndexOf('@').takeIf { it >= 0 }?.let { authority = authority.substring(it + 1) }
        var host = authority
        if (host.startsWith("[")) {
            host.indexOf(']').takeIf { it >= 0 }?.let { host = host.substring(0, it + 1) }
        } else {
            val colon = host.lastIndexOf(':')
            if (colon >= 0 && host.substring(colon + 1).all { it in '0'..'9' }) host = host.substring(0, colon)
        }
        host = cleaned(host).lowercase()
        return host.takeIf { it.isNotEmpty() }
    }

    /** Whitespace and control characters collapsed to single spaces, then cut with "…". */
    fun truncated(text: String, limit: Int): String {
        val flat = cleaned(text)
        if (QrText.length(flat) <= limit) return flat
        return QrText.prefix(flat, maxOf(limit - 1, 0)) + "…"
    }

    private fun cleaned(text: String): String {
        val out = StringBuilder()
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> pendingSpace = out.isNotEmpty()
                Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> Unit
                else -> {
                    if (pendingSpace) {
                        out.append(' ')
                        pendingSpace = false
                    }
                    out.appendCodePoint(cp)
                }
            }
        }
        return out.toString()
    }
}

object HistoryLabels {
    fun kind(res: Resources, kind: HistoryKind): String =
        res.getString(if (kind == HistoryKind.SCANNED) R.string.qr_history_scanned else R.string.qr_history_made)

    fun symbology(res: Resources, symbology: Symbology): String =
        res.getString(
            when (symbology) {
                Symbology.QR -> R.string.qr_symbology_name_qr
                Symbology.MICRO_QR -> R.string.qr_symbology_name_micro_qr
                Symbology.AZTEC -> R.string.qr_symbology_name_aztec
                Symbology.PDF417 -> R.string.qr_symbology_pdf417
                Symbology.MICRO_PDF417 -> R.string.qr_symbology_name_micro_pdf417
                Symbology.DATA_MATRIX -> R.string.qr_symbology_name_data_matrix
                Symbology.CODE128 -> R.string.qr_symbology_name_code128
                Symbology.CODE39 -> R.string.qr_symbology_name_code39
                Symbology.CODE93 -> R.string.qr_symbology_name_code93
                Symbology.EAN13 -> R.string.qr_symbology_name_ean13
                Symbology.EAN8 -> R.string.qr_symbology_name_ean8
                Symbology.UPCE -> R.string.qr_symbology_name_upce
                Symbology.ITF14 -> R.string.qr_symbology_name_itf14
                Symbology.CODABAR -> R.string.qr_symbology_name_codabar
                Symbology.GS1_DATA_BAR -> R.string.qr_symbology_name_gs1_databar
                Symbology.OTHER -> R.string.qr_symbology_name_other
            }
        )
}

/** Which body the History screen shows (SPEC §10), decided by the saved items, not the search. */
enum class HistoryEmptyState {
    LIST,
    READ_ONLY,
    HISTORY_OFF,
    LOCKED,
    NO_ITEMS;

    companion object {
        fun resolve(itemCount: Int, keepHistory: Boolean, locked: Boolean, readOnly: Boolean): HistoryEmptyState =
            when {
                readOnly -> READ_ONLY
                itemCount > 0 -> LIST
                !keepHistory -> HISTORY_OFF
                locked -> LOCKED
                else -> NO_ITEMS
            }

        /** The "Saving new scans needs the unlock" note above a non-empty list. */
        fun showsLockedNote(itemCount: Int, keepHistory: Boolean, locked: Boolean, readOnly: Boolean) =
            itemCount > 0 && locked && keepHistory && !readOnly
    }
}

object HistoryNote {
    fun normalized(text: String?) = text.orEmpty().trim()

    /** Whether "Save note" has anything to save. */
    fun isChanged(draft: String, saved: String?) = normalized(draft) != normalized(saved)
}

object HistoryExportFiles {
    /** `qr-history-2026-11-10.json`: the local calendar date, always ASCII digits. */
    fun fileName(json: Boolean, date: LocalDate = LocalDate.now(ZoneId.systemDefault())): String =
        "qr-history-" + DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).format(date) + if (json) ".json" else ".csv"
}

/** History keeps signal IDs only; this rebuilds signals for display, skipping IDs this version doesn't know. */
fun historySignals(ids: List<String>): List<Signal> =
    signalsInStableOrder(ids.mapNotNull { signalIdFromRaw(it) }.map { Signal(it, emptyMap()) })
