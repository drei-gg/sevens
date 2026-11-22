package gg.seven.scanner

import android.content.res.Resources
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.seven.kit.Space
import gg.seven.kit.numeric
import gg.seven.scanner.core.PageSettings
import gg.seven.scanner.core.ScanDocument
import gg.seven.scanner.core.ScanPage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Scanner's own visual pieces (port of iOS Visuals.swift): page thumbnails, document cards, status
 * lines and stat tiles. Everything else is a Material component.
 */

object ScannerMetrics {
    /** Thumbnails sit inside cards with a small gap around them (concentric corners). */
    val thumbnailRadius = 8.dp

    /** A scanned page is portrait paper; A4 and Letter are both close to 3:4 at thumbnail size. */
    const val PAGE_ASPECT = 3f / 4f

    /** The readable width for content on tablets and in landscape (polish guide §5.4). */
    val contentWidth = 672.dp
}

fun pageCount(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.pages, count, count)

@Composable fun pageCount(count: Int): String = pluralStringResource(R.plurals.pages, count, count)

fun documentCount(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.documents, count, count)

@Composable
fun documentCount(count: Int): String = pluralStringResource(R.plurals.documents, count, count)

/** "Nov 16, 2026". */
fun day(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** "Nov 16, 2026, 4:05 PM". */
fun dayAndTime(millis: Long): String = ScannerModel.titleStamp(millis)

/** True at large font scales, where side-by-side layouts stack (iOS accessibility sizes). */
@Composable fun largeText(): Boolean = LocalDensity.current.fontScale >= 1.5f

/** Rendered pages, so grids don't decode the same page twice while scrolling. */
private data class ThumbnailKey(
    val path: String,
    val modified: Long,
    val settings: PageSettings,
    val annotations: List<gg.seven.scanner.core.PageAnnotation>,
    val ink: List<gg.seven.scanner.core.PageInk>,
    val maxPixels: Int,
)

private object Thumbnails {
    private val cache =
        object :
            LruCache<ThumbnailKey, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: ThumbnailKey, value: ImageBitmap) =
                value.width * value.height * 4 +
                    key.annotations.size * 64 +
                    key.ink.sumOf { it.points.size * 48 + 128 }
        }

    fun get(key: ThumbnailKey): ImageBitmap? = cache.get(key)

    fun put(key: ThumbnailKey, image: ImageBitmap) {
        cache.put(key, image)
    }
}

/**
 * A page rendered with its adjustments. Nothing shows while it renders (a frame or two at thumbnail
 * size), so grids don't flicker spinners; it fades in.
 */
@Composable
fun PageImage(
    file: File?,
    settings: PageSettings,
    modifier: Modifier = Modifier,
    maxPixels: Int = 800,
    fill: Boolean = false,
    annotations: List<gg.seven.scanner.core.PageAnnotation> = emptyList(),
    ink: List<gg.seven.scanner.core.PageInk> = emptyList(),
) {
    // Keep structural geometry references, not a giant point-list string per cached thumbnail.
    val key =
        file?.let {
            ThumbnailKey(it.path, it.lastModified(), settings, annotations, ink, maxPixels)
        }
    var image by remember(key) { mutableStateOf(key?.let(Thumbnails::get)) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (file == null || key == null || image != null) return@LaunchedEffect
        delay(100)
        try {
            val rendered =
                withContext(Dispatchers.Default) {
                    Processing.rendered(file, settings, maxPixels, annotations, ink).asImageBitmap()
                }
            Thumbnails.put(key, rendered)
            image = rendered
        } catch (_: Exception) {
            failed = true
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Crossfade(image, label = "page") { shown ->
            when {
                shown != null ->
                    Image(
                        shown,
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                    )
                failed ->
                    Icon(
                        Icons.Outlined.WarningAmber,
                        stringResource(R.string.page_unavailable),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> Spacer(Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * One page as a thumbnail: the adjusted image on card, rounded, with a hairline so white paper
 * still has an edge.
 */
@Composable
fun PageThumbnail(
    file: File?,
    page: ScanPage?,
    modifier: Modifier = Modifier,
    maxPixels: Int = 480,
) {
    val shape = RoundedCornerShape(ScannerMetrics.thumbnailRadius)
    Box(
        modifier
            .aspectRatio(ScannerMetrics.PAGE_ASPECT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clearAndSetSemantics {}
    ) {
        if (page != null)
            PageImage(
                file,
                page.settings,
                Modifier.fillMaxSize(),
                maxPixels,
                fill = true,
                annotations = page.annotations,
                ink = page.ink,
            )
    }
}

/**
 * A document in the library grid: its first page with the page count on the corner, then the name
 * and date.
 */
@Composable
fun DocumentCard(document: ScanDocument, firstPage: File?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Box {
            PageThumbnail(firstPage, document.pages.firstOrNull(), Modifier.fillMaxWidth())
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(Space.s),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.padding(horizontal = Space.s, vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                    Text(
                        "${document.pages.size}",
                        style = MaterialTheme.typography.labelLarge.numeric(),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (document.favorite == true) Icon(Icons.Outlined.Star, null, Modifier.size(16.dp))
                Text(
                    document.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                day(document.createdMillis),
                style = MaterialTheme.typography.bodySmall.numeric(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The words TalkBack reads for a card: name, then pages, date and favorite. */
@Composable
fun cardDescription(document: ScanDocument, pages: String): String =
    stringResource(
        if (document.favorite == true) R.string.document_card_favorite else R.string.document_card,
        document.title,
        pages,
        day(document.createdMillis),
    )

/** A status line: the colour goes on the icon, the words stay on-surface (polish guide §3.1). */
@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.WarningAmber,
    tint: Color = MaterialTheme.colorScheme.error,
) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tint)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A labelled number on a card: Backup's summary. */
@Composable
fun StatTile(title: String, value: String, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.stat_tile, title, value)
    Surface(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(Space.ms).heightIn(min = 64.dp),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.titleLarge.numeric())
        }
    }
}

/** A card on paper for a titled block of content (iOS GroupBox). */
@Composable
fun ContentCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

/** The page column, capped at the readable width and centred on wide screens. */
fun Modifier.readable(): Modifier =
    this.fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = ScannerMetrics.contentWidth)

/**
 * Haptics that confirm, reject and tick (polish guide §3.8); the segment tick is Android 14's, with
 * the clock tick before it.
 */
fun android.view.View.confirm() {
    performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
}

fun android.view.View.reject() {
    performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
}

fun android.view.View.tick() {
    performHapticFeedback(
        if (android.os.Build.VERSION.SDK_INT >= 34)
            android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        else android.view.HapticFeedbackConstants.CLOCK_TICK
    )
}
