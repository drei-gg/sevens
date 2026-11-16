package gg.seven.vault

import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Vault's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
internal val VaultContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = "Your phone already has a Locked Folder.",
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(
                            "Google Photos keeps a Locked Folder behind your screen lock, and newer Android versions add a private space. If those are enough, keep your money.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Vault adds an independent passphrase, an encrypted library for mixed files, organization and portable backups. Try it for 7 days, then {price}, once.",
                            withoutPrice =
                                "Vault adds an independent passphrase, an encrypted library for mixed files, organization and portable backups. Try it for 7 days, then one purchase.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "Existing files remain readable and exportable after the trial. Recovery needs your credentials and an intact vault or backup. We cannot reset your encryption key.",
                        ),
                    ),
                lookAroundLabel = "Look around",
            ),
        unlock =
            UnlockContent(
                appName = "Vault",
                features =
                    listOf(
                        "Encrypted photos, videos, files and notes",
                        "Independent vaults and useful organization",
                        "Portable encrypted backups. No subscription.",
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        "Open, organize, export and delete existing files",
                        "Back up, restore and recover your vaults",
                    ),
                unlockKeeps =
                    listOf(
                        "Add vaults, files, photos and notes",
                    ),
            ),
    )
