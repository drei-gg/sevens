package gg.seven.fast.wear

import android.graphics.drawable.Icon
import android.os.Build
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import gg.seven.fast.wire.WireSnapshot
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Elapsed time (and progress towards the goal) on the watch face. Text counts up on its own. */
class FastComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType, watchModel.snapshot.value, System.currentTimeMillis())

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val now = System.currentTimeMillis()
        val start = now - (3 * 60 + 25) * 60_000L
        return data(type, WireSnapshot(java.util.UUID(0, 0), now, null, start, start + 16 * 3_600_000L, 0.0, true), now)
    }

    private fun plain(text: String) = PlainComplicationText.Builder(text).build()

    private fun data(type: ComplicationType, snapshot: WireSnapshot?, now: Long): ComplicationData? {
        val start = snapshot?.start
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_fast)).build()
        val tap = Surfaces.openApp(this)
        val description = plain(getString(R.string.complication_label))
        if (start == null) {
            val idle = getString(R.string.no_fast)
            return when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(idle), description).setMonochromaticImage(icon).setTapAction(tap).build()
                ComplicationType.LONG_TEXT ->
                    LongTextComplicationData.Builder(plain(getString(R.string.not_fasting)), description).setMonochromaticImage(icon).setTapAction(tap).build()
                ComplicationType.RANGED_VALUE ->
                    RangedValueComplicationData.Builder(0f, 0f, 1f, description).setText(plain(idle)).setMonochromaticImage(icon).setTapAction(tap).build()
                else -> null
            }
        }
        val elapsed =
            TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_DUAL_UNIT, CountUpTimeReference(Instant.ofEpochMilli(start)))
                .setMinimumTimeUnit(TimeUnit.MINUTES)
                .build()
        val goal = snapshot.goal
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(elapsed, description).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> {
                val text =
                    if (goal != null && goal > start)
                        TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_DUAL_UNIT, CountUpTimeReference(Instant.ofEpochMilli(start)))
                            .setMinimumTimeUnit(TimeUnit.MINUTES)
                            .setText(getString(R.string.elapsed_of_goal, "^1", shortDuration(goal - start)))
                            .build()
                    else elapsed
                LongTextComplicationData.Builder(text, description).setTitle(plain(getString(R.string.fasting))).setMonochromaticImage(icon).setTapAction(tap).build()
            }
            ComplicationType.RANGED_VALUE -> {
                val span = if (goal != null && goal > start) (goal - start) / 1000f else 16 * 3600f
                val seconds = ((now - start) / 1000f).coerceIn(0f, span)
                // Live where the watch face supports dynamic values; the static value otherwise.
                val live =
                    DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(start))
                        .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
                        .toIntSeconds()
                        .asFloat()
                val builder =
                    if (Build.VERSION.SDK_INT >= 33) RangedValueComplicationData.Builder(live, seconds, 0f, span, description)
                    else RangedValueComplicationData.Builder(seconds, 0f, span, description)
                builder
                    .setText(elapsed)
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
            }
            else -> null
        }
    }
}
