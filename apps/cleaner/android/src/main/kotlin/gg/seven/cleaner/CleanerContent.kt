package gg.seven.cleaner

import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Cleaner's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
internal val CleanerContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = "Your phone already has cleanup tools.",
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(
                            "Settings shows what uses storage, Google Photos can free up space, and Contacts can merge duplicates. If those cover your needs, keep your money.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Cleaner brings media review, selected folders and contact comparison together. Try it for 7 days, then {price}, once. Nothing renews.",
                            withoutPrice =
                                "Cleaner brings media review, selected folders and contact comparison together. Try it for 7 days, then a one-time purchase. Nothing renews.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Processing stays on this device. Compressed copies keep their originals.",
                        ),
                    ),
                lookAroundLabel = "Look around first",
            ),
        unlock =
            UnlockContent(
                appName = "Cleaner",
                features =
                    listOf(
                        "Review photos, videos and selected folders",
                        "Compare duplicate contact candidates",
                        "Make and inspect compressed copies",
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        "Read saved decisions and history",
                        "Share your saved contact archive",
                    ),
                unlockKeeps =
                    listOf(
                        "Run new scans and cleanup actions",
                    ),
            ),
    )
