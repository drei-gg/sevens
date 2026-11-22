package gg.seven.qr

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.kit.Entitlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The generic build is the public source as anyone builds it: no trial, everything unlocked. */
@RunWith(AndroidJUnit4::class)
class SourceBuildTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun anExpiredTrialStillHasEveryFeature() {
        val model = ViewModelProvider(ui.activity)[QrModel::class.java]
        val trial = ui.activity.getSharedPreferences("seven-trial", 0)
        trial.edit().clear().putLong("start", System.currentTimeMillis() - 8 * 86_400_000L).commit()
        try {
            model.store.refresh()
            assertTrue(model.store.purchase.value.sourceBuild)
            assertEquals(Entitlement.Unlocked, model.store.entitlement.value)
            assertTrue(model.store.paidFeaturesAvailable)
        } finally {
            trial.edit().clear().commit()
        }
    }
}
