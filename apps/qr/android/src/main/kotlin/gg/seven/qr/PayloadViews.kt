package gg.seven.qr

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import gg.seven.kit.Space
import gg.seven.qr.core.AppLinkInfo
import gg.seven.qr.core.ContactInfo
import gg.seven.qr.core.CryptoInfo
import gg.seven.qr.core.EmailInfo
import gg.seven.qr.core.EventInfo
import gg.seven.qr.core.GeoInfo
import gg.seven.qr.core.LinkInfo
import gg.seven.qr.core.Payload
import gg.seven.qr.core.PaymentChecksum
import gg.seven.qr.core.PaymentFormat
import gg.seven.qr.core.PaymentInfo
import gg.seven.qr.core.PaymentParty
import gg.seven.qr.core.PhoneInfo
import gg.seven.qr.core.SmsInfo
import gg.seven.qr.core.TextInfo
import gg.seven.qr.core.WiFiInfo
import gg.seven.qr.core.cryptoAddressGroups

/* The payload cards of a result (SPEC §3), one per payload type, as iOS ResultPayloadView picks them. */

@Composable
fun PayloadView(payload: Payload, wifiPlan: WiFiJoinPlan) {
    when (payload) {
        is Payload.Link -> LinkView(payload.info)
        is Payload.AppLink -> AppLinkView(payload.info)
        is Payload.Payment -> PaymentView(payload.info)
        is Payload.Crypto -> CryptoView(payload.info)
        is Payload.Wifi -> WiFiView(payload.info, wifiPlan)
        is Payload.Contact -> ContactView(payload.info)
        is Payload.Event -> EventView(payload.info)
        is Payload.Phone -> PhoneView(payload.info)
        is Payload.Sms -> SmsView(payload.info)
        is Payload.Email -> EmailView(payload.info)
        is Payload.Geo -> GeoView(payload.info)
        is Payload.Text -> TextView(payload.info)
        is Payload.Binary -> BinaryView(payload.byteCount)
    }
}

@Composable
private fun LinkView(link: LinkInfo) {
    ResultCard {
        PayloadTitle(ResultLinkDisplay.headline(link))
        ResultLinkDisplay.fullHost(link)?.let { ResultField("Full address of the site", it) }
        ResultLinkDisplay.punycode(link)?.let { ResultField("Written in plain letters as", it, monospaced = true, muted = true) }
        link.userinfo?.takeIf { it.isNotEmpty() }?.let { ResultField("Text before the @ sign", it, muted = true) }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Caption("The whole link")
            SelectionContainer { Caption(link.original) }
        }
        ResultLinkDisplay.openedAs(link)?.let { ResultField("Opens as", it, muted = true) }
    }
}

@Composable
private fun AppLinkView(info: AppLinkInfo) {
    ResultCard {
        PayloadTitle("Opens an app")
        ResultField("Starts with", info.scheme + ":", monospaced = true)
        ResultField("The whole code", info.original, muted = true)
    }
}

