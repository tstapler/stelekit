// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.tools.TextEditResult
import dev.stapler.stelekit.sketch.v2.tools.TextEditSession
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Hint texts of the text overlay (UX-124): touch names the Done button and an outside tap, a keyboard names Esc. */
object TextEditorHints {
    const val TOUCH = "Tap Done or tap outside to finish"
    const val KEYBOARD = "Esc to finish"
    const val DONE = "Done"
    const val OK = "OK"

    fun forInput(hasHardwareKeyboard: Boolean): String = if (hasHardwareKeyboard) KEYBOARD else TOUCH
}

/** Where the overlay's text origin sits on screen and how large its glyphs are, at the current view (UX-20, works at zoom 2.5). */
data class TextOverlayPlacement(val origin: ScreenPx, val fontSizePx: Double) {
    companion object {
        fun of(viewport: SketchViewport, session: TextEditSession): TextOverlayPlacement =
            TextOverlayPlacement(viewport.toScreen(session.origin), TextElement.DEFAULT_FONT_SIZE * viewport.zoom)
    }
}

/**
 * In-place multi-line text editor positioned over the canvas at the transformed text origin (Story 3.4.1).
 *
 * Typing and pasting go through [session], which refuses text over the limit and the user sees the refusal dialog
 * (UX-23). Shortcut letters type into the field because it holds focus and the canvas key dispatcher is not above it
 * (UX-22). The field is lifted clear of the soft keyboard with an IME inset on the container. A tap outside the
 * field, the [TextEditorHints.DONE] button (shown only without a hardware keyboard) and Esc all call [onCommit].
 */
@Composable
fun TextEditorOverlay(
    session: TextEditSession,
    viewport: SketchViewport,
    hasHardwareKeyboard: Boolean,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember(session) { mutableStateOf(TextFieldValue(session.text, TextRange(session.text.length))) }
    var refusal by remember(session) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val placement = TextOverlayPlacement.of(viewport, session)
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val containerHeightPx = constraints.maxHeight
        val containerWidthPx = constraints.maxWidth
        LaunchedEffect(session) { focus.requestFocus() }
        Row(
            Modifier.fillMaxSize().testTag("sketch.text.scrim").pointerInput(session) { detectTapGestures { onCommit() } },
        ) {}
        Surface(
            modifier = Modifier
                .layout { measurable, c ->
                    val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
                    layout(p.width, p.height) {
                        val x = placement.origin.x.roundToInt().coerceIn(0, max(0, containerWidthPx - p.width))
                        val y = min(placement.origin.y.roundToInt(), containerHeightPx - p.height).coerceAtLeast(0)
                        p.place(IntOffset(x, y))
                    }
                }
                .widthIn(min = MIN_FIELD_WIDTH),
            color = MaterialTheme.colorScheme.surface.copy(alpha = FIELD_SURFACE_ALPHA),
        ) {
            Column(Modifier.padding(FIELD_PADDING)) {
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        when (val r = session.replace(next.text)) {
                            is TextEditResult.Accepted -> value = next
                            is TextEditResult.TooLong -> refusal = r.message
                        }
                    },
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = with(density) { placement.fontSizePx.toFloat().toSp() },
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier
                        .focusRequester(focus)
                        .testTag("sketch.text.field")
                        .onPreviewKeyEvent { e ->
                            val esc = e.key == Key.Escape && e.type == KeyEventType.KeyDown
                            if (esc) onCommit()
                            esc
                        },
                )
                Row(Modifier.background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.spacedBy(HINT_GAP), verticalAlignment = Alignment.CenterVertically) {
                    Text(TextEditorHints.forInput(hasHardwareKeyboard), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("sketch.text.hint"))
                    if (!hasHardwareKeyboard) {
                        TextButton(onClick = onCommit, modifier = Modifier.testTag("sketch.text.done")) { Text(TextEditorHints.DONE) }
                    }
                }
            }
        }
    }

    refusal?.let { message ->
        AlertDialog(
            onDismissRequest = { refusal = null },
            text = { Text(message, modifier = Modifier.testTag("sketch.text.limitMessage")) },
            confirmButton = { TextButton(onClick = { refusal = null }, modifier = Modifier.testTag("sketch.text.limitOk")) { Text(TextEditorHints.OK) } },
            modifier = Modifier.testTag("sketch.text.limitDialog"),
        )
    }
}

private val MIN_FIELD_WIDTH = 48.dp
private val FIELD_PADDING = 4.dp
private val HINT_GAP = 8.dp
private const val FIELD_SURFACE_ALPHA = 0.9f
