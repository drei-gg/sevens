package gg.seven.qr

import gg.seven.qr.core.Analysis
import gg.seven.qr.core.EventInfo
import gg.seven.qr.core.Level
import gg.seven.qr.core.LinkInfo
import gg.seven.qr.core.MakeRequest
import gg.seven.qr.core.Payload
import gg.seven.qr.core.PaymentFormat
import gg.seven.qr.core.PaymentInfo
import gg.seven.qr.core.SignalId
import gg.seven.qr.core.WiFiInfo
import gg.seven.qr.core.knownAddressesListBased
import gg.seven.qr.core.wifiIsEnterprise
import gg.seven.qr.core.wifiIsOpen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The result sheet's decisions, without a view so they can be tested (iOS ResultActionPlan,
 * ResultLinkDisplay, ResultPayloadText, ResultMakeMapping, ResultKnownText, ResultEventFormat).
 */

/** Every action a result can offer (SPEC §3). */
enum class ResultAction { OPEN, PEEK, JOIN_WIFI, COPY_PASSWORD, WIFI_SETTINGS, ADD_CONTACT, ADD_EVENT, CALL, MESSAGE, MAIL, MAPS, OPEN_PAYMENT_IN, COPY, COPY_DETAILS, SHARE, MAKE_CODE }

/** What this device and build can do. Read once per sheet; tests pass their own. */
data class ResultCapabilities(
    /** Android 11+: the system's "Save this network?" sheet (NEHotspotConfiguration's counterpart). */
    val wifiJoinAvailable: Boolean = false,
    /** A calendar app takes new events. */
    val canAddEvent: Boolean = true,
)

/** The passphrase kind a Wi-Fi network suggestion is built with. */
enum class WiFiPassphrase { WPA2, WPA3 }

/** How the Wi-Fi card behaves (C8, enterprise rule). */
enum class WiFiJoinPlan { JOIN, SETTINGS_ONLY, COPY_AND_SETTINGS, CANNOT_JOIN }

/** One prominent action and at most one bordered one, pinned at the bottom, and the rest as chips. */
data class ResultActionLayout(val primary: ResultAction?, val secondary: ResultAction?, val chips: List<ResultAction>)

val WiFiInfo.isEnterprise
    get() = wifiIsEnterprise(this)
val WiFiInfo.isOpen
    get() = wifiIsOpen(this)

/** "link", "payment", …: the core's type ID. */
val Payload.typeId: String
    get() = gg.seven.qr.core.payloadTypeId(this)

object ResultActionPlan {
    /** Copy, Share and Make a code are always one-tap chips; the payload's own action leads. */
    private val chipActions = setOf(ResultAction.COPY, ResultAction.COPY_DETAILS, ResultAction.SHARE, ResultAction.MAKE_CODE)

    /**
     * A link leads with Open and offers Peek beside it; a likely scam leads with Peek instead, and
     * Open moves to the chips (it still asks first, D8). With no action of its own, Copy leads.
     */
    fun layout(actions: List<ResultAction>, level: Level): ResultActionLayout {
        var main = actions.filterNot { it in chipActions }
        val chips = actions.filter { it in chipActions }.toMutableList()
        if (ResultAction.OPEN in main && ResultAction.PEEK in main) {
            val rest = main.filter { it != ResultAction.OPEN && it != ResultAction.PEEK }
            if (level == Level.LIKELY_SCAM) {
                main = listOf(ResultAction.PEEK) + rest
                chips.add(0, ResultAction.OPEN)
            } else main = listOf(ResultAction.OPEN, ResultAction.PEEK) + rest
        }
        if (main.isEmpty() && chips.firstOrNull().let { it == ResultAction.COPY || it == ResultAction.COPY_DETAILS })
            main = listOf(chips.removeAt(0))
        val finalChips = if (main.size > 2) main.drop(2) + chips else chips
        return ResultActionLayout(main.getOrNull(0), main.getOrNull(1), finalChips)
    }

