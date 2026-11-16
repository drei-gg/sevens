package gg.seven.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import gg.seven.kit.Space
import gg.seven.kit.numeric
import gg.seven.qr.core.Level
import gg.seven.qr.core.Signal

/*
 * QR's own visuals, the counterparts of QRUI/Shared/Visuals.swift and the verdict views. Status
 * colours travel with an icon and a word: the colour is on the icon, the word stays the primary
 * text colour (polish guide §3.1).
 */

/** The status colours SevenKit's scheme doesn't carry yet (iOS Tokens.caution/danger/muted). */
object QrColors {
    /** Tokens.caution: amber that passes 4.5:1 on paper and card in both themes. */
    val caution: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF2A33A) else Color(0xFF8A4A00)

    /** Tokens.danger, which the scheme also uses as `error`. */
    val danger: Color
        @Composable get() = MaterialTheme.colorScheme.error

    val muted: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

data class VerdictStyle(val filled: ImageVector, val outlined: ImageVector)

object VerdictIcons {
    fun style(level: Level): VerdictStyle =
        when (level) {
            Level.NORMAL -> VerdictStyle(Icons.Rounded.CheckCircle, Icons.Rounded.CheckCircleOutline)
            Level.CAREFUL -> VerdictStyle(Icons.Rounded.Warning, Icons.Rounded.WarningAmber)
            Level.LIKELY_SCAM -> VerdictStyle(Icons.Rounded.Dangerous, Icons.Rounded.Dangerous)
        }

    @Composable
    fun color(level: Level): Color =
        when (level) {
            Level.NORMAL -> QrColors.muted
            Level.CAREFUL -> QrColors.caution
            Level.LIKELY_SCAM -> QrColors.danger
        }

    /** A payload type's symbol: History's row tiles and Make's kinds use the same set. */
    fun type(typeId: String): ImageVector =
        when (typeId) {
            "link", "appLink" -> Icons.Rounded.Link
            "payment" -> Icons.Rounded.CreditCard
            "crypto" -> Icons.Rounded.CurrencyBitcoin
            "wifi" -> Icons.Rounded.Wifi
            "contact" -> Icons.Rounded.AccountCircle
            "event" -> Icons.Rounded.Event
            "phone" -> Icons.Rounded.Phone
            "sms" -> Icons.Rounded.Sms
            "email" -> Icons.Rounded.Email
            "geo" -> Icons.Rounded.Place
            "text" -> Icons.AutoMirrored.Rounded.Notes
            else -> Icons.Rounded.QrCode2
        }
}

/** A card on the paper background (iOS `.sevenCard()`): the card colour, the medium shape. */
@Composable
fun QrCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m), content = content)
    }
}

/** A titled card (iOS ResultCard): the title in the caption role, as a heading. */
@Composable
fun ResultCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    QrCard(modifier) {
        title?.let { Caption(it, Modifier.semantics { heading() }) }
        content()
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = QrColors.muted) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A label above a value. Values are selectable: scanned text is something people compare and copy. */
@Composable
fun ResultField(label: String, value: String, monospaced: Boolean = false, muted: Boolean = false) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Caption(label)
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge.let { if (monospaced) it.copy(fontFamily = FontFamily.Monospace) else it },
                color = if (muted) QrColors.muted else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A heading-sized value at the top of a payload card (the domain, payee, network, name). */
@Composable
fun PayloadTitle(text: String) {
    SelectionContainer {
        Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
    }
}

/**
 * The verdict at the top of a result, stated big and plainly: a large status symbol, the verdict
 * word as the screen's hero, the payload type under it. One accessibility node whose description is
 * the whole verdict as a sentence.
 */
@Composable
fun VerdictHeader(level: Level, typeName: String, signals: List<Signal>, modifier: Modifier = Modifier, caption: String = typeName) {
    val sentence = QrText.accessibilitySentence(level, typeName, signals)
    Surface(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = sentence
            heading()
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(vertical = Space.l, horizontal = Space.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(VerdictIcons.style(level).filled, null, Modifier.size(64.dp).padding(bottom = Space.xs), tint = VerdictIcons.color(level))
            Text(QrText.verdict(level), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Caption(caption, Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        }
    }
}

/** A small verdict, icon and word, for rows. */
@Composable
fun VerdictLabel(level: Level, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Icon(VerdictIcons.style(level).outlined, null, Modifier.size(20.dp), tint = VerdictIcons.color(level))
        Text(QrText.verdict(level), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A rounded square holding a type's symbol: the leading tile of a History row. */
@Composable
fun TypeTile(icon: ImageVector) {
    Surface(Modifier.size(56.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null) }
    }
}

/** A labelled number on a card: History's summary tiles. */
@Composable
fun StatTile(title: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier.semantics(mergeDescendants = true) {}, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(Space.ms).heightIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(icon, null, Modifier.size(16.dp), tint = QrColors.muted)
                Caption(title)
            }
            Text(value, style = MaterialTheme.typography.titleLarge.numeric())
        }
    }
}

/**
 * The scanner's frame: four rounded corner brackets. Over the camera they are white with a dark
 * keyline under them, so they read on any picture; on paper (no camera) they are ink.
 */
@Composable
fun ViewfinderFrame(onCamera: Boolean, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.aspectRatio(1f)) {
        val side = size.minDimension
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val length = side * 0.2f
        val r = minOf(side * 0.08f, length)
        val path = Path()
        fun corner(sx: Float, sy: Float, cx: Float, cy: Float, ex: Float, ey: Float, startX: Float, startY: Float, endX: Float, endY: Float) {
            path.moveTo(startX, startY)
            path.lineTo(sx, sy)
            path.quadraticTo(cx, cy, ex, ey)
            path.lineTo(endX, endY)
        }
        val right = left + side
        val bottom = top + side
        corner(left, top + r, left, top, left + r, top, left, top + length, left + length, top)
        corner(right - r, top, right, top, right, top + r, right - length, top, right, top + length)
        corner(right, bottom - r, right, bottom, right - r, bottom, right, bottom - length, right - length, bottom)
        corner(left + r, bottom, left, bottom, left, bottom - r, left + length, bottom, left, bottom - length)
        val stroke = { width: Float -> Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        if (onCamera) {
            drawPath(path, Color.Black.copy(alpha = 0.45f), style = stroke(9.dp.toPx()))
            drawPath(path, Color.White, style = stroke(5.dp.toPx()))
        } else drawPath(path, ink, style = stroke(5.dp.toPx()))
    }
}

/** Content column with the readable width cap (polish guide §5.4). */
@Composable
fun ReadableColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 672.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.l), content = content)
    }
}

val PillShape = RoundedCornerShape(50)

/** "Task finished" (polish guide §3.8): CONFIRM on Android 11+, a plain tap before. */
fun android.view.View.confirmHaptic() {
    performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
}
