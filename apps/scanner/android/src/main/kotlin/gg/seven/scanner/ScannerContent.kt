package gg.seven.scanner

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Document Scanner's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
@Composable
internal fun scannerContent(): SevenContent {
    val newDocuments = stringResource(R.string.gate_feature_new)
    val addPages = stringResource(R.string.gate_feature_add)
    return SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = stringResource(R.string.gate_before_title),
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(
                            stringResource(R.string.gate_before_alternatives)
                        ),
                        BeforeYouBuyContent.Paragraph(
                            stringResource(R.string.gate_before_offer),
                            withoutPrice = stringResource(R.string.gate_before_offer_without_price),
                        ),
                        BeforeYouBuyContent.Paragraph(
                            stringResource(R.string.gate_before_after_trial)
                        ),
                    ),
                lookAroundLabel = stringResource(R.string.gate_look_around),
            ),
        unlock =
            UnlockContent(
                appName = stringResource(R.string.gate_app_name),
                features = listOf(newDocuments, addPages),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        stringResource(R.string.gate_free_documents),
                        stringResource(R.string.gate_free_backup),
                    ),
                unlockKeeps = listOf(newDocuments, addPages),
            ),
    )
}
