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
                            "Google Photos keeps a Locked Folder behind your screen lock, and newer Android versions add a private space. If that’s enough, keep your money.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "What this adds: each vault opens with its own passphrase, PIN or pattern, and keeps photos, videos, files and notes encrypted, organized and backed up in a portable file. Try it for 7 days, then {price}, once.",
                            withoutPrice =
                                "What this adds: each vault opens with its own passphrase, PIN or pattern, and keeps photos, videos, files and notes encrypted, organized and backed up in a portable file. Try it for 7 days, then one purchase.",
                        ),
                        BeforeYouBuyContent.Paragraph(
                            "What it can’t do: protect an open vault on a compromised phone, take back copies you’ve exported, or hide that Vault is installed. After the trial your files stay readable and exportable. Nobody can reset your secret for you.",
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
