package gg.seven.bloom

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gg.seven.bloom.core.*
import gg.seven.flavor.sevenPurchases
import gg.seven.kit.SevenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BloomModel(app: Application) : AndroidViewModel(app) {
    val store =
        SevenStore(
            app,
            BuildConfig.PERSONAL_UNLOCK,
            sevenPurchases(app, BuildConfig.UNLOCK_PRODUCT_ID, BuildConfig.BILLING_PUBLIC_KEY),
            viewModelScope,
        )

    override fun onCleared() {
        store.close()
        super.onCleared()
    }

    val repository = BloomRepository(app)
    val archive = MutableStateFlow<String?>(null)
    val dashboard = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val preview = MutableStateFlow<String?>(null)

    init {
        run { reload() }
    }

    private suspend fun reload() {
        archive.value = repository.read()
        dashboard.value = repository.dashboard()
    }

    fun run(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: kotlin.Exception) {
                error.value = failure.sentence
            } finally {
                busy.value = false
            }
        }
    }

    fun save(op: String, record: JSONObject, revision: Long, done: () -> Unit) = run {
        repository.change(command(op, record, revision), store.paidFeaturesAvailable)
        reload()
        done()
    }

    fun delete(op: String, id: String) = run {
        val revision = JSONObject(repository.read()).getLong("revision")
        repository.change(command(op, null, revision, id), store.paidFeaturesAvailable)
        reload()
    }

    fun refresh() {
        store.refresh()
        if (archive.value != null) run { reload() }
    }

    fun erase() = run {
        repository.erase()
        reload()
    }

    fun importFile(uri: Uri, password: String?) = run {
        val bytes =
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use {
                    it.readBytesBounded(17_000_000)
                }
            }
        preview.value = repository.inspect(bytes, password)
    }

    fun confirmImport() = run {
        preview.value?.let { repository.merge(it) }
        preview.value = null
        reload()
    }

    fun exportFile(uri: Uri, kind: String, password: String, contacts: Boolean, notes: Boolean) =
        run {
            val json = repository.read()
            val bytes =
                withContext(Dispatchers.Default) {
                    when (kind) {
                        "age" -> bloomSeal(json, password)
                        "csv" -> bloomCsv(json, contacts, notes).toByteArray()
                        else -> json.toByteArray()
                    }
                }
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(bytes)
                }
            }
        }
}
