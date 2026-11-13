package gg.seven.fast

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.fast.core.*
import gg.seven.kit.AccessStore
import gg.seven.kit.PurchaseState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FastInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun atomicPersistenceSurvivesNewRepository() = runBlocking {
        val path = File(context.cacheDir, "persistence-${newId()}.json")
        try {
            val first = ArchiveRepository(context, AtomicFile(path))
            first.load()
            val fast = FastRecord(start = nowMillis() - 10_000)
            first.update { it.save(fast, nowMillis()) }
            val fresh = ArchiveRepository(context, AtomicFile(path))
            fresh.load()
            assertEquals(fast, fresh.state.value!!.active)
        } finally {
            path.delete()
        }
    }

    @Test
    fun malformedArchiveIsPreservedAndMutationIsBlocked() = runBlocking {
        val path = File(context.cacheDir, "corrupt-${newId()}.json")
        val bytes = "{broken".toByteArray()
        path.writeBytes(bytes)
        try {
            val repo = ArchiveRepository(context, AtomicFile(path))
            repo.load()
            assertNotNull(repo.failure.value)
            assertNull(repo.state.value)
            try {
                repo.update { FastArchive() }
                fail("must reject")
            } catch (_: IllegalStateException) {}
            assertArrayEquals(bytes, repo.rawBytes())
        } finally {
            path.delete()
        }
    }

    @Test
    fun failedDiskWriteDoesNotPublishUnsavedState() = runBlocking {
        val repo = ArchiveRepository(context, AtomicFile(File("/proc/sevens-test/fast.json")))
        repo.load()
        val before = repo.state.value
        try {
            repo.update {
                it.copy(
                    metrics =
                        listOf(
                            MetricEntry(date = nowMillis(), kind = MetricKind.water, value = 250.0)
                        )
                )
            }
            fail("must fail")
        } catch (_: java.io.IOException) {}
        assertEquals(before, repo.state.value)
    }

    @Test
    fun entitlementClockRollbackAndPendingNeverGrantAccess() {
        val prefs = context.getSharedPreferences("seven-trial", 0)
        prefs.edit().clear().commit()
        val access = AccessStore(context, false)
        val now = System.currentTimeMillis()
        assertFalse(access.canCreate(PurchaseState(pending = true), now))
        access.startTrial(now - 8 * 86_400_000L)
        access.observeTime(now)
        assertFalse(access.canCreate(PurchaseState(), now - 7 * 86_400_000L))
        assertTrue(access.canCreate(PurchaseState(owned = true), now))
        assertTrue(AccessStore(context, true).canCreate(PurchaseState(), now))
        prefs.edit().clear().commit()
    }
}
