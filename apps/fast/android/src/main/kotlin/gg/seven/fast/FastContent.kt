package gg.seven.fast

import android.content.res.Resources
import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Fast's words for the brand moments, from the iOS gate copy (FastGate.swift). "Before you buy"
 * names what Android already does built in, so it differs from iOS where the phone differs.
 */
internal fun fastContent(res: Resources) =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = res.getString(R.string.gate_title),
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(res.getString(R.string.gate_built_in)),
                        BeforeYouBuyContent.Paragraph(
                            res.getString(R.string.gate_offer, BeforeYouBuyContent.PRICE),
                            withoutPrice = res.getString(R.string.gate_offer_without_price),
                        ),
                        BeforeYouBuyContent.Paragraph(res.getString(R.string.gate_records_stay)),
                        BeforeYouBuyContent.Paragraph(res.getString(R.string.gate_records_not_prescriptions)),
                    ),
                lookAroundLabel = res.getString(R.string.gate_look_around),
            ),
        unlock =
            UnlockContent(
                appName = res.getString(R.string.gate_app_name),
                features = res.getStringArray(R.array.gate_unlock_features).toList(),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree = res.getStringArray(R.array.gate_still_free).toList(),
                unlockKeeps = res.getStringArray(R.array.gate_unlock_keeps).toList(),
            ),
        notStartedStatus = res.getString(R.string.gate_not_started),
    )
