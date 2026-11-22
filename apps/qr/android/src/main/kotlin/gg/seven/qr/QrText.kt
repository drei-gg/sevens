package gg.seven.qr

import android.content.res.Resources
import gg.seven.qr.core.Level
import gg.seven.qr.core.Severity
import gg.seven.qr.core.Signal
import gg.seven.qr.core.SignalId
import gg.seven.qr.core.signalIdSeverity
import java.text.BreakIterator
import java.text.Normalizer

/*
 * The app's words, from the iOS String Catalog defaults (QRUI's VerdictText, SignalText and the
 * screens), are in res/values/strings.xml; these objects pick which one applies. Typographic
 * punctuation: ’ and …
 */

object QrText {
    // MARK: verdict (SPEC §4.1). Level 0 is "Looks normal", never a reassurance.

    fun verdict(res: Resources, level: Level): String =
        res.getString(
            when (level) {
                Level.NORMAL -> R.string.qr_verdict_normal
                Level.CAREFUL -> R.string.qr_verdict_careful
                Level.LIKELY_SCAM -> R.string.qr_verdict_scam
            }
        )

    /** Display name of a payload type ID (also what History search matches). */
    fun typeName(res: Resources, typeId: String): String =
        res.getString(
            when (typeId) {
                "link" -> R.string.qr_type_link
                "appLink" -> R.string.qr_type_app_link
                "payment" -> R.string.qr_type_payment
                "crypto" -> R.string.qr_type_crypto
                "wifi" -> R.string.qr_type_wifi
                "contact" -> R.string.qr_type_contact
                "event" -> R.string.qr_type_event
                "phone" -> R.string.qr_type_phone
                "sms" -> R.string.qr_type_sms
                "email" -> R.string.qr_type_email
                "geo" -> R.string.qr_type_geo
                "text" -> R.string.qr_type_text
                "binary" -> R.string.qr_type_binary
                else -> R.string.qr_type_other
            }
        )

    /** "Be careful. Web link. This link isn’t encrypted…": info signals are left out. */
    fun accessibilitySentence(res: Resources, level: Level, typeName: String, signals: List<Signal>): String {
        val reasons = SignalText.sentences(res, signals.filter { it.severity != Severity.INFO })
        return (listOf(verdict(res, level), typeName) + reasons)
            .map { it.trim('.', ' ') }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "$it." }
    }

    // MARK: text helpers

    /** Case- and accent-insensitive containment, as Swift's `.caseInsensitive, .diacriticInsensitive`. */
    fun foldedContains(haystack: String, needle: String): Boolean = fold(haystack).contains(fold(needle))

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** The first [count] characters (grapheme clusters, as Swift counts them). */
    fun prefix(text: String, count: Int): String {
        if (count <= 0) return ""
        val breaks = BreakIterator.getCharacterInstance().apply { setText(text) }
        var end = 0
        repeat(count) {
            val next = breaks.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }

    /** Characters as Swift's `String.count`. */
    fun length(text: String): Int {
        val it = BreakIterator.getCharacterInstance().apply { setText(text) }
        var n = 0
        while (it.next() != BreakIterator.DONE) n++
        return n
    }

    /** Cut to [limit] characters with "…". */
    fun cut(text: String, limit: Int): String = if (length(text) > limit) prefix(text, limit) + "…" else text
}

val Signal.severity: Severity
    get() = signalIdSeverity(id)

/**
 * D5: the core gives signal IDs and args; this turns each into one plain sentence. At most 20
 * words, no blame, no scores. Unknown fails soft: where a list is only incomplete the sentence says
 * what we don't know. Every [SignalId] has a sentence, with or without its args.
 */
object SignalText {
    /** The longest an arg is shown (they come from scanned codes). */
    const val MAX_ARG_LENGTH = 40

