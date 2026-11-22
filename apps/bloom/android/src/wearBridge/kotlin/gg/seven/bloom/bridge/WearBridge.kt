package gg.seven.bloom.bridge

import android.content.Context
import androidx.startup.Initializer
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import gg.seven.bloom.BloomApplication
import gg.seven.bloom.BloomGlanceWidget
import gg.seven.bloom.BloomReminders
import gg.seven.bloom.sentence
import gg.seven.bloom.wire.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/**
 * Google Play services authenticates the listener caller and pairs matching package/signature
 * identities.
 */
object WearBridge {
    private suspend fun snapshot(app: BloomApplication): WatchSnapshot =
        runCatching {
                WatchCodec.json.decodeFromString<WatchSnapshot>(app.repository.watchSnapshot())
            }
            .getOrElse { WatchSnapshot(generated = Instant.now().epochSecond, enabled = false) }

    suspend fun reply(app: BloomApplication, bytes: ByteArray): ByteArray {
        var id: String? = null
        var failure: String? = null
        try {
            val request = WatchCodec.decodeRequest(bytes)
            id = request.id
            // Decode into the bounded wire shape before handing the command to the shared core.
            app.repository.watchRequest(WatchCodec.encode(request).decodeToString())
            if (request.command != null) {
                runCatching { BloomReminders.reconcile(app, deliver = true) }
                BloomGlanceWidget.refresh(app)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failure = error.sentence(app)
        }
        return WatchCodec.encode(
            WatchReply(request_id = id, snapshot = snapshot(app), error = failure)
        )
    }

    fun start(app: BloomApplication) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            app.repository.changes.collect {
                val payload = WatchCodec.encode(WatchReply(snapshot = snapshot(app)))
                runCatching {
                    Tasks.await(
                        Wearable.getDataClient(app)
                            .putDataItem(
                                PutDataRequest.create(WatchPaths.SNAPSHOT)
                                    .setData(payload)
                                    .setUrgent()
                            ),
                        15,
                        TimeUnit.SECONDS,
                    )
                }
            }
        }
    }
}

class BloomWearListenerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? =
        if (path != WatchPaths.REQUEST) null
        else
            Tasks.forResult(
                runBlocking { WearBridge.reply(application as BloomApplication, request) }
            )
}

class WearBridgeInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        WearBridge.start(context.applicationContext as BloomApplication)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