    /** The buttons for a payload, in display order. */
    fun actions(payload: Payload, capabilities: ResultCapabilities, hasText: Boolean = true): List<ResultAction> {
        val list = mutableListOf<ResultAction>()
        when (payload) {
            is Payload.Link -> {
                if (canOpen(payload.info)) list += listOf(ResultAction.PEEK, ResultAction.OPEN)
                list += listOf(ResultAction.COPY, ResultAction.SHARE)
            }
            is Payload.AppLink -> list += listOf(ResultAction.COPY, ResultAction.SHARE)
            is Payload.Payment -> {
                if (isUriShapedPayment(payload)) list += ResultAction.OPEN_PAYMENT_IN
                list += ResultAction.COPY_DETAILS
            }
            is Payload.Crypto -> {
                if (payload.info.scheme != null) list += ResultAction.OPEN_PAYMENT_IN
                list += ResultAction.COPY
            }
            is Payload.Wifi -> {
                val plan = wifiJoinPlan(payload.info, capabilities)
                if (plan == WiFiJoinPlan.JOIN) list += ResultAction.JOIN_WIFI
                if (hasPassword(payload.info)) list += ResultAction.COPY_PASSWORD
                // Plans the system sheet can't take still get a way there: the Wi-Fi panel.
                if (plan != WiFiJoinPlan.JOIN) list += ResultAction.WIFI_SETTINGS
            }
            is Payload.Contact -> list += listOf(ResultAction.ADD_CONTACT, ResultAction.COPY, ResultAction.SHARE)
            is Payload.Event -> {
                if (capabilities.canAddEvent) list += ResultAction.ADD_EVENT
                list += listOf(ResultAction.COPY, ResultAction.SHARE)
            }
            is Payload.Phone -> list += listOf(ResultAction.CALL, ResultAction.COPY)
            is Payload.Sms -> {
                if (payload.info.number.isNotEmpty()) list += ResultAction.MESSAGE
                list += listOf(ResultAction.COPY, ResultAction.SHARE)
            }
            is Payload.Email -> list += listOf(ResultAction.MAIL, ResultAction.COPY, ResultAction.SHARE)
            is Payload.Geo -> list += listOf(ResultAction.MAPS, ResultAction.COPY)
            is Payload.Text -> list += listOf(ResultAction.COPY, ResultAction.SHARE)
            is Payload.Binary -> Unit
        }
        if (hasText && ResultMakeMapping.supportsMake(payload)) list += ResultAction.MAKE_CODE
        return list
    }

    fun wifiJoinPlan(info: WiFiInfo, capabilities: ResultCapabilities): WiFiJoinPlan =
        when {
            info.isEnterprise -> WiFiJoinPlan.SETTINGS_ONLY
            // Android's network suggestions don't take WEP or SSIDs over 32 bytes.
            !capabilities.wifiJoinAvailable || info.security?.uppercase() == "WEP" || info.ssid.toByteArray().size > 32 -> WiFiJoinPlan.COPY_AND_SETTINGS
            info.isOpen || hasPassword(info) -> WiFiJoinPlan.JOIN
            else -> WiFiJoinPlan.CANNOT_JOIN
        }

    fun hasPassword(info: WiFiInfo) = !info.password.isNullOrEmpty()

    /**
     * Which passphrase a network suggestion takes, from the code's `T` value as the core parsed it:
     * `SAE` (what Android's own Wi-Fi sharing writes for WPA3-only networks) or a WPA3-only value is
     * WPA3; WPA, WPA2 and WPA2/WPA3 transition networks take WPA2, which transition networks accept.
     * Only asked for networks that need a password and aren't enterprise or WEP.
     */
    fun wifiPassphrase(security: String?): WiFiPassphrase {
        val s = security.orEmpty().uppercase()
        val wpa3Only = s.contains("SAE") || (s.contains("WPA3") && !s.contains("WPA2") && !s.contains("WPA/"))
        return if (wpa3Only) WiFiPassphrase.WPA3 else WiFiPassphrase.WPA2
    }

    /** D8: a likely scam asks first, naming the registrable domain. */
    fun needsOpenConfirmation(level: Level) = level == Level.LIKELY_SCAM

    /** Only http(s) links with a host can be opened or Peeked. */
    fun canOpen(link: LinkInfo): Boolean {
        if (link.hostAscii.isEmpty()) return false
        val scheme = link.scheme.lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** UPI and crypto URIs go to the system; EMVCo, EPC and Swiss QR-bill have no URI. */
    fun isUriShapedPayment(payload: Payload): Boolean =
        when (payload) {
            is Payload.Payment -> payload.info.format == PaymentFormat.UPI
            is Payload.Crypto -> payload.info.scheme != null
            else -> false
        }
}

/** What to show for a link. */
object ResultLinkDisplay {
    /** The registrable domain: the largest text after the verdict on a link result. */
    fun headline(link: LinkInfo): String =
        link.registrableDomain?.takeIf { it.isNotEmpty() }
            ?: link.hostUnicode.takeIf { it.isNotEmpty() }
            ?: if (link.scheme.isEmpty()) link.original else link.scheme + ":"

