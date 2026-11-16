package gg.seven.vault

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import gg.seven.kit.ConnectedChoice
import gg.seven.kit.Space
import gg.seven.vault.core.*

val VaultCredentialKind.title: String
    get() = credentialKindTitle(this)

fun VaultCredentialKind.valid(value: String) = runCatching { credentialKindValidate(this, value) }.isSuccess

/** iOS `CredentialKindPicker`: a connected button group for the three unlock methods. */
@Composable
fun CredentialKindPicker(kind: VaultCredentialKind, onChange: (VaultCredentialKind) -> Unit) {
    ConnectedChoice(VaultCredentialKind.entries.map { it to it.title }, kind, onSelect = onChange)
}

/** iOS `CredentialInput`: a secret field (digits for a PIN), or the pattern pad. */
@Composable
fun CredentialInput(kind: VaultCredentialKind, value: String, label: String, tag: String, onChange: (String) -> Unit) {
    if (kind == VaultCredentialKind.PATTERN) PatternPad(value, label, tag, onChange)
    else {
        var shown by rememberSaveable { mutableStateOf(false) }
        OutlinedTextField(
            value,
            onChange,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentType = androidx.compose.ui.autofill.ContentType.Password },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (kind == VaultCredentialKind.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
                    autoCorrectEnabled = false,
                ),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (shown) "Hide $label" else "Show $label")
                }
            },
        )
    }
}

/**
 * iOS `PatternPad`: a 5×5 grid. Draw one continuous path or tap dots in order; the core fills in
 * crossed dots (`pattern_append`) and stores the canonical form. The pad keeps its touches, so
 * drawing never scrolls the form. Each dot is a TalkBack button with its row and column.
 */
@Composable
fun PatternPad(value: String, label: String, tag: String, onChange: (String) -> Unit) {
    val path = remember(value) { patternDecode(value)?.map { it } ?: emptyList() }
    val view = LocalView.current
    val current by rememberUpdatedState(path)
    val change by rememberUpdatedState(onChange)
    // The gesture keeps its own copy of the path, since `value` only catches up on recomposition.
    var working = path
    fun append(dot: Int, base: List<Int> = current) {
        if (dot in base) return
        working = patternAppend(dot, base)
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        change(patternEncode(working))
    }
    val ink = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { change("") }, modifier = Modifier.testTag("$tag.clear")) { Text("Clear") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 360.dp).align(Alignment.CenterHorizontally).aspectRatio(1f)) {
            val side = constraints.maxWidth.toFloat()
            val step = side / 5
            fun center(dot: Int) = Offset((dot % 5 + 0.5f) * step, (dot / 5 + 0.5f) * step)
            fun hit(point: Offset) {
                if (point.x < 0 || point.y < 0 || point.x >= side || point.y >= side) return
                val x = (point.x / step).toInt()
                val y = (point.y / step).toInt()
                val d = point - center(y * 5 + x)
                if (d.x * d.x + d.y * d.y <= (step * 0.42f) * (step * 0.42f)) append(y * 5 + x, working)
            }
            Canvas(
                Modifier.fillMaxSize()
                    .clearAndSetSemantics {}
                    .pointerInput(step) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            working = current
                            var started = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!pointer.pressed) {
                                    // A tap without movement selects the dot under it.
                                    if (!started) hit(down.position)
                                    pointer.consume()
                                    break
                                }
                                if (!started && (pointer.position - down.position).getDistance() > 4.dp.toPx()) {
                                    started = true
                                    working = emptyList()
                                    change("")
                                    hit(down.position)
                                }
                                if (started) hit(pointer.position)
                                pointer.consume()
                            }
                        }
                    }
            ) {
                if (path.size > 1) {
                    val line = Path().apply {
                        moveTo(center(path[0]).x, center(path[0]).y)
                        path.drop(1).forEach { lineTo(center(it).x, center(it).y) }
                    }
                    drawPath(line, ink.copy(alpha = 0.55f), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                for (dot in 0 until 25) drawCircle(if (dot in path) ink else idle.copy(alpha = 0.6f), radius = 7.5.dp.toPx(), center = center(dot))
            }
            // Accessible dots: invisible buttons over each position, in reading order.
            val size = with(androidx.compose.ui.platform.LocalDensity.current) { step.toDp() }
            for (dot in 0 until 25) {
                Box(
                    Modifier.offset(size * (dot % 5), size * (dot / 5))
                        .size(size)
                        .testTag("$tag.dot.$dot")
                        .semantics {
                            role = Role.Button
                            contentDescription = "Row ${dot / 5 + 1}, column ${dot % 5 + 1}"
                            if (dot in path) stateDescription = "Selected"
                            onClick { append(dot); true }
                        }
                )
            }
        }
        Text(
            "${path.size} dots · draw a path or tap dots in order",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
