package gg.seven.bloom

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import gg.seven.bloom.core.*
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun JSONObject.rows(key: String): List<JSONObject> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

internal val Throwable.sentence: String
    get() =
        (this as? gg.seven.bloom.core.Exception.Invalid)?.reason
            ?: message
            ?: "The change could not be saved."

/** One serialized owner; validation and durable encrypted replacement precede publication. */
class BloomRepository(context: Context, private val name: String = "records") {
    val file = File(context.noBackupFilesDir, "Bloom/$name.enc")
    private val atomic = AtomicFile(file)
    private val lock = Mutex()
    private val alias = "bloom.records.$name.v1"
    private var cache: String? = null

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        check(create) { "The records key is unavailable. Existing data has not been changed." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun readLocked(): String {
        cache?.let {
            return it
        }
        if (!file.exists() && !File(file.path + ".bak").exists())
            return bloomEmpty().also { cache = it }
        val bytes = atomic.openRead().use { stream -> stream.readBytesBounded(17_000_000) }
        check(bytes.size >= 29 && bytes[0] == 1.toByte()) {
            "Unsupported or damaged encrypted journal. Nothing was changed."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(false),
            GCMParameterSpec(128, bytes.copyOfRange(1, 13)),
        )
        cipher.updateAAD("bloom.records.v1".toByteArray())
        return bloomValidate(
                String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
            )
            .also { cache = it }
    }

    private fun writeLocked(json: String): String {
        val normalized = bloomValidate(json)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(!file.exists() && !File(file.path + ".bak").exists()))
        cipher.updateAAD("bloom.records.v1".toByteArray())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(normalized.toByteArray())
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) {
            "Could not create the journal folder."
        }
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            check(atomic.openRead().use { it.readBytesBounded(17_000_000) }.contentEquals(bytes)) {
                "The encrypted journal could not be committed. Please retry."
            }
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        cache = normalized
        return normalized
    }

    suspend fun read(): String = withContext(Dispatchers.IO) { lock.withLock { readLocked() } }

    suspend fun change(command: String, paid: Boolean): String =
        withContext(Dispatchers.IO) {
            lock.withLock {
                writeLocked(
                    bloomApply(
                        readLocked(),
                        command,
                        Instant.now().epochSecond,
                        ZoneId.systemDefault().id,
                        paid,
                    )
                )
            }
        }

    suspend fun merge(incoming: String): String =
        withContext(Dispatchers.IO) {
            lock.withLock { writeLocked(bloomMerge(readLocked(), incoming)) }
        }

    suspend fun erase(): String =
        withContext(Dispatchers.IO) {
            lock.withLock {
                readLocked()
                writeLocked(bloomEmpty())
            }
        }

    suspend fun dashboard(): String =
        withContext(Dispatchers.IO) {
            lock.withLock {
                bloomDashboard(readLocked(), Instant.now().epochSecond, ZoneId.systemDefault().id)
            }
        }

    suspend fun seal(password: String): ByteArray =
        withContext(Dispatchers.IO) { lock.withLock { bloomSeal(readLocked(), password) } }

    suspend fun inspect(bytes: ByteArray, password: String?): String =
        withContext(Dispatchers.IO) {
            check(bytes.size <= 17_000_000) { "The backup is too large." }
            if (password == null) bloomValidate(String(bytes, Charsets.UTF_8))
            else bloomOpen(bytes, password)
        }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        check(out.size() + n <= limit) { "The file is too large." }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal fun command(op: String, record: JSONObject?, revision: Long, id: String? = null): String =
    JSONObject()
        .put("op", op)
        .put("record", record ?: JSONObject.NULL)
        .put("id", id ?: JSONObject.NULL)
        .put("request_id", UUID.randomUUID().toString())
        .put("expected_revision", revision)
        .put("created_at", Instant.now().epochSecond)
        .toString()
