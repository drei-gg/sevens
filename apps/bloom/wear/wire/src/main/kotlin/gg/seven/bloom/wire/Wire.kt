package gg.seven.bloom.wire

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object WatchPaths {
    const val SNAPSHOT = "/bloom/snapshot"
    const val REQUEST = "/bloom/request"
    const val PHONE_CAPABILITY = "bloom_phone"
}

@Serializable
data class WatchDose(
    val id: String,
    val plan_id: String,
    val day: String,
    val at: Long,
    val title: String,
    val phase: String,
    val status: String,
)

@Serializable
data class WatchSnapshot(
    val version: Int = 1,
    val generated: Long,
    val revision: Long? = null,
    val enabled: Boolean,
    val cycle_day: Int? = null,
    val doses: List<WatchDose> = emptyList(),
    val can_add: Boolean = false,
) {
    fun fresh(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        version == 1 &&
            generated >= 0 &&
            enabled &&
            now >= generated &&
            now - generated < 86_400 &&
            Instant.ofEpochSecond(now).atZone(zone).toLocalDate() ==
                Instant.ofEpochSecond(generated).atZone(zone).toLocalDate()

    fun replaces(previous: WatchSnapshot?, now: Long): Boolean {
        if (version != 1 || generated > now + 60 || generated < 0) return false
        if (previous == null || previous.generated > now + 60) return true
        if (generated != previous.generated) return generated > previous.generated
        if (!enabled) return true
        if (!previous.enabled) return false
        return revision == null || previous.revision == null || revision >= previous.revision
    }
}

@Serializable
data class DoseRecord(
    val id: String,
    val plan_id: String,
    val day: String,
    val scheduled: Long,
    val status: String,
    val recorded: Long,
    val snooze_until: Long? = null,
    val notes: String = "",
)

@Serializable
data class WatchCommand(
    val op: String = "dose",
    val record: DoseRecord,
    val request_id: String,
    val expected_revision: Long? = null,
    val created_at: Long,
)

@Serializable
data class WatchRequest(
    val version: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val command: WatchCommand? = null,
) {
    companion object {
        fun dose(dose: WatchDose, status: String, revision: Long?, now: Long): WatchRequest {
            require(status in listOf("taken", "skipped"))
            val id = UUID.randomUUID().toString()
            return WatchRequest(
                id = id,
                command =
                    WatchCommand(
                        record = DoseRecord(dose.id, dose.plan_id, dose.day, dose.at, status, now),
                        request_id = id,
                        expected_revision = revision,
                        created_at = now,
                    ),
            )
        }
    }
}

@Serializable
data class WatchReply(
    val version: Int = 1,
    val request_id: String? = null,
    val snapshot: WatchSnapshot? = null,
    val error: String? = null,
)

@Serializable
data class WatchStored(val snapshot: WatchSnapshot? = null, val pending: WatchRequest? = null)

object WatchCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun decodeReply(bytes: ByteArray): WatchReply {
        require(bytes.size <= 512_000)
        return json.decodeFromString<WatchReply>(bytes.decodeToString()).also {
            require(it.version == 1 && (it.snapshot == null || it.snapshot.version == 1))
        }
    }

    fun decodeRequest(bytes: ByteArray): WatchRequest {
        require(bytes.size <= 512_000)
        return json.decodeFromString<WatchRequest>(bytes.decodeToString()).also {
            require(it.version == 1)
            UUID.fromString(it.id)
        }
    }

    fun encode(value: WatchRequest) = json.encodeToString(value).encodeToByteArray()

    fun encode(value: WatchReply) = json.encodeToString(value).encodeToByteArray()

    fun encode(value: WatchStored) = json.encodeToString(value)

    fun stored(value: String): WatchStored {
        require(value.length <= 512_000)
        return json.decodeFromString<WatchStored>(value).also {
            require(it.snapshot == null || it.snapshot.version == 1)
            require(it.pending == null || it.pending.version == 1)
        }
    }
}
