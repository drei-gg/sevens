package gg.seven.bloom.wear

import android.app.Application
import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import gg.seven.bloom.wire.WatchPaths
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

class DataLayerLink(private val context: Context) : PhoneLink {
    private suspend fun <T> Task<T>.result(seconds: Long = 10): T =
        withContext(Dispatchers.IO) { Tasks.await(this@result, seconds, TimeUnit.SECONDS) }

    override suspend fun request(bytes: ByteArray): ByteArray {
        val nodes =
            Wearable.getCapabilityClient(context)
                .getCapability(WatchPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .result()
                .nodes
        val node = nodes.minByOrNull { if (it.isNearby) 0 else 1 } ?: error("Phone unavailable")
        return Wearable.getMessageClient(context)
            .sendRequest(node.id, WatchPaths.REQUEST, bytes)
            .result(30)
    }

    override suspend fun stored(): List<ByteArray> {
        val buffer =
            Wearable.getDataClient(context)
                .getDataItems(Uri.Builder().scheme("wear").path(WatchPaths.SNAPSHOT).build())
                .result()
        return try {
            buffer.mapNotNull { it.data }
        } finally {
            buffer.release()
        }
    }

    fun listen(action: () -> Unit): () -> Unit {
        val listener = CapabilityClient.OnCapabilityChangedListener { action() }
        Wearable.getCapabilityClient(context).addListener(listener, WatchPaths.PHONE_CAPABILITY)
        return { Wearable.getCapabilityClient(context).removeListener(listener) }
    }
}

class BloomWearApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val link by lazy { DataLayerLink(this) }
    val model by lazy {
        WatchModel(
            object : WatchPersistence {
                private val prefs = getSharedPreferences("bloom.watch", MODE_PRIVATE)

                override fun read() = prefs.getString("state", null)

                // The KTX edit helper discards the commit result; durability must be checked before
                // publishing.
                @android.annotation.SuppressLint("UseKtx")
                override suspend fun save(value: String) =
                    withContext(Dispatchers.IO) {
                        check(prefs.edit().putString("state", value).commit()) {
                            "Could not save watch state"
                        }
                    }
            },
            link,
        )
    }
}

class SnapshotListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val app = application as BloomWearApp
        events.forEach { event ->
            if (
                event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path == WatchPaths.SNAPSHOT
            )
                event.dataItem.data?.let { bytes ->
                    app.scope.launch { runCatching { app.model.receive(bytes) } }
                }
        }
    }
}
