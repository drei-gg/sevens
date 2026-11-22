package gg.seven.qr

import gg.seven.qr.core.Level
import gg.seven.qr.core.Severity
import gg.seven.qr.core.Signal
import gg.seven.qr.core.SignalId
import gg.seven.qr.core.signalIdSeverity
import java.text.BreakIterator
import java.text.Normalizer

/*
 * The app's words, from the iOS String Catalog defaults (QRUI's VerdictText, SignalText and the
 * screens). One place, so a translation pass has one file to read. Typographic punctuation: ’ and …
 */

object QrText {
    // MARK: verdict (SPEC §4.1). Level 0 is "Looks normal", never a reassurance.

    fun verdict(level: Level): String =
        when (level) {
            Level.NORMAL -> "Looks normal"
            Level.CAREFUL -> "Be careful"
            Level.LIKELY_SCAM -> "Likely scam"
        }

    /** Display name of a payload type ID (also what History search matches). */
    fun typeName(typeId: String): String =
        when (typeId) {
            "link" -> "Web link"
            "appLink" -> "App link"
            "payment" -> "Payment code"
            "crypto" -> "Crypto address"
            "wifi" -> "Wi-Fi network"
            "contact" -> "Contact"
            "event" -> "Event"
            "phone" -> "Phone number"
            "sms" -> "Text message"
            "email" -> "Email"
            "geo" -> "Location"
            "text" -> "Text"
            "binary" -> "Other data"
            else -> "Other"
        }

