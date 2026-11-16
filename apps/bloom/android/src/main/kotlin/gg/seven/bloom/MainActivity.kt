package gg.seven.bloom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import gg.seven.kit.*

class MainActivity : ComponentActivity() {
    private val model: BloomModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SevenTheme { SevenRoot(model.store, BloomContent) { BloomApp(model) } } }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}

internal val BloomContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                "Your phone already has a calendar.",
                listOf(
                    BeforeYouBuyContent.Paragraph(
                        "Calendar, Clock and Notes can keep dates, reminders and a journal. If that covers you, keep your money."
                    ),
                    BeforeYouBuyContent.Paragraph(
                        "This brings your cycle, symptom and private contact records together, with portable encrypted backups. Try everything for seven days, then {price}, once.",
                        withoutPrice =
                            "Try the journal for seven days, then unlock once. Nothing renews.",
                    ),
                    BeforeYouBuyContent.Paragraph(
                        "This Android preview does not deliver reminders or connect to Health Connect or a watch yet. Calendar predictions are not contraception or a diagnosis."
                    ),
                ),
                lookAroundLabel = "Look around first",
            ),
        unlock =
            UnlockContent(
                "Cycle & Health",
                listOf(
                    "Period and symptom journal",
                    "Private sex/contact records",
                    "Shared-core plan and dose tracking",
                    "Exports and encrypted backups",
                ),
            ),
        trialEnded =
            TrialEndedContent(
                listOf(
                    "Reading, correcting, deleting and exporting records",
                    "Dose records on existing plans",
                ),
                listOf("Adding new records and plans"),
            ),
        notStartedStatus = "Trial not started. Your records remain yours.",
    )
