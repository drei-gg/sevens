package gg.seven.cleaner

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Cleaner's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
@Composable
internal fun cleanerContent(): SevenContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = stringResource(R.string.gate_before_title),
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(stringResource(R.string.gate_before_alternatives)),
                        BeforeYouBuyContent.Paragraph(
                            stringResource(R.string.gate_before_offer),
                            withoutPrice = stringResource(R.string.gate_before_offer_without_price),
                        ),
                        BeforeYouBuyContent.Paragraph(stringResource(R.string.gate_before_on_device)),
                    ),
                lookAroundLabel = stringResource(R.string.gate_look_around),
            ),
        unlock =
            UnlockContent(
                appName = stringResource(R.string.gate_app_name),
                features =
                    listOf(
                        stringResource(R.string.gate_feature_review),
                        stringResource(R.string.gate_feature_contacts),
                        stringResource(R.string.gate_feature_copies),
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        stringResource(R.string.gate_free_history),
                        stringResource(R.string.gate_free_archive),
                    ),
                unlockKeeps = listOf(stringResource(R.string.gate_unlock_scans)),
            ),
    )
