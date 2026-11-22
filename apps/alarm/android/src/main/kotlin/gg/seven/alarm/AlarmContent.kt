package gg.seven.alarm

import android.content.res.Resources
import gg.seven.kit.BeforeYouBuyContent
import gg.seven.kit.SevenContent
import gg.seven.kit.TrialEndedContent
import gg.seven.kit.UnlockContent

/*
 * Alarm & Loops's words for the brand moments, from the iOS gate copy. "Before you buy" names what
 * Android already does built in (never another third-party app), so it differs from iOS.
 */
internal fun alarmContent(res: Resources) =
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
                    ),
                lookAroundLabel = res.getString(R.string.gate_look_around),
            ),
        unlock =
            UnlockContent(
                appName = res.getString(R.string.gate_app_name),
                features =
                    listOf(
                        res.getString(R.string.gate_feature_missions),
                        res.getString(R.string.gate_feature_shared_times),
                        res.getString(R.string.gate_feature_loops),
                        res.getString(R.string.gate_feature_backups),
                    ),
            ),
        // iOS never presents a trial-ended sheet (expiry shows in Settings only, never over a ringing
        // alarm or mission), so AlarmModel marks it as seen. These lists are SPEC "Trial"'s split,
        // kept should SevenRoot ever show it.
        trialEnded =
            TrialEndedContent(
                stillFree =
                    listOf(
                        res.getString(R.string.gate_free_alarms),
                        res.getString(R.string.gate_free_stop),
                        res.getString(R.string.gate_free_history),
                        res.getString(R.string.gate_free_import),
                    ),
                unlockKeeps =
                    listOf(
                        res.getString(R.string.gate_keeps_editing),
                        res.getString(R.string.gate_keeps_reenable),
                    ),
            ),
        notStartedStatus = res.getString(R.string.gate_not_started),
    )
