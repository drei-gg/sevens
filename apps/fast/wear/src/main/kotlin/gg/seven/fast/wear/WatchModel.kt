package gg.seven.fast.wear

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import gg.seven.fast.wire.WireAction
import gg.seven.fast.wire.WireCodec
import gg.seven.fast.wire.WireException
import gg.seven.fast.wire.WireFailure
import gg.seven.fast.wire.WireRequest
import gg.seven.fast.wire.WireSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FastWearApp : Application() {
    val model by lazy { WatchModel(this, DataLayerLink(this)) }
}

val Context.watchModel: WatchModel
    get() = (applicationContext as FastWearApp).model

/** Sends one encoded request to the phone and returns its encoded reply. */
interface PhoneLink {
    suspend fun reachable(): Boolean

    /** Throws when the phone can't be reached or doesn't answer. */
    suspend fun request(bytes: ByteArray): ByteArray
}

/**
 * The watch's state, ported from the Apple Watch's WatchModel: the phone's last snapshot, at most
 * one pending request (persisted, so a timed-out action can be retried verbatim after a relaunch;
 * the phone's receipts make the retry idempotent), and a plain-words status line.
 */
class WatchModel(private val context: Context, private val link: PhoneLink) {
    private val prefs = context.getSharedPreferences("fast.watch", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _snapshot = MutableStateFlow(stored(SNAPSHOT)?.let(::snapshotOf))
    private val _pending = MutableStateFlow(stored(PENDING)?.let { runCatching { WireCodec.decodeRequest(it) }.getOrNull() })
    private val _busy = MutableStateFlow(false)
    private val _status = MutableStateFlow<Int?>(null)
    private val _reachable = MutableStateFlow(false)

    val snapshot: StateFlow<WireSnapshot?> = _snapshot.asStateFlow()
    val pending: StateFlow<WireRequest?> = _pending.asStateFlow()
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val status: StateFlow<Int?> = _status.asStateFlow()
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private fun stored(key: String) = prefs.getString(key, null)?.encodeToByteArray()

    private fun snapshotOf(bytes: ByteArray): WireSnapshot? =
        runCatching { WireCodec.decodeReply(bytes) }.getOrNull()?.takeIf { it.version == WireCodec.VERSION }?.snapshot

    /** A snapshot from the Data Layer or inside a reply. Older snapshots never replace newer ones. */
    @Synchronized
    fun receive(bytes: ByteArray) {
        val incoming = snapshotOf(bytes) ?: return
        val old = _snapshot.value
        if (old != null && old.generatedAt > incoming.generatedAt) return
        _snapshot.value = incoming
        prefs.edit { putString(SNAPSHOT, bytes.decodeToString()) }
        Surfaces.update(context, incoming)
    }

    suspend fun updateReachability(): Boolean {
        val now = runCatching { link.reachable() }.getOrDefault(false)
        val gained = now && !_reachable.value
        _reachable.value = now
        return gained
    }

    suspend fun refresh() {
        if (_busy.value) return
        updateReachability()
        if (!_reachable.value) return
        try {
            receive(link.request(WireCodec.encode(WireRequest.create(WireAction.REFRESH))))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _status.value = R.string.status_reconnect
        }
    }

    suspend fun perform(action: WireAction) {
        val current = _snapshot.value
        perform(WireRequest.create(action, current?.revision, current?.activeId))
    }

    suspend fun perform(request: WireRequest) {
        if (_busy.value || _pending.value != null) return
        _pending.value = request
        prefs.edit { putString(PENDING, WireCodec.encode(request).decodeToString()) }
        retry()
    }

    suspend fun retry() {
        val request = _pending.value
        if (_busy.value || request == null) return
        _busy.value = true
        try {
            val data = link.request(WireCodec.encode(request))
            val reply = WireCodec.decodeReply(data)
            if (reply.version != WireCodec.VERSION || reply.requestId != request.id) throw WireException(WireFailure.MALFORMED)
            receive(data)
            _status.value = reply.failure?.let(::explanation) ?: R.string.status_saved
            // A valid reply resolves this attempt, including a definitive rejection.
            clearPending()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _status.value = R.string.status_not_confirmed
        } finally {
            _busy.value = false
        }
    }

    fun dismissPending() {
        clearPending()
        _status.value = R.string.status_check_phone
        scope.launch { refresh() }
    }

    private fun clearPending() {
        _pending.value = null
        prefs.edit { remove(PENDING) }
    }

    companion object {
        private const val SNAPSHOT = "fast.watch.snapshot"
        private const val PENDING = "fast.watch.pending"

        @StringRes
        fun explanation(failure: WireFailure): Int =
            when (failure) {
                WireFailure.LOCKED -> R.string.failure_locked
                WireFailure.STALE -> R.string.failure_stale
                WireFailure.EXPIRED -> R.string.failure_expired
                WireFailure.UNAVAILABLE -> R.string.failure_unavailable
                WireFailure.SAVE_FAILED -> R.string.failure_save
                WireFailure.UNSUPPORTED, WireFailure.MALFORMED -> R.string.failure_update
            }
    }
}