    fun sentence(res: Resources, signal: Signal): String {
        val args = signal.args
        fun with(key: String, withArg: Int, without: Int): String =
            arg(args, key)?.let { res.getString(withArg, it) } ?: res.getString(without)
        return when (signal.id) {
            SignalId.DANGEROUS_SCHEME -> with("scheme", R.string.qr_signal_dangerous_scheme_named, R.string.qr_signal_dangerous_scheme)
            SignalId.USERINFO_TRICK -> with("host", R.string.qr_signal_userinfo_trick_named, R.string.qr_signal_userinfo_trick)
            SignalId.MIXED_SCRIPT -> res.getString(R.string.qr_signal_mixed_script)
            SignalId.LOOKALIKE_BRAND -> with("brand", R.string.qr_signal_lookalike_brand_named, R.string.qr_signal_lookalike_brand)
            SignalId.BRAND_IN_DOMAIN -> with("brand", R.string.qr_signal_brand_in_domain_named, R.string.qr_signal_brand_in_domain)
            SignalId.NEAR_BRAND -> with("brand", R.string.qr_signal_near_brand_named, R.string.qr_signal_near_brand)
            SignalId.IP_HOST -> res.getString(R.string.qr_signal_ip_host)
            SignalId.SHORTENER -> res.getString(R.string.qr_signal_shortener)
            SignalId.PLAIN_HTTP -> res.getString(R.string.qr_signal_plain_http)
            SignalId.RISKY_TLD ->
                arg(args, "tld")?.trimStart('.')?.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.qr_signal_risky_tld_named, it) }
                    ?: res.getString(R.string.qr_signal_risky_tld)
            SignalId.DEEP_SUBDOMAINS -> res.getString(R.string.qr_signal_deep_subdomains)
            SignalId.OBFUSCATED_PATH -> res.getString(R.string.qr_signal_obfuscated_path)
            SignalId.UNKNOWN_SCHEME -> with("scheme", R.string.qr_signal_unknown_scheme_named, R.string.qr_signal_unknown_scheme)
            SignalId.OPEN_WI_FI -> res.getString(R.string.qr_signal_open_wifi)
            SignalId.PAYMENT_CHECKSUM_FAIL -> res.getString(R.string.qr_signal_payment_checksum_fail)
            SignalId.TLS_PROBLEM -> res.getString(R.string.qr_signal_tls_problem)
            SignalId.SECRET_FORM_BRAND_MISMATCH -> {
                val what = secret(res, args["kind"])
                arg(args, "brand")?.let { res.getString(R.string.qr_signal_secret_form_brand_named, what, it) }
                    ?: res.getString(R.string.qr_signal_secret_form_brand, what)
            }
            SignalId.DOMAIN_VERY_NEW ->
                dayCount(res, args)?.let { res.getString(R.string.qr_signal_domain_very_new_days, it) }
                    ?: res.getString(R.string.qr_signal_domain_very_new)
            SignalId.REDIRECTS_TO_HTTP -> res.getString(R.string.qr_signal_redirects_to_http)
            SignalId.REDIRECT_LEAVES_WEB -> with("scheme", R.string.qr_signal_redirect_leaves_web_named, R.string.qr_signal_redirect_leaves_web)
            SignalId.TOO_MANY_REDIRECTS -> res.getString(R.string.qr_signal_too_many_redirects)
            SignalId.SCRIPT_REDIRECT -> res.getString(R.string.qr_signal_script_redirect)
            SignalId.ASKS_FOR_SECRET -> res.getString(R.string.qr_signal_asks_for_secret, secret(res, args["kind"]))
            SignalId.FORM_POSTS_ELSEWHERE -> res.getString(R.string.qr_signal_form_posts_elsewhere)
            SignalId.DOWNLOADS_FILE -> res.getString(R.string.qr_signal_downloads_file)
            SignalId.DOMAIN_NEW ->
                dayCount(res, args)?.let { res.getString(R.string.qr_signal_domain_new_days, it) }
                    ?: res.getString(R.string.qr_signal_domain_new)
            SignalId.ASSUMED_HTTPS -> res.getString(R.string.qr_signal_assumed_https)
            SignalId.SHARED_HOSTING_DOMAIN -> res.getString(R.string.qr_signal_shared_hosting_domain)
            SignalId.NEEDS_JAVA_SCRIPT -> res.getString(R.string.qr_signal_needs_javascript)
            SignalId.AGE_UNKNOWN -> res.getString(R.string.qr_signal_age_unknown)
            SignalId.PEEK_INCOMPLETE -> res.getString(R.string.qr_signal_peek_incomplete)
            SignalId.BRAND_OTHER_ENDING -> with("brand", R.string.qr_signal_brand_other_ending_named, R.string.qr_signal_brand_other_ending)
            SignalId.UNKNOWN_ENDING -> res.getString(R.string.qr_signal_unknown_ending)
            SignalId.KNOWN_BY_YOU -> res.getString(R.string.qr_signal_known_by_you)
        }
    }

    /** One sentence per signal, in order; identical sentences appear once. */
    fun sentences(res: Resources, signals: List<Signal>): List<String> = signals.map { sentence(res, it) }.distinct()

    /** A non-empty arg without control or invisible formatting characters, cut to [MAX_ARG_LENGTH]. */
    fun arg(args: Map<String, String>, key: String): String? {
        val raw = args[key] ?: return null
        val kept = buildString {
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> Unit
                    else -> appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
        }.trim(' ', '\t')
        if (kept.isEmpty()) return null
        return QrText.cut(kept, MAX_ARG_LENGTH)
    }

    private fun secret(res: Resources, kind: String?): String =
        res.getString(
            when (kind) {
                "password" -> R.string.qr_secret_password
                "code" -> R.string.qr_secret_code
                "card" -> R.string.qr_secret_card
                else -> R.string.qr_secret_other
            }
        )

    /** "1 day" / "12 days" from `args["days"]`; null when absent or not a count. */
    private fun dayCount(res: Resources, args: Map<String, String>): String? {
        val days = args["days"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return res.getQuantityString(R.plurals.qr_days, days, days)
    }
}

