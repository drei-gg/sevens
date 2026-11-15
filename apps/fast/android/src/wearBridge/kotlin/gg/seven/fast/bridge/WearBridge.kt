package gg.seven.fast.bridge

// The phone's side of the Wear OS companion, the counterpart of FastUI's WatchService.swift. It is
// compiled into the personal and store flavors only (wear-bridge.gradle.kts): the Wearable Data
// Layer is Google Play services, a platform exception like Play Billing, and the generic flavor
// stays free of it. Rules, replay protection and receipts live in the Rust core (watchApply).

import android.content.Context
import androidx.startup.Initializer
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import gg.seven.fast.FastApplication
import gg.seven.fast.ReminderScheduler
import gg.seven.fast.core.CompanionAction
import gg.seven.fast.core.CompanionFailure
import gg.seven.fast.core.CompanionRequest
import gg.seven.fast.core.FastArchive
import gg.seven.fast.core.History
import gg.seven.fast.core.active
import gg.seven.fast.core.fastGoalDate
import gg.seven.fast.core.localDate
import gg.seven.fast.core.watchApply
import gg.seven.fast.wire.WireAction
import gg.seven.fast.wire.WireCodec
import gg.seven.fast.wire.WireException
import gg.seven.fast.wire.WireFailure
import gg.seven.fast.wire.WirePaths
import gg.seven.fast.wire.WireReply
import gg.seven.fast.wire.WireRequest
import gg.seven.fast.wire.WireSnapshot
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Core failure → wire failure. Anything the core didn't decide (a failed write) is saveFailed. */
internal fun wireFailure(error: Throwable): WireFailure =
    when (error) {
        is WireException -> error.failure
        is CompanionFailure.Unsupported -> WireFailure.UNSUPPORTED
        is CompanionFailure.Malformed -> WireFailure.MALFORMED
        is CompanionFailure.Expired -> WireFailure.EXPIRED
        is CompanionFailure.Stale -> WireFailure.STALE
        is CompanionFailure.Locked -> WireFailure.LOCKED
        is CompanionFailure.Unavailable -> WireFailure.UNAVAILABLE
        is CompanionFailure.SaveFailed -> WireFailure.SAVE_FAILED
        else -> WireFailure.SAVE_FAILED
    }

internal fun WireRequest.companion() =
    CompanionRequest(
        version,
        id,
        Instant.ofEpochMilli(sentAt),
        when (action) {
            WireAction.REFRESH -> CompanionAction.REFRESH
            WireAction.START -> CompanionAction.START
            WireAction.END -> CompanionAction.END
            WireAction.WATER -> CompanionAction.WATER
        },
        revision,
        activeId,
    )

/**
 * Decodes a request, guards readiness, applies it inside the store's transaction and replies with
 * a fresh snapshot or a failure, as WatchService.receiveWatch does on iOS.
 *
 * @param ready loads the archive if needed; false while it is missing or read-only.
 * @param apply applies a non-refresh request atomically, throwing the core's failure.
 * @param snapshot the current snapshot, or null while not ready.
 */
internal class WearRequestHandler(
    private val ready: suspend () -> Boolean,
    private val apply: suspend (CompanionRequest) -> Unit,
    private val snapshot: () -> WireSnapshot?,
) {
    suspend fun handle(bytes: ByteArray): ByteArray {
        var requestId: UUID? = null
        val reply =
            try {
                val request = WireCodec.decodeRequest(bytes)
                requestId = request.id
                if (request.version != WireCodec.VERSION) throw WireException(WireFailure.UNSUPPORTED)
                if (!ready()) throw WireException(WireFailure.UNAVAILABLE)
                if (request.action != WireAction.REFRESH) apply(request.companion())
                WireReply(requestId = requestId, snapshot = snapshot())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WireReply(requestId = requestId, snapshot = runCatching { snapshot() }.getOrNull(), failure = wireFailure(e))
            }
        return runCatching { WireCodec.encode(reply) }.getOrElse { WireCodec.encode(WireReply(requestId = requestId, failure = WireFailure.MALFORMED)) }
    }
}

internal object WearBridge {
    private fun canCreate(app: FastApplication) = app.access.canCreate(app.purchaseSource.state.value, System.currentTimeMillis())

    fun snapshot(archive: FastArchive, canCreateRecords: Boolean, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): WireSnapshot {
        val active = archive.active
        return WireSnapshot(
            revision = archive.companionRevision,
            generatedAt = now.toEpochMilli(),
            activeId = active?.id,
            start = active?.start?.toEpochMilli(),
            goal = active?.let(::fastGoalDate)?.toEpochMilli(),
            waterMilliliters = History.water(archive.metrics, now.localDate(zone), zone),
            canCreateRecords = canCreateRecords,
        )
    }

    private fun current(app: FastApplication): WireSnapshot? {
        val archive = app.repository.state.value ?: return null
        if (app.repository.failure.value != null) return null
        return snapshot(archive, canCreate(app))
    }

    fun handler(app: FastApplication) =
        WearRequestHandler(
            ready = {
                app.repository.load()
                app.repository.state.value != null && app.repository.failure.value == null
            },
            apply = { request ->
                val now = Instant.now()
                val permitted = canCreate(app)
                val saved = app.repository.update { watchApply(it, request, permitted, ZoneId.systemDefault().id, now) }
                // Records are saved; reminders and the timer surfaces follow, as after a phone edit.
                runCatching { ReminderScheduler.reconcile(app, saved) }
            },
            snapshot = { current(app) },
        )

    /**
     * Publishes the latest snapshot as a DataItem whenever a committed write changes the companion
     * revision or access changes. Waits until something (the app or a watch request) opens the
     * archive, so a reminder broadcast doesn't load records or billing.
     */
    fun startPublishing(app: FastApplication) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = Wearable.getDataClient(app)
        scope.launch {
            app.repository.state.filterNotNull().first()
            combine(app.repository.state, app.repository.failure, app.purchaseSource.state) { archive, failure, _ ->
                    if (archive == null || failure != null) null else archive.companionRevision to canCreate(app)
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect {
                    val snapshot = current(app) ?: return@collect
                    runCatching {
                        val request = PutDataRequest.create(WirePaths.SNAPSHOT).setData(WireCodec.encode(WireReply(snapshot = snapshot))).setUrgent()
                        Tasks.await(data.putDataItem(request))
                    } // No watch, or no Wear OS app on this phone: nothing to deliver.
                }
        }
    }
}

/** Answers watch requests; Google Play services starts it on demand. */
class FastWearListenerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WirePaths.REQUEST) return null
        val app = application as FastApplication
        // Called on a background thread. No timeout here: a late but definitive reply is better
        // than a guess, and the watch keeps the request pending until it hears back.
        return Tasks.forResult(runBlocking { WearBridge.handler(app).handle(request) })
    }
}

/** Starts snapshot publishing at process start without a hook in the app's own code. */
class WearBridgeInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        (context.applicationContext as? FastApplication)?.let(WearBridge::startPublishing)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
