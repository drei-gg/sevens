package gg.seven.qr

import android.content.res.Resources
import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * QR Code Kit's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
internal fun qrContent(res: Resources): SevenContent =
    SevenContent(
        beforeYouBuy =
            BeforeYouBuyContent(
                title = res.getString(R.string.qr_gate_before_you_buy),
                paragraphs =
                    listOf(
                        BeforeYouBuyContent.Paragraph(res.getString(R.string.qr_gate_phone_scans)),
                        BeforeYouBuyContent.Paragraph(
                            res.getString(R.string.qr_gate_what_this_adds),
                            withoutPrice = res.getString(R.string.qr_gate_what_this_adds_no_price),
                        ),
                    ),
                lookAroundLabel = res.getString(R.string.qr_gate_look_around),
            ),
        unlock =
            UnlockContent(
                appName = res.getString(R.string.app_name),
                features =
                    listOf(
                        res.getString(R.string.qr_gate_feature_peek),
                        res.getString(R.string.qr_gate_feature_make),
                        res.getString(R.string.qr_gate_feature_share),
                        res.getString(R.string.qr_gate_feature_history),
                    ),
            ),
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        res.getString(R.string.qr_gate_free_scanning),
                        res.getString(R.string.qr_gate_free_verdict),
                        res.getString(R.string.qr_gate_free_payments),
                        res.getString(R.string.qr_gate_free_opening),
                        res.getString(R.string.qr_gate_free_check_share),
                        res.getString(R.string.qr_gate_free_history),
                    ),
                unlockKeeps =
                    listOf(
                        res.getString(R.string.qr_peek),
                        res.getString(R.string.qr_gate_keeps_make),
                        res.getString(R.string.qr_gate_keeps_share),
                        res.getString(R.string.qr_gate_keeps_history),
                    ),
            ),
        notStartedStatus = res.getString(R.string.qr_gate_not_started),
    )
