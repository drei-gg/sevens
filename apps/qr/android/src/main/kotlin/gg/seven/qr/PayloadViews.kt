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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
        ResultLinkDisplay.fullHost(link)?.let { ResultField(stringResource(R.string.qr_field_full_address), it) }
        ResultLinkDisplay.punycode(link)?.let { ResultField(stringResource(R.string.qr_field_plain_letters), it, monospaced = true, muted = true) }
        link.userinfo?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_field_before_at), it, muted = true) }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Caption(stringResource(R.string.qr_field_whole_link))
            SelectionContainer { Caption(link.original) }
        }
        ResultLinkDisplay.openedAs(link)?.let { ResultField(stringResource(R.string.qr_field_opens_as), it, muted = true) }
    }
}

@Composable
private fun AppLinkView(info: AppLinkInfo) {
    ResultCard {
        PayloadTitle(stringResource(R.string.qr_field_opens_app))
        ResultField(stringResource(R.string.qr_field_starts_with), info.scheme + ":", monospaced = true)
        ResultField(stringResource(R.string.qr_field_whole_code), info.original, muted = true)
    }
}

/**
 * Payment codes (SPEC §5): who gets paid, in large type, then the amount and the fields the spec
 * names, each with a human label (PaymentText); everything else under More details.
 */
@Composable
private fun PaymentView(info: PaymentInfo) {
    val payee = info.payeeName?.takeIf { it.isNotEmpty() } ?: info.payeeAccount?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.qr_payment_no_name)
    val party =
        when (info.party) {
            PaymentParty.PERSON -> stringResource(R.string.qr_payment_person)
            PaymentParty.BUSINESS -> stringResource(R.string.qr_payment_business)
            PaymentParty.UNKNOWN -> null
        }
    val res = LocalResources.current
    val accountLabel = PaymentText.accountLabel(res, info.format)
    ResultCard(title = stringResource(R.string.qr_payment_who)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            PayloadTitle(payee)
            info.extra["alternateName"]?.takeIf { it.isNotEmpty() }?.let {
                SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge, color = QrColors.muted) }
            }
        }
        Text(party ?: stringResource(R.string.qr_payment_check_match), style = MaterialTheme.typography.titleMedium)
        ResultField(stringResource(R.string.qr_payment_amount), PaymentText.amount(res, info))
        PaymentText.rows(res, info).forEach { row -> ResultField(row.label, row.value, monospaced = row.label == accountLabel) }
        PaymentText.kind(res, info)?.let { Caption(it) }
        PaymentText.checksum(res, info)?.let {
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                val invalid = info.checksum == PaymentChecksum.INVALID
                Icon(if (invalid) Icons.Rounded.Dangerous else Icons.Rounded.CheckCircleOutline, null, Modifier.size(18.dp), tint = if (invalid) QrColors.danger else QrColors.muted)
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        MoreDetails(PaymentText.moreDetails(res, info).map { it.label to it.value })
    }
}

/** stringResource(R.string.qr_more_details): a disclosure of the remaining fields. */
@Composable
private fun MoreDetails(rows: List<Pair<String, String>>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.qr_more_details))
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
            Caption(if (info.isLightningInvoice) stringResource(R.string.qr_crypto_invoice) else stringResource(R.string.qr_crypto_address))
            SelectionContainer {
                Text(
                    groups.joinToString(" "),
                    Modifier.semantics { contentDescription = groups.joinToString(", ") { it.toCharArray().joinToString(" ") } },
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        info.amount?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_payment_amount), it) }
        info.label?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_field_label), it) }
        info.message?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_field_message), it) }
        info.chainId?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_crypto_chain), it, muted = true) }
    }
}

/** Wi-Fi: name, security, whether a password is present. The password shows only on a tap. */
@Composable
private fun WiFiView(info: WiFiInfo, plan: WiFiJoinPlan) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val security =
        when {
            info.isOpen -> stringResource(R.string.qr_wifi_open)
            info.isEnterprise -> stringResource(R.string.qr_wifi_enterprise, info.security ?: info.eapMethod ?: "EAP")
            else -> info.security.orEmpty()
        }
    ResultCard {
        PayloadTitle(info.ssid.ifEmpty { stringResource(R.string.qr_wifi_no_name) })
        ResultField(stringResource(R.string.qr_wifi_security), security)
        if (info.isHidden) Caption(stringResource(R.string.qr_wifi_hidden))
        if (!info.isOpen) {
            val password = info.password
            if (ResultActionPlan.hasPassword(info) && password != null)
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Caption(stringResource(R.string.qr_wifi_password))
                    if (revealed) SelectionContainer { Text(password, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)) }
                    else Text(stringResource(R.string.qr_wifi_has_password), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { revealed = !revealed }) { Text(if (revealed) stringResource(R.string.qr_wifi_hide_password) else stringResource(R.string.qr_wifi_show_password)) }
                }
            else ResultField(stringResource(R.string.qr_wifi_password), stringResource(R.string.qr_wifi_no_password))
        }
        when (plan) {
            // Wi-Fi settings, the action below, opens the panel where these are joined.
            WiFiJoinPlan.SETTINGS_ONLY -> Caption(stringResource(R.string.qr_wifi_settings_only))
            WiFiJoinPlan.COPY_AND_SETTINGS ->
                Caption(if (ResultActionPlan.hasPassword(info)) stringResource(R.string.qr_wifi_copy_then_settings) else stringResource(R.string.qr_wifi_join_in_settings))
            WiFiJoinPlan.CANNOT_JOIN -> Caption(stringResource(R.string.qr_wifi_cannot_join))
            WiFiJoinPlan.JOIN -> Unit
        }
    }
}