    /** The full host when it is more than the registrable domain (`login.example.com`). */
    fun fullHost(link: LinkInfo): String? = link.hostUnicode.takeIf { it.isNotEmpty() && it != headline(link) }

    /** The `xn--` form, only when the displayed host differs from it. */
    fun punycode(link: LinkInfo): String? = link.hostAscii.takeIf { it.isNotEmpty() && it != link.hostUnicode.lowercase() }

    /** What Open uses, when it isn't what was scanned (D9: a schemeless link opens as https). */
    fun openedAs(link: LinkInfo): String? =
        link.normalized.takeIf { ResultActionPlan.canOpen(link) && it.isNotEmpty() && it != link.original }
}

/** The text that Copy and Share carry, per payload. */
object ResultPayloadText {
    fun copyText(analysis: Analysis): String? =
        when (val p = analysis.payload) {
            is Payload.Link -> p.info.original
            is Payload.AppLink -> p.info.original
            is Payload.Payment -> paymentDetails(p.info)
            is Payload.Crypto -> p.info.address
            is Payload.Wifi -> p.info.password
            is Payload.Phone -> p.info.number
            is Payload.Email -> p.info.address
            is Payload.Geo -> p.info.latitude + "," + p.info.longitude
            is Payload.Text -> p.info.text
            is Payload.Contact, is Payload.Event, is Payload.Sms, is Payload.Binary -> analysis.code.text
        }

    fun shareText(analysis: Analysis): String? =
        when (val p = analysis.payload) {
            is Payload.Crypto -> p.info.address
            is Payload.Binary -> null
            else -> analysis.code.text
        }

    /** "Copy details" for a payment: one `label: value` line per fact the code states. */
    fun paymentDetails(info: PaymentInfo): String {
        val lines = mutableListOf<String>()
        fun add(label: String, value: String?) {
            if (!value.isNullOrEmpty()) lines += "$label: $value"
        }
        add("Pays", info.payeeName)
        add("Account", info.payeeAccount)
        if (!info.amount.isNullOrEmpty()) add("Amount", listOfNotNull(info.currency, info.amount).joinToString(" "))
        add("Network", info.network)
        add("City", info.city)
        add("Country", info.country)
        add("Reference", info.reference)
        add("Note", info.note)
        info.extra.keys.sorted().forEach { add(it, info.extra[it]) }
        return lines.joinToString("\n")
    }
}

/** Payload → what Make should be pre-filled with (SPEC §3 "Make code"). */
object ResultMakeMapping {
    fun request(analysis: Analysis): MakeRequest? =
        when (val p = analysis.payload) {
            is Payload.Link -> MakeRequest.Link(p.info.original)
            is Payload.Text -> MakeRequest.Text(p.info.text)
            is Payload.Wifi -> MakeRequest.Wifi(p.info)
            is Payload.Contact -> MakeRequest.Contact(p.info)
            is Payload.Event -> MakeRequest.Event(p.info)
            is Payload.Email -> MakeRequest.Email(p.info)
            is Payload.Sms -> MakeRequest.Sms(p.info)
            is Payload.Phone -> MakeRequest.Phone(p.info.number)
            is Payload.Geo -> MakeRequest.Geo(p.info)
            is Payload.AppLink, is Payload.Payment, is Payload.Crypto -> analysis.code.text?.takeIf { it.isNotEmpty() }?.let { MakeRequest.Text(it) }
            is Payload.Binary -> null
        }

    fun supportsMake(payload: Payload) = payload !is Payload.Binary
}

/** The words of the "I know this address" question (SPEC §3). Plain, no verdict language. */
object ResultKnownText {
    val listBased: Set<SignalId> by lazy { knownAddressesListBased().toSet() }

    fun question(analysis: Analysis, domain: String): String {
        val brand = analysis.signals.asSequence().filter { it.id in listBased }.mapNotNull { SignalText.arg(it.args, "brand") }.firstOrNull()
        if (brand != null) return "This address uses $brand’s name, but it isn’t one we know."
        val name = SignalText.arg(mapOf("d" to domain), "d") ?: domain
        val ids = analysis.signals.map { it.id }.toSet()
        return when {
            SignalId.SHORTENER in ids -> "$name is a link shortener, so we can’t tell where it leads."
            SignalId.RISKY_TLD in ids -> "$name ends in a domain ending scammers use a lot, and it isn’t an address we know."
            else -> "$name isn’t an address we know."
        }
    }
}

/**
 * Event times (see EventInfo): UTC and TZID times are absolute instants, shown in the device's
 * zone. Floating and all-day times hold the wall-clock fields read as GMT, so they are formatted in
 * GMT and shown as written.
 */
object ResultEventFormat {
    fun isWallClock(event: EventInfo) = event.isAllDay || event.isFloating