/** Payment codes (SPEC §5): who gets paid, in large type, then the amount, and the rest. */
@Composable
private fun PaymentView(info: PaymentInfo) {
    val payee = info.payeeName?.takeIf { it.isNotEmpty() } ?: info.payeeAccount?.takeIf { it.isNotEmpty() } ?: "No name in this code"
    val party =
        when (info.party) {
            PaymentParty.PERSON -> "Person"
            PaymentParty.BUSINESS -> "Business"
            PaymentParty.UNKNOWN -> null
        }
    val amount = info.amount?.takeIf { it.isNotEmpty() }?.let { listOfNotNull(info.currency, it).joinToString(" ") } ?: "No amount in the code; you enter it"
    val kind = info.isDynamic?.let { if (it) "Dynamic code, made for one payment" else "Static code, the same every time" }
    val checksum =
        when (info.checksum) {
            PaymentChecksum.VALID -> "Checksum matches"
            PaymentChecksum.INVALID -> "Checksum doesn’t match. The code may be damaged or altered."
            PaymentChecksum.ABSENT -> if (info.format == PaymentFormat.EMV) "This code has no checksum" else null
        }
    val format =
        when (info.format) {
            PaymentFormat.EMV -> "EMVCo"
            PaymentFormat.UPI -> "UPI"
            PaymentFormat.EPC -> "SEPA (EPC)"
            PaymentFormat.SWISS -> "Swiss QR-bill"
        }
    val extras = buildList {
        fun add(label: String, value: String?) {
            if (!value.isNullOrEmpty()) add(label to value)
        }
        add("Account", info.payeeAccount)
        add("City", info.city)
        add("Country", info.country)
        add("Reference", info.reference)
        add("Note", info.note)
        add("Format", format)
        info.extra.keys.sorted().filter { it != "alternateName" }.forEach { add(it, info.extra[it]) }
    }
    ResultCard(title = "Who you would pay") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            PayloadTitle(payee)
            info.extra["alternateName"]?.takeIf { it.isNotEmpty() }?.let {
                SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge, color = QrColors.muted) }
            }
        }
        Text(party ?: "Check that this is who you meant to pay.", style = MaterialTheme.typography.titleMedium)
        ResultField("Amount", amount)
        info.network?.takeIf { it.isNotEmpty() }?.let { ResultField("Network", it) }
        kind?.let { Caption(it) }
        checksum?.let {
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                val invalid = info.checksum == PaymentChecksum.INVALID
                Icon(if (invalid) Icons.Rounded.Dangerous else Icons.Rounded.CheckCircleOutline, null, Modifier.size(18.dp), tint = if (invalid) QrColors.danger else QrColors.muted)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (extras.isNotEmpty()) MoreDetails(extras)
    }
}

/** "More details": a disclosure of the remaining fields. */
@Composable
private fun MoreDetails(rows: List<Pair<String, String>>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("More details")
            Spacer(Modifier.width(Space.xs))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (open)
            Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                rows.forEach { (label, value) -> ResultField(label, value) }
            }
    }
}

/** Crypto addresses: the whole address in groups of four, to compare against another copy. */
@Composable
private fun CryptoView(info: CryptoInfo) {
    val groups = remember(info) { cryptoAddressGroups(info) }
    ResultCard {
        PayloadTitle(info.network)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Caption(if (info.isLightningInvoice) "Invoice" else "Address")
            SelectionContainer {
                Text(
                    groups.joinToString(" "),
                    Modifier.semantics { contentDescription = groups.joinToString(", ") { it.toCharArray().joinToString(" ") } },
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        info.amount?.takeIf { it.isNotEmpty() }?.let { ResultField("Amount", it) }
        info.label?.takeIf { it.isNotEmpty() }?.let { ResultField("Label", it) }
        info.message?.takeIf { it.isNotEmpty() }?.let { ResultField("Message", it) }
        info.chainId?.takeIf { it.isNotEmpty() }?.let { ResultField("Chain", it, muted = true) }
    }
}

/** Wi-Fi: name, security, whether a password is present. The password shows only on a tap. */
@Composable
private fun WiFiView(info: WiFiInfo, plan: WiFiJoinPlan) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val security =
        when {
            info.isOpen -> "Open, no password"
            info.isEnterprise -> "Company or school network (${info.security ?: info.eapMethod ?: "EAP"})"
            else -> info.security.orEmpty()
        }
    ResultCard {
        PayloadTitle(info.ssid.ifEmpty { "No network name" })
        ResultField("Security", security)
        if (info.isHidden) Caption("Hidden network")
        if (!info.isOpen) {
            val password = info.password
            if (ResultActionPlan.hasPassword(info) && password != null)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Caption("Password")
                    if (revealed) SelectionContainer { Text(password, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)) }
                    else Text("The code includes a password", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide password" else "Show password") }
                }
            else ResultField("Password", "The code doesn’t include one")
        }
        when (plan) {
            // Wi-Fi settings, the action below, opens the panel where these are joined.
            WiFiJoinPlan.SETTINGS_ONLY -> Caption("Join this one in Wi-Fi settings.")
            WiFiJoinPlan.COPY_AND_SETTINGS ->
                Caption(if (ResultActionPlan.hasPassword(info)) "Copy the password, then join in Wi-Fi settings." else "Join in Wi-Fi settings.")
            WiFiJoinPlan.CANNOT_JOIN -> Caption("The code has no password for this network. Join in Wi-Fi settings.")
            WiFiJoinPlan.JOIN -> Unit
        }
    }
}

