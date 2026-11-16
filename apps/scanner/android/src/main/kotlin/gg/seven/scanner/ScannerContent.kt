package gg.seven.scanner

import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Document Scanner's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
internal val ScannerContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = "Your phone already scans documents.",
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(
                            "Google Drive can scan paper into PDFs, and many phones’ cameras can too. If that covers your needs, keep your money.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "This adds a dedicated searchable library, recognized text and page management. Try everything for 7 days, then {price}, once. Nothing renews.",
                            withoutPrice =
                                "This adds a dedicated searchable library, recognized text and page management. Try everything for 7 days, then a one-time purchase. Nothing renews.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Your saved documents remain readable, editable and exportable after the trial.",
                        ),
                    ),
                lookAroundLabel = "Look around first",
            ),
        unlock =
            UnlockContent(
                appName = "Document Scanner",
                features =
                    listOf(
                        "Scan and import documents",
                        "A private searchable library",
                        "Page management and PDF export",
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        "Read, edit, delete and export saved documents",
                    ),
                unlockKeeps =
                    listOf(
                        "Scan and import new documents",
                    ),
            ),
    )
