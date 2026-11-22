package gg.seven.cleaner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.scanCheckpoint
import gg.seven.cleaner.core.scanRestore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Derived measurements only. Review decisions stay in ReviewStore and never enter this database.
 */
class MeasurementCache(
    context: Context,
    private val file: File = File(context.noBackupFilesDir, "Cleaner/measurements.sqlite"),
) {
    private val lock = Mutex()
    private val helper =
        object : SQLiteOpenHelper(context, file.absolutePath, null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE measurements (asset_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)"
                    )
                }

                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    throw android.database.sqlite.SQLiteException(
                        "Saved scan progress needs a newer app. Clear scan progress to compare again."
                    )
                }

                override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    onUpgrade(db, oldVersion, newVersion)
            }
            .apply { setWriteAheadLoggingEnabled(true) }

    suspend fun count(): Int =
        lock.withLock {
            withContext(Dispatchers.IO) {
                helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM measurements", null).use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            }
        }

    /**
     * Called only with a fresh accessible-library inventory. Prune revoked/deleted assets first.
     */
    suspend fun restore(current: List<CleanupItem>): List<CleanupItem> =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val db = helper.writableDatabase
                val accessible = current.mapTo(HashSet()) { it.id }
                db.beginTransaction()
                try {
                    val cached = mutableMapOf<String, String>()
                    val removed = mutableListOf<String>()
                    db.rawQuery("SELECT asset_id, payload FROM measurements", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0)
                            if (id in accessible) cached[id] = cursor.getString(1)
                            else removed += id
                        }
                    }
                    for (id in removed) db.delete("measurements", "asset_id = ?", arrayOf(id))
                    val result =
                        current.map { item ->
                            cached[item.id]?.let { scanRestore(item, it) } ?: item
                        }
                    // Changed revisions are no longer completed work. Drop them before showing
                    // progress.
                    for (item in result) if (scanCheckpoint(item) == null)
                        db.delete("measurements", "asset_id = ?", arrayOf(item.id))
                    db.setTransactionSuccessful()
                    result
                } finally {
                    db.endTransaction()
                }
            }
        }

    suspend fun checkpoint(item: CleanupItem) =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val db = helper.writableDatabase
                val text = scanCheckpoint(item)
                if (text == null) db.delete("measurements", "asset_id = ?", arrayOf(item.id))
                else
                    db.insertWithOnConflict(
                            "measurements",
                            null,
                            ContentValues().apply {
                                put("asset_id", item.id)
                                put("payload", text)
                            },
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                        .also { check(it != -1L) { "Scan progress could not be saved." } }
                Unit
            }
        }

    suspend fun clear() =
        lock.withLock {
            withContext(Dispatchers.IO) {
                helper.close()
                if (file.exists() && !SQLiteDatabase.deleteDatabase(file))
                    error("Scan progress could not be cleared.")
            }
        }

    suspend fun close() = lock.withLock { withContext(Dispatchers.IO) { helper.close() } }
}
