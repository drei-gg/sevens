@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package gg.seven.cleaner

import android.content.Context
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.seven.cleaner.core.CleanupItem
import gg.seven.cleaner.core.MediaKind
import gg.seven.kit.SevenColors
import gg.seven.kit.Space
import gg.seven.kit.numeric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Cleaner's own compositions (iOS CleanerVisuals): the hero, stat tiles, notices, the measured
 * media bar and the largest-files bars. Material components inside; the two charts are drawn,
 * being Cleaner's own visuals. Data hues stay on glyphs and chart marks.
 */

/** The data hues (polish guide: dataCool, dataWarm), shared with SevenKit's palette. */
object Hues {
    val cool: Color
        @Composable get() = SevenColors.water

    val warm: Color
        @Composable get() = SevenColors.time

    /** Status: careful (polish guide `caution`). Not in SevenKit's palette yet. */
    val caution: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF2A33A) else Color(0xFF8A4A00)
}

fun byteText(context: Context, bytes: Long?): String =
    bytes?.let { Formatter.formatFileSize(context, it) } ?: "Not measured"

@Composable
fun byteText(bytes: Long?): String = byteText(LocalContext.current, bytes)

/** True at large font scales, where two columns no longer fit (iOS isAccessibilitySize). */
@Composable
fun largeText(): Boolean = LocalDensity.current.fontScale >= 1.5f

@Composable
fun count(id: Int, value: Int): String = androidx.compose.ui.res.pluralStringResource(id, value, value)

/** The screen's one hero: glyph, label, value and a sentence, centred. */
@Composable
fun CleanerHero(
    label: String,
    value: String,
    detail: String,
    icon: ImageVector,
    hue: Color = Hues.cool,
    big: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.l).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        Icon(icon, null, Modifier.size(if (largeText()) 32.dp else 48.dp), tint = hue)
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(
            value,
            Modifier.semantics { heading() },
            style = (if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium).numeric(),
            textAlign = TextAlign.Center,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A stat tile: glyph, value, title and an optional line; a card, clickable when it leads on. */
@Composable
fun CleanerStat(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    detail: String? = null,
    hue: Color = Hues.cool,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(icon, null, tint = hue)
            Text(value, style = MaterialTheme.typography.titleLarge.numeric())
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    val colors =
        CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        )
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxHeight(), colors = colors, content = body)
    else Card(modifier = modifier.fillMaxHeight().semantics(mergeDescendants = true) {}, colors = colors, content = body)
}

/** Two equal columns of tiles, one at large font sizes (iOS CleanerTiles). */
@Composable
fun CleanerTiles(vararg tiles: @Composable (Modifier) -> Unit) {
    val columns = if (largeText()) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
        tiles.toList().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A one-line explanation with its glyph; [caution] for a found problem. */
@Composable
fun CleanerNotice(text: String, icon: ImageVector = Icons.Outlined.Info, caution: Boolean = false) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (caution) Hues.caution else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A ruled section header for our own layouts (polish guide §4.3). */
@Composable
fun SectionRule(text: String, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            trailing?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The screen's one filled action, full width. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
    ) {
        Text(text, style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
    }
}

/** A quick action (iOS CleanerChip): Material's assist chip. */
@Composable
fun QuickAction(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(text) }, leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) })
}

/** A content column at the readable width, centred on wide screens. */
@Composable
fun Readable(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 672.dp).fillMaxWidth().padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.l), content = content)
    }
}

/** Photo and video bytes as one proportioned bar, with their totals (iOS LibraryComposition). */
@Composable
fun LibraryComposition(items: List<CleanupItem>) {
    val photos = items.filter { it.kind == MediaKind.PHOTO }.sumOf { it.bytes ?: 0 }
    val videos = items.filter { it.kind == MediaKind.VIDEO }.sumOf { it.bytes ?: 0 }
    val cool = Hues.cool
    val warm = Hues.warm
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(Space.m).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.ms)) {
            Text("Measured media", style = MaterialTheme.typography.titleMedium)
            if (photos + videos > 0) {
                Canvas(Modifier.fillMaxWidth().height(Space.m).clip(RoundedCornerShape(50)).clearAndSetSemantics {}) {
                    val split = size.width * photos / (photos + videos)
                    drawRect(cool, size = Size(split, size.height))
                    drawRect(warm, topLeft = Offset(split, 0f), size = Size(size.width - split, size.height))
                }
                CleanerTiles(
                    { m -> Legend("Photos · ${byteText(photos)}", Icons.Outlined.Image, cool, m) },
                    { m -> Legend("Videos · ${byteText(videos)}", Icons.Outlined.Videocam, warm, m) },
                )
            } else
                Text("Measure your library to see the photo and video breakdown.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Legend(text: String, icon: ImageVector, hue: Color, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = hue)
        Text(text, style = MaterialTheme.typography.bodySmall.numeric())
    }
}

/** The three largest measured files against the largest (iOS FileSizeChart). */
@Composable
fun FileSizeChart(items: List<CleanupItem>) {
    val measured = items.filter { it.bytes != null }
    val largest = (measured.maxOfOrNull { it.bytes ?: 0 } ?: 1).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.outlineVariant
    val warm = Hues.warm
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.ms)) {
            Text("Largest files", style = MaterialTheme.typography.titleMedium)
            measured.take(3).forEach { item ->
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(byteText(item.bytes), style = MaterialTheme.typography.bodySmall.numeric())
                    }
                    Canvas(Modifier.fillMaxWidth().height(Space.s).clearAndSetSemantics {}) {
                        val radius = CornerRadius(size.height / 2)
                        drawRoundRect(track, cornerRadius = radius)
                        drawRoundRect(warm, size = Size(size.width * (item.bytes ?: 0) / largest, size.height), cornerRadius = radius)
                    }
                }
            }
        }
    }
}

/** A media thumbnail, or a quiet placeholder while it loads or when it can't be read. */
@Composable
fun Thumbnail(item: CleanupItem, modifier: Modifier = Modifier, pixels: Int = 320) {
    val photos = LocalPhotos.current
    val bitmap by
        produceState<Bitmap?>(null, item.id, pixels) {
            value = withContext(Dispatchers.IO) { photos?.thumbnailBitmap(item.id, pixels) }
        }
    Box(modifier.clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (item.kind == MediaKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
    }
}

val LocalPhotos = staticCompositionLocalOf<PhotosService?> { null }

/** A spoken label for a merged element. */
fun Modifier.spoken(text: String) = semantics { contentDescription = text }
