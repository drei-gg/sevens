package gg.seven.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Vault's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
@Composable
internal fun vaultContent() =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = stringResource(R.string.gate_byb_title),
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(stringResource(R.string.gate_byb_builtin)),
                        BeforeYouBuyContent.Paragraph(
                            stringResource(R.string.gate_byb_adds),
                            withoutPrice = stringResource(R.string.gate_byb_adds_without_price),
                        ),
                        BeforeYouBuyContent.Paragraph(stringResource(R.string.gate_byb_limits)),
                    ),
                lookAroundLabel = stringResource(R.string.gate_byb_look_around),
            ),
        unlock =
            UnlockContent(
                appName = stringResource(R.string.lock_title),
                features =
                    listOf(
                        stringResource(R.string.gate_unlock_encrypted),
                        stringResource(R.string.gate_unlock_vaults),
                        stringResource(R.string.gate_unlock_backups),
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        stringResource(R.string.gate_ended_open),
                        stringResource(R.string.gate_ended_backup),
                    ),
                unlockKeeps = listOf(stringResource(R.string.gate_ended_add)),
            ),
    )
