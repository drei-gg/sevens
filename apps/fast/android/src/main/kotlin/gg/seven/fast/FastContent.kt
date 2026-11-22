package gg.seven.fast

import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Fast's words for the brand moments, from the iOS gate copy (FastGate.swift). "Before you buy"
 * names what Android already does built in, so it differs from iOS where the phone differs.
 */
internal val FastContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = "Your phone already has a timer.",
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(
                            "The Clock app can time a fast, a notes app can hold a journal, and Health Connect can keep your weight. If that’s all you need, keep your money."
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "This brings your timers, calendar, patterns, private journal and water tracker with optional reminders together. Try everything for 7 days, no card. Then {price}, once. Nothing renews.",
                            withoutPrice =
                                "This brings your timers, calendar, patterns, private journal and water tracker with optional reminders together. Try everything for 7 days, no card. Then a one-time purchase. Nothing renews.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Your saved records always stay readable, editable and exportable, even after the trial. A fast already running can always be ended and saved."
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Timers are records, not prescriptions. A shorter fast is a valid record. Nothing starts or ends by itself."
                        ),
                    ),
                lookAroundLabel = "Look around first",
            ),
        unlock =
            UnlockContent(
                appName = "Fast Tracker",
                features =
                    listOf(
                        "Fasting and eating timers, with flexible plans",
                        "A complete calendar, heatmap and trends",
                        "A private journal and optional measurements",
                        "Water tracking, personal goals and gentle reminders",
                        "Local reminders and portable backups",
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        "Reading, correcting, deleting and exporting your records",
                        "Ending and saving a fast already in progress",
                    ),
                unlockKeeps = listOf("Starting new fasts, plans, journal entries and measurements"),
            ),
        notStartedStatus = "Free trial not started. Your saved records stay accessible.",
    )
