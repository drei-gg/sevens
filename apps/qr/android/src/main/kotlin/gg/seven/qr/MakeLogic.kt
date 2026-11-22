package gg.seven.qr

import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.oned.Code128Writer
import com.google.zxing.pdf417.encoder.PDF417
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import gg.seven.qr.core.BitMatrix
import gg.seven.qr.core.ContactInfo
import gg.seven.qr.core.EmailInfo
import gg.seven.qr.core.EventInfo
import gg.seven.qr.core.GeoInfo
import gg.seven.qr.core.MakeRequest
import gg.seven.qr.core.SmsInfo
import gg.seven.qr.core.Symbology
import gg.seven.qr.core.WiFiInfo
import gg.seven.qr.core.bitMatrixNew
import gg.seven.qr.core.quietZoneAdd
import gg.seven.qr.core.quietZoneModules
import gg.seven.qr.core.phoneNumberInternational
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Make's rules that stay native (iOS MakeFormState, MakeFormValidation, MakeSymbology, Correction,
 * CodeGenerator, MakeGeometry). The payload text comes from the core's encoder; the module grid
 * comes from ZXing (CoreImage's generators on iOS); quiet zones, contrast, the SVG writer and the
 * label sheet are the core's.
 */

/** The nine kinds of code Make builds (SPEC §8). */
enum class MakeFormKind(val title: String, val typeId: String) {
    LINK("Web link", "link"),
    TEXT("Text", "text"),
    WIFI("Wi-Fi network", "wifi"),
    CONTACT("Contact", "contact"),
    EVENT("Event", "event"),
    EMAIL("Email", "email"),
    SMS("Text message", "sms"),
    PHONE("Phone number", "phone"),
    LOCATION("Location", "geo");

    /** Short, ASCII, used in export file names. */
    val fileToken
        get() = name.lowercase()

    companion object {
        fun of(request: MakeRequest): MakeFormKind =
            when (request) {
                is MakeRequest.Link -> LINK
                is MakeRequest.Text -> TEXT
                is MakeRequest.Wifi -> WIFI
                is MakeRequest.Contact -> CONTACT
                is MakeRequest.Event -> EVENT
                is MakeRequest.Email -> EMAIL
                is MakeRequest.Sms -> SMS
                is MakeRequest.Phone -> PHONE
                is MakeRequest.Geo -> LOCATION
            }
    }
}

/** The four symbologies Make offers. */
enum class MakeSymbology(val raw: String, val title: String, val symbology: Symbology) {
    QR("qr", "QR code", Symbology.QR),
    AZTEC("aztec", "Aztec code", Symbology.AZTEC),
    PDF417("pdf417", "PDF417", Symbology.PDF417),
    CODE128("code128", "Code 128 barcode", Symbology.CODE128);

    /** Only QR and Aztec use the correction setting. */
    val supportsCorrection
        get() = this == QR || this == AZTEC

    /** "QR code for <short payload>": the TalkBack description of the picture. */
    fun accessibilityLabel(payload: String): String {
        val flat = payload.replace("\r\n", " ").replace("\n", " ")
        return "$title for ${QrText.cut(flat, 40)}"
    }
}

/**
 * Error correction. For QR, Auto is the smallest version at M, then the highest level that fits
 * that same version. Aztec takes a percentage: L 5, M/Auto 23, Q 40, H 60.
 */
enum class Correction(val title: String) {
    AUTO("Auto"),
    L("L, lowest"),
    M("M, medium"),
    Q("Q, higher"),
    H("H, highest");

    val qrLevel: ErrorCorrectionLevel
        get() =
            when (this) {
                AUTO, M -> ErrorCorrectionLevel.M
                L -> ErrorCorrectionLevel.L
                Q -> ErrorCorrectionLevel.Q
                H -> ErrorCorrectionLevel.H
            }

    val aztecPercent: Int
        get() =
            when (this) {
                L -> 5
                AUTO, M -> 23
                Q -> 40
                H -> 60
            }
}

/** Why a code could not be made; [message] is the inline text under the field. */
enum class MakeError(val message: String) {
    EMPTY("Enter something to turn into a code."),
    TOO_LONG("This is too long for the chosen code type. Shorten it or pick another type."),
    NOT_ASCII("Code 128 only supports plain ASCII characters. Remove accents and symbols, or pick another type."),
    GENERATOR_FAILED("The code couldn’t be created. Try different content."),
}

class MakeException(val error: MakeError) : Exception(error.message)

/** Text to a module grid (dark = true), with the core's quiet zone around it. */
object CodeGenerator {
    /** Longest input handed to the generators, bytes (no QR holds more than 2953). */
    const val MAX_INPUT_BYTES = 8192
    /** PDF417 rows are drawn this many modules tall (the usual minimum row height). */
    const val PDF417_ROW_HEIGHT = 3

    /**
     * The UTF-8 bytes as one Latin-1 character each, so ZXing writes the exact bytes in byte mode
     * without an ECI header (CoreImage writes raw bytes too), while still choosing the denser
     * numeric and alphanumeric modes where the text allows.
     */
    private fun raw(text: String) = String(text.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    fun generate(text: String, symbology: MakeSymbology, correction: Correction): BitMatrix {
        if (text.isEmpty()) throw MakeException(MakeError.EMPTY)
        if (symbology == MakeSymbology.CODE128 && text.any { it.code >= 128 }) throw MakeException(MakeError.NOT_ASCII)
        if (text.toByteArray().size > MAX_INPUT_BYTES) throw MakeException(MakeError.TOO_LONG)
        val matrix =
            when (symbology) {
                MakeSymbology.QR -> if (correction == Correction.AUTO) autoQr(text).second else qr(text, correction.qrLevel)
                MakeSymbology.AZTEC -> aztec(text, correction.aztecPercent)
                MakeSymbology.PDF417 -> pdf417(text)
                MakeSymbology.CODE128 -> code128(text)
            }
        val quiet = quietZoneModules(symbology.symbology)
        return quietZoneAdd(matrix, quiet.horizontal, quiet.vertical)
    }

    fun qr(text: String, level: ErrorCorrectionLevel): BitMatrix {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1", EncodeHintType.QR_COMPACT to "true")
        val code =
            try {
                com.google.zxing.qrcode.encoder.Encoder.encode(raw(text), level, hints)
            } catch (_: WriterException) {
                throw MakeException(MakeError.TOO_LONG)
            } catch (_: IllegalArgumentException) {
                throw MakeException(MakeError.TOO_LONG)
            }
        val m = code.matrix
        return matrix(m.width, m.height) { x, y -> m.get(x, y).toInt() == 1 }
    }

    /**
     * SPEC §8: Auto finds the smallest version at level M, then the highest level (H, then Q) that
     * still fits that same version, so the code never grows because of it. No quiet zone.
     */
    fun autoQr(text: String): Pair<Correction, BitMatrix> {
        val base = qr(text, ErrorCorrectionLevel.M)
        for (level in listOf(Correction.H, Correction.Q)) {
            val m = runCatching { qr(text, level.qrLevel) }.getOrNull() ?: continue
            if (m.width == base.width) return level to m
        }
        return Correction.M to base
    }

    /** The level Auto settles on for this text (M, Q or H); null when it can't be made. */
    fun autoLevel(text: String): Correction? = runCatching { autoQr(text).first }.getOrNull()

    /** QR version (1…40) of a generated matrix, which includes the quiet zone; null if not a QR size. */
    fun qrVersion(matrix: BitMatrix): Int? {
        val modules = matrix.width.toInt() - 2 * quietZoneModules(Symbology.QR).horizontal.toInt()
        if (matrix.width != matrix.height || modules < 21 || (modules - 17) % 4 != 0) return null
        return (modules - 17) / 4
    }

    private fun aztec(text: String, percent: Int): BitMatrix {
        val code =
            try {
                com.google.zxing.aztec.encoder.Encoder.encode(text.toByteArray(Charsets.UTF_8), percent, 0)
            } catch (_: IllegalArgumentException) {
                throw MakeException(MakeError.TOO_LONG)
            }
        val m = code.matrix
        return matrix(m.width, m.height) { x, y -> m.get(x, y) }
    }

    private fun pdf417(text: String): BitMatrix {
        val rows =
            try {
                PDF417().apply { generateBarcodeLogic(raw(text), 2, false) }.barcodeMatrix.getScaledMatrix(1, PDF417_ROW_HEIGHT)
            } catch (_: WriterException) {
                throw MakeException(MakeError.TOO_LONG)
            }
        if (rows.isEmpty() || rows[0].isEmpty()) throw MakeException(MakeError.GENERATOR_FAILED)
        // ZXing's matrix runs bottom to top.
        val height = rows.size
        return matrix(rows[0].size, height) { x, y -> rows[height - 1 - y][x].toInt() == 1 }
    }

    /** A single row; renderers stretch it (see [MakeGeometry.rows]). */
    private fun code128(text: String): BitMatrix {
        val pattern =
            try {
                Code128Writer().encode(text)
            } catch (_: IllegalArgumentException) {
                throw MakeException(MakeError.TOO_LONG)
            }
        // Trim the writer's own light edges: the core adds the quiet zone.
        val first = pattern.indexOfFirst { it }
        val last = pattern.indexOfLast { it }
        if (first < 0) throw MakeException(MakeError.GENERATOR_FAILED)
        val bars = pattern.copyOfRange(first, last + 1)
        return bitMatrixNew(bars.size.toLong(), 1, bars.toList())
    }

    private inline fun matrix(width: Int, height: Int, dark: (Int, Int) -> Boolean): BitMatrix {
        val bits = ArrayList<Boolean>(width * height)
        for (y in 0 until height) for (x in 0 until width) bits += dark(x, y)
        return bitMatrixNew(width.toLong(), height.toLong(), bits)
    }
}

/** Shared drawing geometry for the renderers. */
object MakeGeometry {
    /** Bar height of a one-row (1D) code as a share of its width. */
    const val BAR_HEIGHT_RATIO = 0.3

    fun rows(matrix: BitMatrix): Int =
        if (matrix.height != 1L) matrix.height.toInt() else maxOf(1, Math.round(matrix.width * BAR_HEIGHT_RATIO).toInt())

    fun dark(matrix: BitMatrix, x: Int, drawnRow: Int): Boolean {
        val y = if (matrix.height == 1L) 0 else drawnRow
        if (x < 0 || y < 0 || x >= matrix.width || y >= matrix.height) return false
        return matrix.bits[y * matrix.width.toInt() + x]
    }

    data class Run(val row: Int, val x: Int, val length: Int)

    /** Horizontal runs of dark modules per drawn row. */
    fun runs(matrix: BitMatrix): List<Run> {
        val out = mutableListOf<Run>()
        val columns = matrix.width.toInt()
        for (y in 0 until rows(matrix)) {
            var x = 0
            while (x < columns) {
                if (!dark(matrix, x, y)) {
                    x++
                    continue
                }
                var end = x + 1
                while (end < columns && dark(matrix, end, y)) end++
                out += Run(y, x, end - x)
                x = end
            }
        }
        return out
    }

    /** A one-row code stretched to its drawn bar height (for the SVG writer). */
    fun stretched(matrix: BitMatrix): BitMatrix {
        if (matrix.height != 1L) return matrix
        val rows = rows(matrix)
        return bitMatrixNew(matrix.width, rows.toLong(), List(rows) { matrix.bits }.flatten())
    }
}

/** Light shape checks. They catch typos; they don't try to be a standard. */
object MakeFormValidation {
    fun trimmed(s: String) = s.trim()

    /** One "@", something before it, a dotted domain without spaces or edge dots. */
    fun isPlausibleEmail(raw: String): Boolean {
        val s = trimmed(raw)
        if (s.isEmpty() || QrText.length(s) > 254 || s.any { it.isWhitespace() }) return false
        val parts = s.split('@')
        if (parts.size != 2 || parts[0].isEmpty()) return false
        val domain = parts[1]
        return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.') && !domain.contains("..")
    }

    /**
     * Make's Phone and Text message numbers (SPEC §8, written as `TEL:+…`): an international number,
     * `+` or a leading `00` and the country code. A local number can't be made international
     * offline, so the person is asked for the country code. The core decides (and writes it).
     */
    fun isInternationalPhone(raw: String): Boolean = phoneNumberInternational(raw) != null

    /** A contact's phone stays lenient: digits plus `+ ( ) - . * # ,` and spaces, 3 to 20 digits. */
    fun isPlausiblePhone(raw: String): Boolean {
        val s = trimmed(raw)
        if (s.isEmpty() || !s.all { it in "0123456789+()-.*#, " }) return false
        return s.count { it in '0'..'9' } in 3..20
    }

    /** "12.5" or "12,5"; null when not a finite number. */
    fun number(raw: String): Double? = trimmed(raw).replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun normalizedNumber(raw: String) = trimmed(raw).replace(',', '.')
}

enum class MakeWiFiSecurity(val code: String, val title: String) {
    WPA("WPA", "WPA, WPA2 or WPA3"),
    WEP("WEP", "WEP"),
    NONE("nopass", "None (open network)");

    companion object {
        fun of(code: String?): MakeWiFiSecurity {
            val s = code.orEmpty().uppercase()
            return when {
                s.isEmpty() || s == "NOPASS" -> NONE
                s.contains("WEP") -> WEP
                else -> WPA
            }
        }
    }
}

enum class MakeFormField { LINK, TEXT, SSID, WIFI_PASSWORD, CONTACT_NAME, CONTACT_PHONE, CONTACT_EMAIL, EVENT_TITLE, EVENT_END, EMAIL_ADDRESS, SMS_NUMBER, PHONE, LATITUDE, LONGITUDE }

/** Why a form can't make a request yet. A blank required field is a prompt, not an error. */
sealed class MakeFormIssue(val field: MakeFormField) {
    class Required(field: MakeFormField) : MakeFormIssue(field)

    class BadEmail(field: MakeFormField) : MakeFormIssue(field)

    class BadPhone(field: MakeFormField) : MakeFormIssue(field)

    data object BadLatitude : MakeFormIssue(MakeFormField.LATITUDE)

    data object BadLongitude : MakeFormIssue(MakeFormField.LONGITUDE)

    data object EndBeforeStart : MakeFormIssue(MakeFormField.EVENT_END)

    val isBlank
        get() = this is Required

    val message: String
        get() =
            when (this) {
                is Required -> "Fill in this field to make a code."
                is BadEmail -> "This doesn’t look like an email address. Use the form name@example.com."
                is BadPhone ->
                    if (this.field == MakeFormField.CONTACT_PHONE) "Use digits, and + ( ) - . or spaces. At least 3 digits."
                    else "Start with + and the country code, then the number. Spaces, dashes and brackets are fine."
                BadLatitude -> "Latitude is a number from -90 to 90."
                BadLongitude -> "Longitude is a number from -180 to 180."
                EndBeforeStart -> "The event can’t end before it starts."
            }
}

sealed interface MakeOutcome {
    data class Request(val request: MakeRequest) : MakeOutcome

    data class Invalid(val issue: MakeFormIssue) : MakeOutcome
}

/**
 * Everything the nine forms hold, as typed. [result] turns it into a MakeRequest or says what is
 * missing; only the chosen kind's fields are looked at. Times are epoch milliseconds.
 */
data class MakeFormState(
    val link: String = "",
    val text: String = "",
    val ssid: String = "",
    val wifiSecurity: MakeWiFiSecurity = MakeWiFiSecurity.WPA,
    val wifiPassword: String = "",
    val wifiHidden: Boolean = false,
    /** Set by a prefill from an enterprise network, so its EAP details survive an untouched form. */
    val wifiPrefill: WiFiInfo? = null,
    val givenName: String = "",
    val familyName: String = "",
    val organization: String = "",
    val jobTitle: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val contactUrl: String = "",
    val contactAddress: String = "",
    val contactNote: String = "",
    val eventTitle: String = "",
    val eventAllDay: Boolean = false,
    val eventLocation: String = "",
    val eventDescription: String = "",
    val eventUrl: String = "",
    val eventStart: Long = nextHour(Instant.now()),
    val eventEnd: Long = nextHour(Instant.now()) + 3_600_000,
    val emailAddress: String = "",
    val emailSubject: String = "",
    val emailBody: String = "",
    val smsNumber: String = "",
    val smsBody: String = "",
    val phone: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val locationQuery: String = "",
) {
    fun result(kind: MakeFormKind): MakeOutcome {
        fun need(s: String, f: MakeFormField): MakeFormIssue? = if (s.isBlank()) MakeFormIssue.Required(f) else null
        fun nonEmpty(s: String): String? = s.trim().takeIf { it.isNotEmpty() }
        fun bad(issue: MakeFormIssue) = MakeOutcome.Invalid(issue)
        return when (kind) {
            MakeFormKind.LINK -> need(link, MakeFormField.LINK)?.let(::bad) ?: MakeOutcome.Request(MakeRequest.Link(link.trim()))
            MakeFormKind.TEXT -> need(text, MakeFormField.TEXT)?.let(::bad) ?: MakeOutcome.Request(MakeRequest.Text(text))
            MakeFormKind.WIFI -> {
                need(ssid, MakeFormField.SSID)?.let { return bad(it) }
                if (wifiSecurity != MakeWiFiSecurity.NONE && wifiPassword.isEmpty()) return bad(MakeFormIssue.Required(MakeFormField.WIFI_PASSWORD))
                var info = WiFiInfo(ssid, wifiSecurity.code, if (wifiSecurity == MakeWiFiSecurity.NONE) null else wifiPassword, wifiHidden)
                val p = wifiPrefill
                if (p != null && p.isEnterprise && MakeWiFiSecurity.of(p.security) == wifiSecurity)
                    info = info.copy(security = p.security, eapMethod = p.eapMethod, identity = p.identity)
                MakeOutcome.Request(MakeRequest.Wifi(info))
            }
            MakeFormKind.CONTACT -> {
                val given = nonEmpty(givenName)
                val family = nonEmpty(familyName)
                val org = nonEmpty(organization)
                if (given == null && family == null && org == null) return bad(MakeFormIssue.Required(MakeFormField.CONTACT_NAME))
                nonEmpty(contactPhone)?.let { if (!MakeFormValidation.isPlausiblePhone(it)) return bad(MakeFormIssue.BadPhone(MakeFormField.CONTACT_PHONE)) }
                nonEmpty(contactEmail)?.let { if (!MakeFormValidation.isPlausibleEmail(it)) return bad(MakeFormIssue.BadEmail(MakeFormField.CONTACT_EMAIL)) }
                val full = listOfNotNull(given, family).joinToString(" ")
                MakeOutcome.Request(
                    MakeRequest.Contact(
                        ContactInfo(
                            name = full.ifEmpty { null },
                            givenName = given,
                            familyName = family,
                            organization = org,
                            title = nonEmpty(jobTitle),
                            phones = listOfNotNull(nonEmpty(contactPhone)),
                            emails = listOfNotNull(nonEmpty(contactEmail)),
                            urls = listOfNotNull(nonEmpty(contactUrl)),
                            addresses = listOfNotNull(nonEmpty(contactAddress)),
                            note = nonEmpty(contactNote),
                        )
                    )
                )
            }
            MakeFormKind.EVENT -> {
                need(eventTitle, MakeFormField.EVENT_TITLE)?.let { return bad(it) }
                if (eventEnd < eventStart) return bad(MakeFormIssue.EndBeforeStart)
                MakeOutcome.Request(MakeRequest.Event(eventInfo()))
            }
            MakeFormKind.EMAIL -> {
                need(emailAddress, MakeFormField.EMAIL_ADDRESS)?.let { return bad(it) }
                if (!MakeFormValidation.isPlausibleEmail(emailAddress)) return bad(MakeFormIssue.BadEmail(MakeFormField.EMAIL_ADDRESS))
                MakeOutcome.Request(MakeRequest.Email(EmailInfo(emailAddress.trim(), nonEmpty(emailSubject), nonEmpty(emailBody))))
            }
            MakeFormKind.SMS -> {
                need(smsNumber, MakeFormField.SMS_NUMBER)?.let { return bad(it) }
                if (!MakeFormValidation.isInternationalPhone(smsNumber)) return bad(MakeFormIssue.BadPhone(MakeFormField.SMS_NUMBER))
                MakeOutcome.Request(MakeRequest.Sms(SmsInfo(smsNumber.trim(), nonEmpty(smsBody))))
            }
            MakeFormKind.PHONE -> {
                need(phone, MakeFormField.PHONE)?.let { return bad(it) }
                if (!MakeFormValidation.isInternationalPhone(phone)) return bad(MakeFormIssue.BadPhone(MakeFormField.PHONE))
                MakeOutcome.Request(MakeRequest.Phone(phone.trim()))
            }
            MakeFormKind.LOCATION -> {
                need(latitude, MakeFormField.LATITUDE)?.let { return bad(it) }
                val lat = MakeFormValidation.number(latitude)
                if (lat == null || lat !in -90.0..90.0) return bad(MakeFormIssue.BadLatitude)
                need(longitude, MakeFormField.LONGITUDE)?.let { return bad(it) }
                val lon = MakeFormValidation.number(longitude)
                if (lon == null || lon !in -180.0..180.0) return bad(MakeFormIssue.BadLongitude)
                MakeOutcome.Request(MakeRequest.Geo(GeoInfo(MakeFormValidation.normalizedNumber(latitude), MakeFormValidation.normalizedNumber(longitude), null, nonEmpty(locationQuery))))
            }
        }
    }

    /**
     * Timed events are absolute instants. All-day events carry the local calendar date read as
     * GMT and an exclusive end date (iCalendar's rule), as EventInfo documents.
     */
    private fun eventInfo(zone: ZoneId = ZoneId.systemDefault()): EventInfo {
        fun nonEmpty(s: String): String? = s.trim().takeIf { it.isNotEmpty() }
        var start = Instant.ofEpochMilli(eventStart)
        var end = Instant.ofEpochMilli(eventEnd)
        if (eventAllDay) {
            start = wallAsGmt(start, zone)
            end = wallAsGmt(end, zone).plusSeconds(86_400)
        }
        return EventInfo(
            summary = nonEmpty(eventTitle),
            start = start,
            end = end,
            isAllDay = eventAllDay,
            location = nonEmpty(eventLocation),
            description = nonEmpty(eventDescription),
            url = nonEmpty(eventUrl),
        )
    }

    companion object {
        /** The event starts at the next full hour and lasts an hour. */
        fun nextHour(now: Instant): Long {
            val hour = 3_600_000L
            val ms = now.toEpochMilli()
            return ((ms + hour - 1) / hour) * hour
        }

        /** Local date and time fields read as the same fields in GMT, to the minute. */
        fun wallAsGmt(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
            LocalDateTime.ofInstant(instant, zone).truncatedTo(ChronoUnit.MINUTES).toInstant(ZoneOffset.UTC)

        fun localFromGmtWall(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
            LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).atZone(zone).toInstant()

        /** A form holding what [request] says (a result's "Make a code", the share target). */
        fun prefill(request: MakeRequest, now: Instant = Instant.now()): MakeFormState {
            val base = MakeFormState(eventStart = nextHour(now), eventEnd = nextHour(now) + 3_600_000)
            return when (request) {
                is MakeRequest.Link -> base.copy(link = request.text)
                is MakeRequest.Text -> base.copy(text = request.text)
                is MakeRequest.Wifi -> {
                    val w = request.info
                    base.copy(ssid = w.ssid, wifiSecurity = MakeWiFiSecurity.of(w.security), wifiPassword = w.password.orEmpty(), wifiHidden = w.isHidden, wifiPrefill = w.takeIf { it.isEnterprise })
                }
                is MakeRequest.Contact -> {
                    val c = request.info
                    var given = c.givenName.orEmpty()
                    val family = c.familyName.orEmpty()
                    if (given.isEmpty() && family.isEmpty()) c.name?.let { given = it }
                    base.copy(
                        givenName = given,
                        familyName = family,
                        organization = c.organization.orEmpty(),
                        jobTitle = c.title.orEmpty(),
                        contactPhone = c.phones.firstOrNull().orEmpty(),
                        contactEmail = c.emails.firstOrNull().orEmpty(),
                        contactUrl = c.urls.firstOrNull().orEmpty(),
                        contactAddress = c.addresses.firstOrNull().orEmpty(),
                        contactNote = c.note.orEmpty(),
                    )
                }
                is MakeRequest.Event -> {
                    val e = request.info
                    val wall = e.isAllDay || e.isFloating
                    var start = base.eventStart
                    e.start?.let { start = (if (wall) localFromGmtWall(it) else it).toEpochMilli() }
                    var end =
                        e.end?.let { raw ->
                            // All-day ends are exclusive; show the last day.
                            val shown = if (e.isAllDay) raw.minusSeconds(86_400) else raw
                            (if (wall) localFromGmtWall(shown) else shown).toEpochMilli()
                        } ?: (start + 3_600_000)
                    if (end < start) end = start
                    base.copy(
                        eventTitle = e.summary.orEmpty(),
                        eventAllDay = e.isAllDay,
                        eventStart = start,
                        eventEnd = end,
                        eventLocation = e.location.orEmpty(),
                        eventDescription = e.description.orEmpty(),
                        eventUrl = e.url.orEmpty(),
                    )
                }
                is MakeRequest.Email -> base.copy(emailAddress = request.info.address, emailSubject = request.info.subject.orEmpty(), emailBody = request.info.body.orEmpty())
                is MakeRequest.Sms -> base.copy(smsNumber = request.info.number, smsBody = request.info.body.orEmpty())
                is MakeRequest.Phone -> base.copy(phone = request.number)
                is MakeRequest.Geo -> base.copy(latitude = request.info.latitude, longitude = request.info.longitude, locationQuery = request.info.query.orEmpty())
            }
        }
    }
}