/** A contact card. Links inside it are listed separately, each with its own check. */
@Composable
private fun ContactView(info: ContactInfo) {
    val name = info.name?.takeIf { it.isNotEmpty() } ?: listOfNotNull(info.givenName, info.familyName).joinToString(" ").ifEmpty { stringResource(R.string.qr_contact_no_name) }
    val role = listOfNotNull(info.title, info.organization).filter { it.isNotEmpty() }.joinToString(", ")
    ResultCard {
        PayloadTitle(name)
        if (role.isNotEmpty()) Text(role, style = MaterialTheme.typography.bodyLarge, color = QrColors.muted)
        if (info.phones.isNotEmpty()) ResultField(stringResource(R.string.qr_field_phone), info.phones.joinToString("\n"))
        if (info.emails.isNotEmpty()) ResultField(stringResource(R.string.qr_type_email), info.emails.joinToString("\n"))
        if (info.urls.isNotEmpty()) ResultField(stringResource(R.string.qr_contact_websites), info.urls.joinToString("\n"))
        if (info.addresses.isNotEmpty()) ResultField(stringResource(R.string.qr_contact_address), info.addresses.joinToString("\n"))
        info.birthday?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_contact_birthday), it) }
        info.note?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_payment_note), it) }
    }
}

@Composable
private fun EventView(info: EventInfo) {
    ResultCard {
        PayloadTitle(info.summary?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.qr_event_no_title))
        val time = ResultEventFormat.timeText(info)
        if (time != null) {
            ResultField(stringResource(R.string.qr_event_when), time)
            ResultEventFormat.organiserText(LocalResources.current, info)?.let { Caption(it) }
        } else ResultField(stringResource(R.string.qr_event_when), stringResource(R.string.qr_event_no_time))
        info.location?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_event_where), it) }
        info.description?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_event_details), it) }
        info.url?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_payment_key_url), it, muted = true) }
    }
}

@Composable
private fun PhoneView(info: PhoneInfo) {
    ResultCard { PayloadTitle(info.number) }
}

@Composable
private fun SmsView(info: SmsInfo) {
    ResultCard {
        ResultField(stringResource(R.string.qr_field_to), info.number)
        info.body?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_field_message), it) }
    }
}

@Composable
private fun EmailView(info: EmailInfo) {
    ResultCard {
        ResultField(stringResource(R.string.qr_field_to), info.address)
        info.subject?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_field_subject), it) }
        info.body?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_field_message), it) }
    }
}

@Composable
private fun GeoView(info: GeoInfo) {
    ResultCard {
        ResultField(stringResource(R.string.qr_geo_coordinates), info.latitude + ", " + info.longitude, monospaced = true)
        info.altitude?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_geo_altitude), it) }
        info.query?.takeIf { it.isNotEmpty() }?.let { ResultField(stringResource(R.string.qr_field_label), it) }
    }
}

/** Plain text: the raw text, selectable, with a note when it was cut. */
@Composable
private fun TextView(info: TextInfo) {
    ResultCard(title = stringResource(R.string.qr_text_title)) {
        SelectionContainer { Text(info.text.ifEmpty { stringResource(R.string.qr_text_empty) }, style = MaterialTheme.typography.bodyLarge) }
        if (info.isTruncated) Caption(stringResource(R.string.qr_text_truncated))
    }
}

/** A code that isn't text. */
@Composable
private fun BinaryView(byteCount: Long) {
    ResultCard {
        PayloadTitle(stringResource(R.string.qr_binary_title))
        Text(pluralStringResource(R.plurals.qr_binary_bytes, byteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), byteCount), style = MaterialTheme.typography.bodyLarge, color = QrColors.muted)
    }
}
