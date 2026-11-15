package gg.seven.fast.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import gg.seven.fast.wire.WirePaths
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Wearable Data Layer, the Android counterpart of WatchConnectivity: the phone's snapshot is a
 * DataItem (latest state, delivered when connected, like an application context) and actions are
 * acknowledged requests to a reachable phone (MessageClient.sendRequest, like sendMessageData).
 */
class DataLayerLink(context: Context) : PhoneLink {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val data = Wearable.getDataClient(context)

    private suspend fun <T> Task<T>.result(seconds: Long): T = withContext(Dispatchers.IO) { Tasks.await(this@result, seconds, TimeUnit.SECONDS) }

    private suspend fun phone() =
        capabilities
            .getCapability(WirePaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .result(10)
            .nodes
            .minByOrNull { if (it.isNearby) 0 else 1 }

    override suspend fun reachable() = phone() != null

    override suspend fun request(bytes: ByteArray): ByteArray {
        val node = phone() ?: throw IOException("Phone not reachable")
        return messages.sendRequest(node.id, WirePaths.REQUEST, bytes).result(30)
    }

    /** The snapshot already delivered while the app was closed. */
    suspend fun stored(): List<ByteArray> {
        val buffer = data.getDataItems(Uri.Builder().scheme("wear").path(WirePaths.SNAPSHOT).build()).result(10)
        return try {
            buffer.mapNotNull { it.data }
        } finally {
            buffer.release()
        }
    }

    fun watchReachability(context: Context, onChange: () -> Unit): () -> Unit {
        val listener = CapabilityClient.OnCapabilityChangedListener { onChange() }
        val client = Wearable.getCapabilityClient(context)
        client.addListener(listener, WirePaths.PHONE_CAPABILITY)
        return { client.removeListener(listener) }
    }
}

/** Receives the phone's snapshot even while the app is closed, so the tile, complication and ongoing activity stay current. */
class SnapshotListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WirePaths.SNAPSHOT)
                event.dataItem.data?.let(watchModel::receive)
        }
    }
}
