package gg.seven.fast

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The generic build is the public source as anyone builds it: no trial, everything unlocked. */
@RunWith(AndroidJUnit4::class)
class SourceBuildTest {
    @Test
    fun anExpiredTrialStillAddsRecords() {
        val app = ApplicationProvider.getApplicationContext<FastApplication>()
        val now = System.currentTimeMillis()
        val trial = app.getSharedPreferences("seven-trial", 0)
        trial.edit().clear().putLong("start", now - 8 * 86_400_000L).commit()
        try {
            app.store.refresh()
            assertTrue(app.store.purchase.value.sourceBuild)
            assertTrue(app.access.canCreate(app.store.purchase.value, now))
        } finally {
            trial.edit().clear().commit()
        }
    }
}