/**
 * What's new (SPEC §11, iOS SettingsInfo.changelog): `CHANGELOG.md`, copied into the assets at build
 * time from apps/qr. Null when it isn't bundled, empty or over 512 KB, which hides the row.
 */
object Changelog {
    const val ASSET = "CHANGELOG.md"
    private const val MAX_BYTES = 512 * 1024

    fun load(context: android.content.Context): String? =
        runCatching {
                context.assets.open(ASSET).use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (bytes.size() <= MAX_BYTES) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        bytes.write(chunk, 0, n)
                    }
                    if (bytes.size() > MAX_BYTES) null else bytes.toString("UTF-8")
                }
            }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    sealed interface Block {
        val text: String

        data class Heading(override val text: String) : Block

        data class Paragraph(override val text: String) : Block

        data class Bullet(override val text: String) : Block
    }

    /**
     * The changelog's own Markdown, read as plain blocks: `##` lines are headings, `-` or `*` lines
     * bullets (wrapped lines join them), the rest paragraphs. The top `# Changelog` title is left out:
     * the screen is already called What's new.
     */
    fun blocks(text: String): List<Block> {
        val out = mutableListOf<Block>()
        var open: StringBuilder? = null
        var bullet = false
        fun close() {
            val body = open?.toString()?.trim()
            if (!body.isNullOrEmpty()) out += if (bullet) Block.Bullet(body) else Block.Paragraph(body)
            open = null
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> close()
                line.startsWith("#") -> {
                    close()
                    val level = line.takeWhile { it == '#' }.length
                    val title = line.drop(level).trim()
                    if (level > 1 && title.isNotEmpty()) out += Block.Heading(title)
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    close()
                    open = StringBuilder(line.drop(2).trim())
                    bullet = true
                }
                open != null -> open!!.append(' ').append(line)
                else -> {
                    open = StringBuilder(line)
                    bullet = false
                }
            }
        }
        close()
        return out
    }
}
