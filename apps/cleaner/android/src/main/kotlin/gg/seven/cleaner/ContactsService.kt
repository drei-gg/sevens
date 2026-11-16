package gg.seven.cleaner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as Kinds
import android.util.Base64
import androidx.core.content.ContextCompat
import gg.seven.cleaner.core.ContactField
import gg.seven.cleaner.core.ContactRecord
import gg.seven.cleaner.core.contactDisplayName
import gg.seven.cleaner.core.contactPackageEncode
import gg.seven.cleaner.core.contactPackageVerify
import gg.seven.cleaner.core.emailKey
import gg.seven.cleaner.core.newContactField
import gg.seven.cleaner.core.newContactPackage
import gg.seven.cleaner.core.newContactRecord
import gg.seven.cleaner.core.sortContactFields
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ContactSnapshot(
    val records: List<ContactRecord>,
    /** Each raw contact's verified archive bytes, by record id. */
    val archives: Map<String, ByteArray>,
    val limited: Boolean,
)

/**
 * Contacts through the Contacts Provider (iOS ContactsService). Each record is one raw contact:
 * one account's own entry, as iOS reads each container's contacts without unifying them, so a
 * person who appears in two accounts appears twice. Nothing here writes to the provider.
 *
 * The archive of a raw contact is its account and every data row, verbatim (blobs as base64), in
 * a small JSON document, checked by reading it back: the Android counterpart of iOS's
 * `NSKeyedArchiver` of a `CNContact`. Notes and group membership are left out, as the package
 * says; iOS can't read notes without an extra entitlement, and the two apps keep one contract.
 */
class ContactsService(private val context: Context) {
    private val resolver = context.contentResolver
    private val normalizer by lazy { PhoneNormalizer() }