    private fun fmt(instant: Instant, date: FormatStyle?, time: FormatStyle?, zone: ZoneId, locale: Locale): String {
        val f =
            when {
                date != null && time != null -> DateTimeFormatter.ofLocalizedDateTime(date, time)
                date != null -> DateTimeFormatter.ofLocalizedDate(date)
                else -> DateTimeFormatter.ofLocalizedTime(time ?: FormatStyle.SHORT)
            }
        return f.withLocale(locale).format(instant.atZone(zone))
    }

    /** "Tuesday, 12 November 2026, 14:00 – 15:00", or the all-day date(s). Null without a start. */
    fun timeText(event: EventInfo, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val start = event.start ?: return null
        val z = if (isWallClock(event)) ZoneOffset.UTC else zone
        val sep = " – "
        if (event.isAllDay) {
            val first = fmt(start, FormatStyle.FULL, null, z, locale)
            val end = event.end ?: return first
            // iCalendar's DTEND for a date is the day after the last one.
            val last = end.atZone(z).minusDays(1).toInstant()
            if (last.atZone(z).toLocalDate() == start.atZone(z).toLocalDate() || !last.isAfter(start)) return first
            return first + sep + fmt(last, FormatStyle.FULL, null, z, locale)
        }
        val day = fmt(start, FormatStyle.FULL, null, z, locale)
        val startTime = fmt(start, null, FormatStyle.SHORT, z, locale)
        val end = event.end?.takeIf { it.isAfter(start) } ?: return "$day, $startTime"
        if (end.atZone(z).toLocalDate() == start.atZone(z).toLocalDate()) return "$day, $startTime$sep" + fmt(end, null, FormatStyle.SHORT, z, locale)
        return "$day, $startTime$sep" + fmt(end, FormatStyle.FULL, FormatStyle.SHORT, z, locale)
    }

    /** For an absolute time given in another zone: the start in that zone. Null when nothing to add. */
    fun organiserText(event: EventInfo, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? {
        if (isWallClock(event)) return null
        val start = event.start ?: return null
        val id = event.timeZoneId ?: return null
        val other = runCatching { ZoneId.of(id) }.getOrNull() ?: return null
        if (other.rules.getOffset(start) == zone.rules.getOffset(start)) return null
        return "${fmt(start, null, FormatStyle.SHORT, other, locale)} in $id"
    }

    /** Reads GMT wall-clock fields and returns the same fields in [zone]. */
    fun localFromWallClock(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
        LocalDateTime.ofInstant(instant, ZoneOffset.UTC).atZone(zone).toInstant()

    data class CalendarDates(val start: Instant, val end: Instant, val allDay: Boolean)

    /**
     * Dates for the calendar: wall-clock times become local; a missing end is one hour after the
     * start; an all-day end is the last day.
     */
    fun calendarDates(event: EventInfo, zone: ZoneId = ZoneId.systemDefault()): CalendarDates? {
        val rawStart = event.start ?: return null
        val wall = isWallClock(event)
        val start = if (wall) localFromWallClock(rawStart, zone) else rawStart
        if (event.isAllDay) {
            var end = start
            event.end?.let { rawEnd ->
                val last = localFromWallClock(rawEnd, zone).atZone(zone).minusDays(1).toInstant()
                if (last.isAfter(start)) end = last
            }
            return CalendarDates(start, end, true)
        }
        var end = start.plusSeconds(3600)
        event.end?.let { rawEnd ->
            val candidate = if (wall) localFromWallClock(rawEnd, zone) else rawEnd
            if (candidate.isAfter(start)) end = candidate
        }
        return CalendarDates(start, end, false)
    }

    /** `YYYY-MM-DD` or `YYYYMMDD` (a contact's birthday); anything else is dropped. */
    fun birthday(text: String?): LocalDate? {
        text ?: return null
        val digits = text.filter { it.isDigit() }
        if (digits.length != 8 || !text.all { it.isDigit() || it == '-' }) return null
        return runCatching { LocalDate.of(digits.take(4).toInt(), digits.substring(4, 6).toInt(), digits.takeLast(2).toInt()) }.getOrNull()
    }
}

/** Digits, `+`, `*`, `#` and pause characters only. */
fun dialable(number: String): String = number.filter { it in "0123456789+*#,;" }
