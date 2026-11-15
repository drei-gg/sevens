package gg.seven.fast.wire

// The data that crosses between the Wear OS watch and the Android phone, mirroring the Swift
// FastWire protocol (apps/fast/Kit/Sources/FastWire/Wire.swift): the same JSON field names,
// actions, failures, version and 32,768-byte limit. Journal, weight and reflections never cross.
//
// One deliberate difference: dates are integer epoch milliseconds (UTC). Swift's JSONEncoder
// writes seconds since 2001-01-01. The two protocols never meet: an Apple Watch only talks to an
// iPhone, a Wear OS watch only to an Android phone.

import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** Data Layer names shared by both apps. */
object WirePaths {
    /** The phone's latest snapshot, a DataItem holding an encoded [WireReply]. */
    const val SNAPSHOT = "/fast/snapshot"
    /** Watch → phone requests sent with MessageClient.sendRequest; the reply is a [WireReply]. */
    const val REQUEST = "/fast/request"
    /** Declared by the phone's Play-services flavors (res/values/wear.xml). */
    const val PHONE_CAPABILITY = "fast_phone"
}

@Serializable
enum class WireAction {
    @SerialName("refresh") REFRESH,
    @SerialName("start") START,
    @SerialName("end") END,
    @SerialName("water") WATER,
}

@Serializable
enum class WireFailure {
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("malformed") MALFORMED,
    @SerialName("expired") EXPIRED,
    @SerialName("stale") STALE,
    @SerialName("locked") LOCKED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("saveFailed") SAVE_FAILED,
}

class WireException(val failure: WireFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

@Serializable
data class WireRequest(
    val version: Int,
    @Serializable(UuidSerializer::class) val id: UUID,
    /** Epoch milliseconds. */
    val sentAt: Long,
    val action: WireAction,
    @Serializable(UuidSerializer::class) val revision: UUID? = null,
    @SerialName("activeID") @Serializable(UuidSerializer::class) val activeId: UUID? = null,
) {
    companion object {
        fun create(
            action: WireAction,
            revision: UUID? = null,
            activeId: UUID? = null,
            now: Long = System.currentTimeMillis(),
            id: UUID = UUID.randomUUID(),
        ) = WireRequest(WireCodec.VERSION, id, now, action, revision, activeId)
    }
}

@Serializable
data class WireSnapshot(
    @Serializable(UuidSerializer::class) val revision: UUID,
    /** Epoch milliseconds. */
    val generatedAt: Long,
    @SerialName("activeID") @Serializable(UuidSerializer::class) val activeId: UUID? = null,
    /** Epoch milliseconds. */
    val start: Long? = null,
    /** Epoch milliseconds. */
    val goal: Long? = null,
    val waterMilliliters: Double,
    val canCreateRecords: Boolean,
)

@Serializable
data class WireReply(
    val version: Int = WireCodec.VERSION,
    @SerialName("requestID") @Serializable(UuidSerializer::class) val requestId: UUID? = null,
    val snapshot: WireSnapshot? = null,
    val failure: WireFailure? = null,
)

object WireCodec {
    const val VERSION = 1
    const val MAXIMUM_BYTES = 32_768

    // Like Swift's JSONDecoder: unknown keys are ignored, absent optionals are omitted.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(request: WireRequest): ByteArray = limit(json.encodeToString(WireRequest.serializer(), request))

    fun encode(reply: WireReply): ByteArray = limit(json.encodeToString(WireReply.serializer(), reply))

    /** Throws [WireException] with [WireFailure.MALFORMED] for oversized or unreadable input. */
    fun decodeRequest(bytes: ByteArray): WireRequest = decode(bytes) { json.decodeFromString(WireRequest.serializer(), it) }

    fun decodeReply(bytes: ByteArray): WireReply = decode(bytes) { json.decodeFromString(WireReply.serializer(), it) }

    private fun limit(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        if (bytes.size > MAXIMUM_BYTES) throw WireException(WireFailure.MALFORMED)
        return bytes
    }

    private fun <T> decode(bytes: ByteArray, body: (String) -> T): T {
        if (bytes.size > MAXIMUM_BYTES) throw WireException(WireFailure.MALFORMED)
        return try {
            body(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (e: IllegalArgumentException) {
            // SerializationException and invalid UTF-8 / UUIDs are all IllegalArgumentExceptions.
            throw WireException(WireFailure.MALFORMED, e)
        } catch (e: CharacterCodingException) {
            throw WireException(WireFailure.MALFORMED, e)
        }
    }
}

/** Canonical 36-character UUIDs, written upper-case like Foundation's UUID, read in either case. */
internal object UuidSerializer : KSerializer<UUID> {
    private val shape = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    override val descriptor = PrimitiveSerialDescriptor("gg.seven.fast.wire.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString().uppercase())

    override fun deserialize(decoder: Decoder): UUID {
        val text = decoder.decodeString()
        require(shape.matches(text)) { "Not a UUID" }
        return UUID.fromString(text)
    }
}