/** A contact card. Links inside it are listed separately, each with its own check. */
@Composable
private fun ContactView(info: ContactInfo) {
    val name = info.name?.takeIf { it.isNotEmpty() } ?: listOfNotNull(info.givenName, info.familyName).joinToString(" ").ifEmpty { "No name" }
    val role = listOfNotNull(info.title, info.organization).filter { it.isNotEmpty() }.joinToString(", ")
    ResultCard {
        PayloadTitle(name)
        if (role.isNotEmpty()) Text(role, style = MaterialTheme.typography.bodyLarge, color = QrColors.muted)
        if (info.phones.isNotEmpty()) ResultField("Phone", info.phones.joinToString("\n"))
        if (info.emails.isNotEmpty()) ResultField("Email", info.emails.joinToString("\n"))
        if (info.urls.isNotEmpty()) ResultField("Websites", info.urls.joinToString("\n"))
        if (info.addresses.isNotEmpty()) ResultField("Address", info.addresses.joinToString("\n"))
        info.birthday?.takeIf { it.isNotEmpty() }?.let { ResultField("Birthday", it) }
        info.note?.takeIf { it.isNotEmpty() }?.let { ResultField("Note", it) }
    }
}

@Composable
private fun EventView(info: EventInfo) {
    ResultCard {
        PayloadTitle(info.summary?.takeIf { it.isNotEmpty() } ?: "Event with no title")
        val time = ResultEventFormat.timeText(info)
        if (time != null) {
            ResultField("When", time)
            ResultEventFormat.organiserText(info)?.let { Caption(it) }
        } else ResultField("When", "No time in the code")
        info.location?.takeIf { it.isNotEmpty() }?.let { ResultField("Where", it) }
        info.description?.takeIf { it.isNotEmpty() }?.let { ResultField("Details", it) }
        info.url?.takeIf { it.isNotEmpty() }?.let { ResultField("Link", it, muted = true) }
    }
}

@Composable
private fun PhoneView(info: PhoneInfo) {
    ResultCard { PayloadTitle(info.number) }
}

@Composable
private fun SmsView(info: SmsInfo) {
    ResultCard {
        ResultField("To", info.number)
        info.body?.takeIf { it.isNotEmpty() }?.let { ResultField("Message", it) }
    }
}

@Composable
private fun EmailView(info: EmailInfo) {
    ResultCard {
        ResultField("To", info.address)
        info.subject?.takeIf { it.isNotEmpty() }?.let { ResultField("Subject", it) }
        info.body?.takeIf { it.isNotEmpty() }?.let { ResultField("Message", it) }
    }
}

@Composable
private fun GeoView(info: GeoInfo) {
    ResultCard {
        ResultField("Coordinates", info.latitude + ", " + info.longitude, monospaced = true)
        info.altitude?.takeIf { it.isNotEmpty() }?.let { ResultField("Altitude", it) }
        info.query?.takeIf { it.isNotEmpty() }?.let { ResultField("Label", it) }
    }
}

/** Plain text: the raw text, selectable, with a note when it was cut. */
@Composable
private fun TextView(info: TextInfo) {
    ResultCard(title = "Text in the code") {
        SelectionContainer { Text(info.text.ifEmpty { "The code holds no text." }, style = MaterialTheme.typography.bodyLarge) }
        if (info.isTruncated) Caption("The code holds more text than this. Only the first part is shown and checked.")
    }
}

/** A code that isn't text. */
@Composable
private fun BinaryView(byteCount: Long) {
    ResultCard {
        PayloadTitle("Data, not text")
        Text("$byteCount bytes. This app can’t show what is inside.", style = MaterialTheme.typography.bodyLarge, color = QrColors.muted)
    }
}
