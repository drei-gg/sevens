package gg.seven.bloom.wear

import gg.seven.bloom.wire.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface WatchPersistence {
    fun read(): String?

    suspend fun save(value: String)
}

interface PhoneLink {
    suspend fun request(bytes: ByteArray): ByteArray

    suspend fun stored(): List<ByteArray>
}

/**
 * One durable pending request. It is never presented as a saved dose before the phone acknowledges
 * it.
 */
class WatchModel(
    private val disk: WatchPersistence,
    private val link: PhoneLink,
    private val clock: () -> Long = { Instant.now().epochSecond },
) {
    private val cached =
        disk.read()?.let { runCatching { WatchCodec.stored(it) }.getOrNull() } ?: WatchStored()
    val state =
        MutableStateFlow(
            cached.copy(pending = cached.pending.takeUnless { cached.snapshot?.enabled == false })
        )
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val mutex = Mutex()

    private suspend fun persist(next: WatchStored) {
        disk.save(WatchCodec.encode(next))
        state.value = next
    }

    suspend fun receive(bytes: ByteArray) =
        mutex.withLock {
            val reply = WatchCodec.decodeReply(bytes)
            val previous = state.value
            val incoming = reply.snapshot?.takeIf { it.replaces(previous.snapshot, clock()) }
            val snapshot = incoming ?: previous.snapshot
            val acknowledged =
                reply.request_id != null &&
                    reply.request_id == previous.pending?.id &&
                    reply.error == null
            val pending = if (snapshot?.enabled == false || acknowledged) null else previous.pending
            persist(WatchStored(snapshot, pending))
            reply.error?.let { message.value = it }
        }

    suspend fun record(dose: WatchDose, status: String) {
        if (busy.value || state.value.pending != null) return
        val snapshot = state.value.snapshot ?: return
        if (!snapshot.fresh(clock()) || snapshot.doses.none { it == dose }) {
            message.value = "Refresh from your phone before recording."
            return
        }
        try {
            val queued =
                mutex.withLock {
                    val current = state.value.snapshot
                    if (
                        state.value.pending != null ||
                            busy.value ||
                            current == null ||
                            !current.fresh(clock()) ||
                            dose !in current.doses
                    )
                        false
                    else {
                        persist(
                            state.value.copy(
                                pending = WatchRequest.dose(dose, status, current.revision, clock())
                            )
                        )
                        true
                    }
                }
            if (!queued) return
            message.value = "Pending — not saved on phone yet."
            sync()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message.value = "Could not keep this action. Check your phone before trying again."
        }
    }

    suspend fun sync() {
        if (busy.value) return
        busy.value = true
        try {
            val stored =
                try {
                    link.stored()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    emptyList()
                }
            stored.forEach { bytes ->
                try {
                    receive(bytes)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    /* An obsolete data item must not block a pending acknowledgement. */
                }
            }
            val request = state.value.pending ?: WatchRequest()
            val bytes = link.request(WatchCodec.encode(request))
            val reply = WatchCodec.decodeReply(bytes)
            check(reply.request_id == request.id) { "Unmatched reply" }
            receive(bytes)
            message.value = reply.error ?: "Up to date with phone."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message.value =
                if (state.value.pending == null) "Open both apps to connect and refresh."
                else "Not confirmed. Your action stays pending; open both apps and retry."
        } finally {
            busy.value = false
        }
    }

    suspend fun discardPending() {
        if (busy.value) return
        try {
            mutex.withLock { persist(state.value.copy(pending = null)) }
            message.value =
                "Pending action cleared here. Check phone history in case the reply was interrupted."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message.value = "Could not clear the pending action. It remains queued."
        }
    }
}
