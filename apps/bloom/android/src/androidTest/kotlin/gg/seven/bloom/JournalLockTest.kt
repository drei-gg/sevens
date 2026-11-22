package gg.seven.bloom

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class JournalLockTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun cancellationAndFailedPersistenceKeepProtection() {
        val writes = mutableListOf<Boolean>()
        var writable = false
        val lock =
            JournalLock(true, "Not saved") {
                writes += it
                writable
            }
        assertTrue(lock.locked.value)
        assertTrue(lock.begin(false))
        assertFalse(lock.begin(false))
        lock.failed("Cancelled")
        lock.authenticated() // A late result after cancellation cannot unlock.
        assertTrue(lock.enabled.value)
        assertTrue(lock.locked.value)
        assertTrue(writes.isEmpty())
        lock.begin(false)
        lock.authenticated()
        assertTrue(lock.enabled.value)
        assertTrue(lock.locked.value)
        assertNotNull(lock.message.value)
        writable = true
        lock.begin()
        lock.authenticated()
        assertFalse(lock.locked.value)
        lock.protect()
        assertTrue(lock.locked.value)
        lock.begin(false)
        lock.authenticated()
        assertFalse(lock.enabled.value)
        assertFalse(lock.locked.value)
        lock.protect()
        assertFalse(lock.locked.value)
        assertEquals(listOf(false, false), writes)
    }

    @Test
    fun enabledLockHidesJournalAndRelocksAfterBackground() {
        lateinit var lock: JournalLock
        ui.runOnIdle {
            lock = ViewModelProvider(ui.activity)[BloomModel::class.java].appLock
            lock.begin(true)
            lock.authenticated()
            lock.protect()
        }
        try {
            ui.onNodeWithText("Your records are locked").assertIsDisplayed()
            ui.onNodeWithText("Log today").assertDoesNotExist()
            ui.onNodeWithTag("journal.unlock").assertHasClickAction()
            ui.waitUntil(5_000) {
                ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            }
            ui.runOnIdle {
                assertTrue(
                    ui.activity.window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0
                )
                lock.begin()
                lock.authenticated()
            }
            ui.onNodeWithText("Your records are locked").assertDoesNotExist()
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("Your records are locked").assertDoesNotExist()
            ui.runOnIdle {
                val model = ViewModelProvider(ui.activity)[BloomModel::class.java]
                model.healthPreview.value = gg.seven.bloom.core.bloomEmpty()
                model.preview.value = gg.seven.bloom.core.bloomEmpty()
            }
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.onNodeWithText("Your records are locked").assertIsDisplayed()
            ui.runOnIdle {
                val model = ViewModelProvider(ui.activity)[BloomModel::class.java]
                assertNull(model.healthPreview.value)
                assertNull(model.preview.value)
            }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("Your records are locked").assertIsDisplayed()
        } finally {
            ui.runOnIdle {
                lock.begin(false)
                lock.authenticated()
            }
        }
    }
}
