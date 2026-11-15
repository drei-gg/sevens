package gg.seven.fast.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders.StringLayoutConstraint
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.circularProgressIndicator
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import gg.seven.fast.wire.WireSnapshot
import java.time.Instant

/** The running fast at a glance: elapsed time and goal progress, counted live by the tile renderer. */
class FastTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            val layout = tileLayout(this, requestParams.deviceConfiguration, watchModel.snapshot.value, System.currentTimeMillis())
            completer.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion("1")
                    // Counting is dynamic; this refreshes the static fallback on older renderers.
                    .setFreshnessIntervalMillis(15 * 60_000L)
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                    .build()
            )
            "fast-tile"
        }
}

private fun openAppAction(context: Context) =
    ActionBuilders.LaunchAction.Builder()
        .setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName(MainActivity::class.java.name)
                .build()
        )
        .build()

internal fun tileLayout(context: Context, device: DeviceParameters, snapshot: WireSnapshot?, now: Long): LayoutElementBuilders.LayoutElement =
    // Fixed Sevens colours, never the watch's dynamic (wallpaper) theme.
    materialScope(context, device, allowDynamicTheme = false, defaultColorScheme = SevensTileColors) {
        val start = snapshot?.start
        primaryLayout(
            titleSlot = { text(LayoutString(context.getString(if (start != null) R.string.fasting else R.string.title))) },
            mainSlot = {
                when {
                    snapshot == null -> text(LayoutString(context.getString(R.string.no_snapshot)), maxLines = 3)
                    start == null -> text(LayoutString(context.getString(R.string.own_pace)), maxLines = 2)
                    else -> running(snapshot, start, now)
                }
            },
            bottomSlot = {
                textEdgeButton(onClick = clickable(openAppAction(context), "open")) {
                    text(LayoutString(context.getString(R.string.open)))
                }
            },
        )
    }

private fun MaterialScope.running(snapshot: WireSnapshot, start: Long, now: Long): LayoutElementBuilders.LayoutElement {
    val elapsed = DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(start)).durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
    val two = DynamicInt32.IntFormatter.Builder().setMinIntegerDigits(2).build()
    val clock =
        elapsed.toIntHours().format().concat(androidx.wear.protolayout.expression.DynamicBuilders.DynamicString.constant(":")).concat(elapsed.minutesPart.format(two))
    // Hours and minutes; the app shows seconds.
    val fallback = elapsedText(now - start).substringBeforeLast(':')
    val goal = snapshot.goal
    val ring = goal != null && goal > start
    val label =
        text(
            LayoutString(fallback, clock, StringLayoutConstraint.Builder("000:00").build()),
            // Inside the goal ring the count must fit its diameter.
            typography = if (ring) Typography.TITLE_LARGE else Typography.DISPLAY_MEDIUM,
        )
    val box = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
    if (ring && goal != null) {
        val span = ((goal - start) / 1000).toFloat()
        box.addContent(
            circularProgressIndicator(
                staticProgress = snapshot.goalProgress(now) ?: 0f,
                dynamicProgress = elapsed.toIntSeconds().asFloat().div(span),
                // The time hue colours the fasting ring only (a data hue, never a control).
                colors =
                    androidx.wear.protolayout.material3.ProgressIndicatorColors(
                        indicatorColor = LayoutColor(0xFFF5913A.toInt()),
                        trackColor = LayoutColor(0xFF36322D.toInt()),
                    ),
            )
        )
    }
    return box.addContent(label).build()
}

/** The Sevens on a tile: cream ink on warm black, as the phone's dark scheme. */
private val SevensTileColors =
    androidx.wear.protolayout.material3.ColorScheme(
        primary = LayoutColor(0xFFEEE9DF.toInt()),
        onPrimary = LayoutColor(0xFF161513.toInt()),
        primaryContainer = LayoutColor(0xFF36322D.toInt()),
        onPrimaryContainer = LayoutColor(0xFFEEE9DF.toInt()),
        secondary = LayoutColor(0xFFA8A196.toInt()),
        onSecondary = LayoutColor(0xFF121110.toInt()),
        surfaceContainerLow = LayoutColor(0xFF1C1A18.toInt()),
        surfaceContainer = LayoutColor(0xFF24211D.toInt()),
        surfaceContainerHigh = LayoutColor(0xFF2B2723.toInt()),
        onSurface = LayoutColor(0xFFEEE9DF.toInt()),
        onSurfaceVariant = LayoutColor(0xFFA8A196.toInt()),
        outline = LayoutColor(0xFF8C857A.toInt()),
        outlineVariant = LayoutColor(0xFF504A42.toInt()),
        background = LayoutColor(0xFF000000.toInt()),
        onBackground = LayoutColor(0xFFEEE9DF.toInt()),
        error = LayoutColor(0xFFFF7468.toInt()),
        onError = LayoutColor(0xFF121110.toInt()),
    )