    suspend fun scan(region: String?): ContactSnapshot =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                throw blocked("Contacts access was not granted. You can change it in Settings.")
            val accounts = mutableMapOf<Long, Pair<String?, String?>>()
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME),
                "${ContactsContract.RawContacts.DELETED} = 0",
                null,
                null,
            )?.use { c -> while (c.moveToNext()) accounts[c.getLong(0)] = c.getString(1) to c.getString(2) }
                ?: throw blocked("Contacts could not be read. No contacts were changed.")
            val rows = mutableMapOf<Long, MutableList<JSONObject>>()
            resolver.query(ContactsContract.Data.CONTENT_URI, DATA_COLUMNS, null, null, "${ContactsContract.Data._ID} ASC")?.use { c ->
                while (c.moveToNext()) {
                    coroutineContext.ensureActive()
                    val raw = c.getLong(0)
                    if (raw !in accounts) continue
                    val row = row(c) ?: continue
                    rows.getOrPut(raw) { mutableListOf() } += row
                }
            } ?: throw blocked("Contacts could not be read. No contacts were changed.")
            val records = mutableListOf<ContactRecord>()
            val archives = mutableMapOf<String, ByteArray>()
            for ((raw, account) in accounts) {
                coroutineContext.ensureActive()
                val data = rows[raw].orEmpty()
                val container = listOfNotNull(account.first, account.second).joinToString(" · ").ifEmpty { "This phone" }
                records += record(raw.toString(), container, data, region)
                // No silent omission: one archive that fails its check fails the whole export.
                archives[raw.toString()] = verified(archive(account, data))
            }
            if (records.size != archives.size) throw blocked("Some contacts could not be archived and verified. No contacts were changed.")
            ContactSnapshot(records, archives, limited = false)
        }

    /** One data row as JSON, or null for what the package excludes (notes, group membership). */
    private fun row(c: Cursor): JSONObject? {
        val mime = c.getString(1) ?: return null
        if (mime == Kinds.Note.CONTENT_ITEM_TYPE || mime == Kinds.GroupMembership.CONTENT_ITEM_TYPE) return null
        val row = JSONObject()
        for (i in 1 until c.columnCount) {
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> Unit
                Cursor.FIELD_TYPE_BLOB -> row.put("$name.base64", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(i))
                else -> row.put(name, c.getString(i))
            }
        }
        return row
    }

    private fun archive(account: Pair<String?, String?>, data: List<JSONObject>): ByteArray =
        JSONObject()
            .put("format", "android.rawcontact.1")
            .put("accountType", account.first ?: JSONObject.NULL)
            .put("accountName", account.second ?: JSONObject.NULL)
            .put("data", JSONArray(data))
            .toString()
            .toByteArray()

    /** The archive read back must hold the same document (iOS round-trip check). */
    private fun verified(bytes: ByteArray): ByteArray {
        val text = String(bytes)
        val back = runCatching { JSONObject(text) }.getOrNull()
        if (back == null || back.toString() != text) throw blocked("Contact archive failed its round-trip check.")
        return bytes
    }

    private fun record(id: String, container: String, data: List<JSONObject>, region: String?): ContactRecord {
        val res = context.resources
        val fields = mutableListOf<ContactField>()
        val phones = mutableSetOf<String>()
        val emails = mutableSetOf<String>()
        var given = ""
        var middle = ""
        var family = ""
        var organization = ""
        fun add(key: String, value: String?, label: String = "") {
            if (!value.isNullOrEmpty()) fields += newContactField(key, value, label)
        }
        fun JSONObject.text(column: String) = optString(column).takeIf { has(column) && it.isNotEmpty() }
        fun JSONObject.int(column: String) = if (has(column)) optInt(column) else 0
        for (row in data) {
            val d1 = row.text("data1")
            when (row.optString("mimetype")) {
                Kinds.StructuredName.CONTENT_ITEM_TYPE -> {
                    given = row.text(Kinds.StructuredName.GIVEN_NAME).orEmpty()
                    middle = row.text(Kinds.StructuredName.MIDDLE_NAME).orEmpty()
                    family = row.text(Kinds.StructuredName.FAMILY_NAME).orEmpty()
                    add("prefix", row.text(Kinds.StructuredName.PREFIX))
                    add("givenName", given)
                    add("middleName", middle)
                    add("familyName", family)
                    add("suffix", row.text(Kinds.StructuredName.SUFFIX))
                    add("phoneticGiven", row.text(Kinds.StructuredName.PHONETIC_GIVEN_NAME))
                    add("phoneticMiddle", row.text(Kinds.StructuredName.PHONETIC_MIDDLE_NAME))
                    add("phoneticFamily", row.text(Kinds.StructuredName.PHONETIC_FAMILY_NAME))
                }
                Kinds.Nickname.CONTENT_ITEM_TYPE -> add("nickname", d1)
                Kinds.Organization.CONTENT_ITEM_TYPE -> {
                    if (organization.isEmpty()) organization = row.text(Kinds.Organization.COMPANY).orEmpty()
                    add("organization", row.text(Kinds.Organization.COMPANY))
                    add("department", row.text(Kinds.Organization.DEPARTMENT))
                    add("jobTitle", row.text(Kinds.Organization.TITLE))
                    add("phoneticOrganization", row.text(Kinds.Organization.PHONETIC_NAME))
                }
                Kinds.Phone.CONTENT_ITEM_TYPE -> {
                    add("phone", d1, Kinds.Phone.getTypeLabel(res, row.int("data2"), row.text("data3")).toString())
                    d1?.let { normalizer.normalized(it, region) }?.let(phones::add)
                }
                Kinds.Email.CONTENT_ITEM_TYPE -> {
                    add("email", d1, Kinds.Email.getTypeLabel(res, row.int("data2"), row.text("data3")).toString())
                    d1?.let(::emailKey)?.let(emails::add)
                }
                Kinds.Website.CONTENT_ITEM_TYPE -> add("url", d1, row.text("data3").orEmpty())
                Kinds.StructuredPostal.CONTENT_ITEM_TYPE ->
                    add("address", d1, Kinds.StructuredPostal.getTypeLabel(res, row.int("data2"), row.text("data3")).toString())
                Kinds.Relation.CONTENT_ITEM_TYPE ->
                    add("relation", d1, Kinds.Relation.getTypeLabel(res, row.int("data2"), row.text("data3")).toString())
                Kinds.Im.CONTENT_ITEM_TYPE -> {
                    val protocol = Kinds.Im.getProtocolLabel(res, row.int("data5"), row.text("data6"))
                    add("instantMessage", d1?.let { "$protocol: $it" }, Kinds.Im.getTypeLabel(res, row.int("data2"), row.text("data3")).toString())
                }
                Kinds.SipAddress.CONTENT_ITEM_TYPE -> add("instantMessage", d1?.let { "SIP: $it" })
                Kinds.Event.CONTENT_ITEM_TYPE ->
                    if (row.int("data2") == Kinds.Event.TYPE_BIRTHDAY) add("birthday", d1)
                    else add("date", d1, eventLabel(row.int("data2"), row.text("data3")))
                Kinds.Photo.CONTENT_ITEM_TYPE ->
                    row.text("data15.base64")?.let { add("photo", "${Base64.decode(it, Base64.NO_WRAP).size} bytes retained in archive") }
            }
        }
        return newContactRecord(
            id = id,
            name = contactDisplayName(given, middle, family, organization),
            container = container,
            fields = sortContactFields(fields),
            phoneKeys = phones.toList(),
            emailKeys = emails.toList(),
        )
    }

    private fun eventLabel(type: Int, custom: String?): String =
        when (type) {
            Kinds.Event.TYPE_ANNIVERSARY -> "Anniversary"
            Kinds.Event.TYPE_CUSTOM -> custom.orEmpty()
            else -> "Other"
        }

    /** This package is explicitly a partial archive, not a complete address-book backup. */
    suspend fun export(snapshot: ContactSnapshot, file: File) =
        withContext(Dispatchers.IO) {
            val pack = newContactPackage(snapshot.records, snapshot.archives)
            file.parentFile?.mkdirs()
            val atomic = androidx.core.util.AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(contactPackageEncode(pack))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            contactPackageVerify(pack, file.readBytes().direct())
        }

    private companion object {
        val DATA_COLUMNS =
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.IS_SUPER_PRIMARY) +
                (1..15).map { "data$it" }
    }
}
