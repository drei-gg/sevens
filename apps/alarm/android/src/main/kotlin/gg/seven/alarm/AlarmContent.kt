package gg.seven.alarm

import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Alarm & Loops's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
internal val AlarmContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = "Your phone already has alarms.",
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(
                            "The Clock app already wakes you up and times things. If that is enough, keep your money.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then {price}, once. Nothing renews.",
                            withoutPrice =
                                "This adds wake-up missions, flexible time loops and a history you can keep. Try everything for 7 days, then a one-time purchase.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Existing enabled alarms keep running after the trial. You can always stop alarms and read, export or delete your history.",
                        ),
                    ),
                lookAroundLabel = "Look around first",
            ),
        unlock =
            UnlockContent(
                appName = "Alarm & Loops",
                features =
                    listOf(
                        "Wake-up missions and follow-up checks",
                        "Shared times with different weekdays",
                        "Interval loops and complete local history",
                        "Portable backups and exports",
                    ),
            ),
        // iOS never presents a trial-ended sheet (expiry shows in Settings only, never over a ringing
        // alarm or mission), so AlarmModel marks it as seen. These lists are SPEC "Trial"'s split,
        // kept should SevenRoot ever show it.
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        "Alarms you already enabled keep ringing",
                        "Turning alarms off, deleting them and stopping them",
                        "Correcting, exporting and deleting your history",
                        "Importing a backup, with its routines turned off",
                    ),
                unlockKeeps =
                    listOf(
                        "Creating and editing alarms, loops and naps",
                        "Turning routines back on",
                    ),
            ),
        notStartedStatus = "Free trial not started. Existing alarms and history remain available.",
    )
