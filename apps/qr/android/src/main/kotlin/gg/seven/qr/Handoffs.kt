package gg.seven.qr

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import gg.seven.qr.core.ContactInfo
import gg.seven.qr.core.EmailInfo
import gg.seven.qr.core.EventInfo
import gg.seven.qr.core.GeoInfo
import gg.seven.qr.core.LinkInfo
import gg.seven.qr.core.SmsInfo
import gg.seven.qr.core.WiFiInfo
import java.time.ZoneId

/*
 * Every hand-off a result makes to another app (SPEC §3), the counterparts of iOS ResultActionURLs
 * and the system controllers. Each starts from a tap; scanned text is passed through Uri builders,
 * never concatenated into a URI. Nothing here touches the network: the other app does.
 */
object Handoffs {
    private fun start(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    /** What Open uses: the normalized link, http(s) only. */
    fun openUri(link: LinkInfo): Uri? {
        if (!ResultActionPlan.canOpen(link)) return null
        val uri = runCatching { link.normalized.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        return uri.takeIf { scheme == "http" || scheme == "https" }
    }

    /** In the in-app browser (Custom Tabs, the SFSafariViewController counterpart), or the browser app. */
    fun open(context: Context, uri: Uri, external: Boolean) {
        if (!external) {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
            val started = runCatching { tab.launchUrl(context, uri) }.isSuccess
            if (started) return
        }
        start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    fun share(context: Context, text: String, title: String? = null) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        title?.let { send.putExtra(Intent.EXTRA_TITLE, it) }
        start(context, Intent.createChooser(send, null))
    }

    /** A password is marked sensitive, so the system's clipboard preview doesn't show it. */
    fun copy(context: Context, text: String, sensitive: Boolean = false) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("QR Code Kit", text)
        // The framework constant is Android 13+; the key itself is what the system reads on 12L too.
        if (sensitive)
            clip.description.extras = PersistableBundle().apply { putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
    }

    /** The dialer with the number typed in: the person places the call (iOS confirms first). */
    fun call(context: Context, number: String): Boolean {
        val digits = dialable(number)
        if (digits.none { it.isDigit() }) return false
        return start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digits, null)))
    }

    fun mail(context: Context, info: EmailInfo): Boolean {
        val address = info.address.trim()
        if (address.isEmpty()) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null))
        info.subject?.takeIf { it.isNotEmpty() }?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        info.body?.takeIf { it.isNotEmpty() }?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        return start(context, intent)
    }

    /** The messaging app with the number and the body filled in. */
    fun message(context: Context, info: SmsInfo): Boolean {
        val digits = dialable(info.number)
        if (digits.isEmpty()) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", digits, null))
        info.body?.takeIf { it.isNotEmpty() }?.let { intent.putExtra("sms_body", it) }
        return start(context, intent)
    }

    private fun isCoordinate(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.all { it in "0123456789+-." } && t.toDoubleOrNull() != null
    }

    /** `geo:lat,lon?q=lat,lon(label)`: a pin in the maps app. */
    fun maps(context: Context, info: GeoInfo): Boolean {
        if (!isCoordinate(info.latitude) || !isCoordinate(info.longitude)) return false
        val lat = info.latitude.trim()
        val lon = info.longitude.trim()
        val label = info.query?.takeIf { it.isNotEmpty() }?.let { "($it)" }.orEmpty()
        // lat and lon are checked to be plain numbers above; the label is encoded.
        val uri = ("geo:$lat,$lon?q=" + Uri.encode("$lat,$lon$label")).toUri()
        return start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    /** "Open in…": the original payload, only when it is URI-shaped (UPI, crypto). */
    fun openPayment(context: Context, text: String?): Boolean {
        val uri = text?.let { runCatching { it.toUri() }.getOrNull() } ?: return false
        if (uri.scheme.isNullOrEmpty()) return false
        return start(context, Intent(Intent.ACTION_VIEW, uri))
    }

    /** The contacts app's new-contact screen, filled in (no contacts permission needed). */
    fun addContact(context: Context, info: ContactInfo): Boolean {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        val name = listOfNotNull(info.givenName, info.familyName).joinToString(" ").ifEmpty { info.name.orEmpty() }
        if (name.isNotEmpty()) intent.putExtra(ContactsContract.Intents.Insert.NAME, name)
        info.organization?.let { intent.putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        info.title?.let { intent.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
        val phoneKeys = listOf(ContactsContract.Intents.Insert.PHONE, ContactsContract.Intents.Insert.SECONDARY_PHONE, ContactsContract.Intents.Insert.TERTIARY_PHONE)
        info.phones.take(3).forEachIndexed { i, phone -> intent.putExtra(phoneKeys[i], phone) }
        val emailKeys = listOf(ContactsContract.Intents.Insert.EMAIL, ContactsContract.Intents.Insert.SECONDARY_EMAIL, ContactsContract.Intents.Insert.TERTIARY_EMAIL)
        info.emails.take(3).forEachIndexed { i, email -> intent.putExtra(emailKeys[i], email) }
        info.addresses.firstOrNull()?.let { intent.putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        info.note?.let { intent.putExtra(ContactsContract.Intents.Insert.NOTES, it) }
        val rows = ArrayList<ContentValues>()
        info.urls.forEach { url ->
            rows += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Website.URL, url)
                put(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_OTHER)
            }
        }
        ResultEventFormat.birthday(info.birthday)?.let { day ->
            rows += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Event.START_DATE, day.toString())
                put(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
            }
        }
        if (rows.isNotEmpty()) intent.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
        return start(context, intent)
    }

    fun eventIntent(info: EventInfo, zone: ZoneId = ZoneId.systemDefault()): Intent {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        intent.putExtra(CalendarContract.Events.TITLE, info.summary.orEmpty())
        info.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        val notes = listOfNotNull(info.description, info.url).joinToString("\n\n")
        if (notes.isNotEmpty()) intent.putExtra(CalendarContract.Events.DESCRIPTION, notes)
        ResultEventFormat.calendarDates(info, zone)?.let { dates ->
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dates.start.toEpochMilli())
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, dates.end.toEpochMilli())
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, dates.allDay)
        }
        return intent
    }

    /** The calendar app's new-event screen (no calendar permission needed). */
    fun addEvent(context: Context, info: EventInfo): Boolean = start(context, eventIntent(info))

    fun canAddEvent(context: Context): Boolean =
        Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).resolveActivity(context.packageManager) != null

    /**
     * Android 11+: the system sheet that asks "Save this network?" (NEHotspotConfiguration's
     * counterpart). Null when the network can't be suggested this way.
     */
    @RequiresApi(30)
    fun wifiIntent(info: WiFiInfo): Intent? {
        if (info.ssid.isEmpty() || info.ssid.toByteArray().size > 32 || info.isEnterprise) return null
        val builder = WifiNetworkSuggestion.Builder().setSsid(info.ssid).setIsHiddenSsid(info.isHidden)
        if (!info.isOpen) {
            val password = info.password?.takeIf { it.isNotEmpty() } ?: return null
            if (info.security?.uppercase() == "WEP") return null
            runCatching { builder.setWpa2Passphrase(password) }.getOrElse { return null }
        }
        val suggestion = runCatching { builder.build() }.getOrElse { return null }
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
    }

    fun wifiSettings(context: Context) = start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
}