    /** "Be careful. Web link. This link isn’t encrypted…": info signals are left out. */
    fun accessibilitySentence(level: Level, typeName: String, signals: List<Signal>): String {
        val reasons = SignalText.sentences(signals.filter { it.severity != Severity.INFO })
        return (listOf(verdict(level), typeName) + reasons)
            .map { it.trim('.', ' ') }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "$it." }
    }

    val historyReadOnlyReason = "This version can’t read the saved history, so new scans aren’t being saved."

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

    fun sentence(signal: Signal): String {
        val args = signal.args
        return when (signal.id) {
            SignalId.DANGEROUS_SCHEME ->
                arg(args, "scheme")?.let { "This code starts with “$it:”, which can run code or open files instead of showing a website." }
                    ?: "This code can run a script or open a file instead of showing a website."
            SignalId.USERINFO_TRICK ->
                arg(args, "host")?.let { "The text before the @ sign is a name, not the destination. The link really goes to $it." }
                    ?: "The text before the @ sign is a name, not the destination. The link goes to the address after it."
            SignalId.MIXED_SCRIPT -> "The address mixes letters from different alphabets, a trick used to imitate real sites."
            SignalId.LOOKALIKE_BRAND ->
                arg(args, "brand")?.let { "This address imitates the look of $it, and it isn’t one we know for them." }
                    ?: "This address imitates the look of a well-known brand, and it isn’t one we know for them."
            SignalId.BRAND_IN_DOMAIN ->
                arg(args, "brand")?.let { "The name $it appears in this address, but it isn’t one we know for them." }
                    ?: "A well-known brand’s name appears in this address, but it isn’t one we know for them."
            SignalId.NEAR_BRAND ->
                arg(args, "brand")?.let { "This address is one letter away from $it. Check the spelling closely." }
                    ?: "This address is one letter away from a well-known brand. Check the spelling closely."
            SignalId.IP_HOST -> "The address is a string of numbers instead of a name. Real services rarely do this."
            SignalId.SHORTENER -> "This is a shortened link, so its real destination is hidden until you follow it."
            SignalId.PLAIN_HTTP -> "This link isn’t encrypted, so anyone on the network could read or change what you send."
            SignalId.RISKY_TLD ->
                arg(args, "tld")?.trimStart('.')?.takeIf { it.isNotEmpty() }?.let { "Addresses ending in .$it show up often in scam reports." }
                    ?: "This address uses an ending that shows up often in scam reports."
            SignalId.DEEP_SUBDOMAINS -> "The address has an unusually long chain of parts before the main name, which can hide the real site."
            SignalId.OBFUSCATED_PATH -> "The address is very long or heavily encoded, which can hide where it really goes."
            SignalId.UNKNOWN_SCHEME ->
                arg(args, "scheme")?.let { "This code opens another app (“$it:”) instead of a website." }
                    ?: "This code opens another app instead of a website."
            SignalId.OPEN_WI_FI -> "This Wi-Fi network has no password, so others nearby could see what you do on it."
            SignalId.PAYMENT_CHECKSUM_FAIL -> "This payment code failed its built-in check. It may be damaged or altered."
            SignalId.TLS_PROBLEM -> "The site’s certificate didn’t check out, so this connection can’t be trusted."
            SignalId.SECRET_FORM_BRAND_MISMATCH -> {
                val what = secret(args["kind"])
                arg(args, "brand")?.let { "This page asks for $what and mentions $it, but it isn’t their site." }
                    ?: "This page asks for $what and mentions a well-known brand, but it isn’t their site."
            }
            SignalId.DOMAIN_VERY_NEW ->
                dayCount(args)?.let { "This domain was registered $it ago, which is very recent." }
                    ?: "This domain was registered within the last month."
            SignalId.REDIRECTS_TO_HTTP -> "This link sends you on to a page that isn’t encrypted."
            SignalId.REDIRECT_LEAVES_WEB ->
                arg(args, "scheme")?.let { "This link redirects out of the web to open another app (“$it:”)." }
                    ?: "This link redirects out of the web to open another app."
            SignalId.TOO_MANY_REDIRECTS -> "This link bounces you through an unusually long chain of redirects."
            SignalId.SCRIPT_REDIRECT -> "The page uses a script to send you somewhere else as soon as it loads."
            SignalId.ASKS_FOR_SECRET -> "This page asks you to enter ${secret(args["kind"])}."
            SignalId.FORM_POSTS_ELSEWHERE -> "A form on this page sends what you type to a different website."
            SignalId.DOWNLOADS_FILE -> "This link downloads a file instead of showing a page."
            SignalId.DOMAIN_NEW ->
                dayCount(args)?.let { "This domain was registered $it ago." } ?: "This domain was registered within the last six months."
            SignalId.ASSUMED_HTTPS -> "The code had no prefix, so it was read as an https web address."
            SignalId.SHARED_HOSTING_DOMAIN -> "This site sits on a shared hosting domain, so the domain’s age says little about it."
            SignalId.NEEDS_JAVA_SCRIPT -> "The page shows almost nothing without JavaScript, so its contents couldn’t be read."
            SignalId.AGE_UNKNOWN -> "The age of this domain couldn’t be found."
            SignalId.PEEK_INCOMPLETE -> "Peek didn’t finish, so what it shows may be incomplete."
            SignalId.BRAND_OTHER_ENDING ->
                arg(args, "brand")?.let { "This isn’t an address we know for $it." }
                    ?: "This uses a well-known brand’s name, but it isn’t an address we know for them."
            SignalId.UNKNOWN_ENDING -> "We don’t recognize this domain ending."
            SignalId.KNOWN_BY_YOU -> "You told us you know this address."
        }
    }

    /** One sentence per signal, in order; identical sentences appear once. */
    fun sentences(signals: List<Signal>): List<String> = signals.map(::sentence).distinct()

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

    private fun secret(kind: String?): String =
        when (kind) {
            "password" -> "a password"
            "code" -> "a one-time code or PIN"
            "card" -> "card details"
            else -> "private details"
        }

    /** "1 day" / "12 days" from `args["days"]`; null when absent or not a count. */
    private fun dayCount(args: Map<String, String>): String? {
        val days = args["days"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return if (days == 1) "1 day" else "$days days"
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
