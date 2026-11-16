package gg.seven.cleaner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.cleaner.core.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The main flows on sample data (iOS CleanerTour's fixtures): no library, contacts or files touched. */
@RunWith(AndroidJUnit4::class)
class CleanerUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private val model: CleanerModel
        get() = ViewModelProvider(ui.activity)[CleanerModel::class.java]

    @Before
    fun prepare() {
        if (ui.onAllNodesWithTag("seven-look-around").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("seven-look-around").performClick()
        ui.activity.getSharedPreferences("seven-trial", 0).edit().clear().commit()
        model.store.startTrial()
        File(ui.activity.filesDir, "Cleaner/review.json").delete()
        ui.runOnUiThread { Fixtures.seed(model) }
        ui.waitForIdle()
    }

    private fun decisions() = runBlocking { model.repository.read().decisions }

    @Test
    fun queueingAndUndoingAreStoredBeforeTheyShow() {
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithContentDescription("Select Coast at golden hour copy.heic").performScrollTo().performClick()
        ui.onNodeWithText("1 selected").assertExists()
        ui.onAllNodesWithText("Queue removal")[0].performScrollTo().performClick()
        ui.waitUntil(5_000) { decisions()["tour-b"] == ReviewDecision.REMOVE }
        assertEquals("1", model.state.value.revisions["tour-b"])
        ui.onNodeWithTag("nav-Overview").performClick()
        ui.onNodeWithTag("cleaner.pending").performScrollTo().performClick()
        ui.onNodeWithText("Coast at golden hour copy.heic").assertExists()
        ui.onNodeWithText("Undo").performClick()
        ui.waitUntil(5_000) { decisions()["tour-b"] == ReviewDecision.UNDECIDED }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(5_000) { model.state.value.decisions["tour-b"] == ReviewDecision.UNDECIDED }
    }

    @Test
    fun suggestedCopiesNeverIncludeTheFavorite() {
        ui.onNodeWithTag("cleaner.review-groups").performClick()
        ui.onNodeWithText("Select suggested exact copies").performClick()
        assertEquals(setOf("tour-b"), model.selected.value)
        ui.onNodeWithText("Queue 1 selected item").assertExists()
        ui.onNodeWithTag("groups").performScrollToNode(hasText("Identical files"))
        ui.onNodeWithTag("groups").performScrollToNode(hasText("Similar moments"))
    }

    @Test
    fun collectionsFilterTheLibrary() {
        ui.onNodeWithTag("nav-Photos").performClick()
        ui.onNodeWithText("Motion photos").performScrollTo().performClick()
        ui.onNodeWithText("Showing this collection").assertExists()
        ui.onNodeWithTag("library-PHOTO").performScrollToNode(hasText("Weekend walk.heic"))
        ui.onAllNodesWithText("Coffee receipt.png").assertCountEquals(0)
    }

    @Test
    fun contactCandidatesAreComparedNeverMerged() {
        ui.onNodeWithTag("nav-Contacts").performClick()
        ui.onNodeWithText("Compare contacts").performClick()
        ui.onNodeWithText("Shared phone number (including extension)", substring = true).performClick()
        ui.onNodeWithText("Some source fields cannot be read. Keep the original records.").performScrollTo().assertExists()
        ui.onNodeWithText("No source contact is removed by this build.", substring = true).performScrollTo().assertExists()
        ui.onAllNodesWithText("Merge", substring = false).assertCountEquals(0)
    }

    @Test
    fun anExpiredTrialKeepsDecisionsButNotNewScans() {
        ui.activity.getSharedPreferences("seven-trial", 0).edit().putLong("start", System.currentTimeMillis() - 8 * 86_400_000L).commit()
        model.store.refresh()
        ui.waitForIdle()
        ui.onNodeWithTag("nav-Videos").performClick()
        ui.onNodeWithText("Find duplicates").performClick()
        ui.onNodeWithText("Start the free trial or unlock", substring = true).assertExists()
        ui.onNodeWithText("See the unlock").assertExists()
        ui.onNodeWithText("OK").performClick()
        // Reading what was decided stays free.
        ui.onNodeWithTag("nav-Overview").performClick()
        ui.onNodeWithText("Activity").performScrollTo().performClick()
        ui.onNodeWithText("Measured the accessible photo and video resources.").assertExists()
    }

    @Test
    fun filesShowExactCopiesAndUnmeasuredSizes() {
        ui.onNodeWithTag("nav-Files").performClick()
        ui.onNodeWithText("Duplicate groups").performClick()
        ui.onNodeWithText("Exact copies").performScrollTo().assertExists()
        ui.onNodeWithTag("files").performScrollToNode(hasText("Project archive copy.zip"))
        // Only in the largest-files chart, not in the list of copies.
        ui.onAllNodesWithText("Weekend footage.mov", substring = true).assertCountEquals(1)
        ui.onNodeWithTag("files").performScrollToNode(hasText("1 item has not been measured."))
    }
}

/** The iOS tour's sample library, people and folder. */
object Fixtures {
    fun seed(model: CleanerModel) {
        val date = Instant.ofEpochSecond(1_794_528_000)
        fun item(id: String, name: String, kind: MediaKind, bytes: Long?, w: Int, h: Int, favorite: Boolean = false, tags: List<String> = emptyList(), fingerprint: String? = null, duration: Double = 0.0) =
            CleanupItem(id, name, kind, "1", bytes, if (kind == MediaKind.FILE) null else date, w, h, duration, favorite, tags, fingerprint)
        val media =
            listOf(
                item("tour-a", "Coast at golden hour.heic", MediaKind.PHOTO, 8_400_000, 4032, 3024, favorite = true, fingerprint = "same"),
                item("tour-b", "Coast at golden hour copy.heic", MediaKind.PHOTO, 8_400_000, 4032, 3024, fingerprint = "same"),
                item("tour-c", "Weekend walk.heic", MediaKind.PHOTO, 6_200_000, 4032, 3024, tags = listOf("Live Photos")),
                item("tour-receipt", "Coffee receipt.png", MediaKind.PHOTO, 2_900_000, 1179, 2556, tags = listOf("Screenshots")),
                item("tour-d", "Another view.heic", MediaKind.PHOTO, null, 4032, 3024),
                item("tour-video", "The coast.mov", MediaKind.VIDEO, 482_000_000, 3840, 2160, duration = 95.0),
                item("tour-recording", "Screen recording.mov", MediaKind.VIDEO, 176_000_000, 1179, 2556, tags = listOf("Screen recordings"), duration = 42.0),
            )
        val groups =
            matchingExact(media) +
                MatchGroup("similar", MatchKind.SIMILAR, listOf("tour-a", "tour-c"), "Nearby capture times and similar image features. Review both moments.")
        val state =
            newReviewState().copy(
                history = listOf(newHistoryEntry("Reviewed two matching photos. The favorite is kept."), newHistoryEntry("Measured the accessible photo and video resources."))
            )
        runBlocking { model.repository.record("Measured the accessible photo and video resources.") }
        val files =
            listOf(
                item("file-a", "Weekend footage.mov", MediaKind.FILE, 1_450_000_000, 0, 0),
                item("file-b", "Project archive.zip", MediaKind.FILE, 680_000_000, 0, 0, fingerprint = "zip"),
                item("file-c", "Project archive copy.zip", MediaKind.FILE, 680_000_000, 0, 0, fingerprint = "zip"),
                item("file-d", "Reference.pdf", MediaKind.FILE, null, 0, 0),
            )
        fun field(key: String, value: String, label: String = "") = newContactField(key, value, label)
        val people =
            listOf(
                newContactRecord(
                    "contact-a", "Alex Rivera", "personal",
                    listOf(field("givenName", "Alex"), field("familyName", "Rivera"), field("phone", "+63 917 555 0123", "Mobile"), field("email", "alex@example.com", "Personal"), field("birthday", "14 March"), field("organization", "Weekend Studio")),
                    listOf("+639175550123"), emptyList(),
                ),
                newContactRecord(
                    "contact-b", "Alex Rivera", "personal",
                    listOf(field("givenName", "Alex"), field("familyName", "Rivera"), field("phone", "0917 555 0123", "Mobile"), field("email", "alex@studio.example", "Work")),
                    listOf("+639175550123"), emptyList(),
                ),
                newContactRecord("contact-c", "Weekend Studio", "personal", listOf(field("organization", "Weekend Studio")), emptyList(), emptyList()),
            )
        model.seed(media, groups, state, FolderScan(sortLargestFirst(files), matchingExact(files), 1), ContactSnapshot(people, emptyMap(), limited = false))
        assertTrue(model.ready.value)
    }
}
