package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

/**
 * Renders a heading block (H1–H6) with appropriate typography.
 * The leading `#` markers are stripped from [content] before rendering.
 * Inline markdown (bold, italic, wiki-links) is supported via [parseMarkdownWithStyling].
 */
@Composable
internal fun HeadingBlock(
    content: String,
    level: Int,
    linkColor: Color,
    onStartEditing: () -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
) {
    val strippedContent = content.trimStart('#').trimStart()
    val textColor = MaterialTheme.colorScheme.onBackground
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

    val textStyle = when (level) {
        1 -> MaterialTheme.typography.displaySmall
        2 -> MaterialTheme.typography.headlineLarge
        3 -> MaterialTheme.typography.headlineMedium
        4 -> MaterialTheme.typography.headlineSmall
        5 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }

    val annotatedString = remember(strippedContent, linkColor, textColor, codeBg) {
        parseMarkdownWithStyling(
            text = strippedContent,
            linkColor = linkColor,
            textColor = textColor,
            codeBackground = codeBg,
        )
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Shared tap-dispatch logic, reused as the onLongPress fallback below so that on
    // platforms where onLongPressSelect is null (Android -- see useLongPressForDrag), a
    // held/slow tap still resolves as an ordinary tap instead of being silently swallowed:
    // detectTapGestures treats any gesture that outlasts the long-press timeout as "handled"
    // by onLongPress once onLongPress is non-null, regardless of what that lambda does, so
    // onTap would never fire for that gesture without this fallback.
    fun dispatchTap(tapOffset: Offset) {
        if (isInSelectionMode) {
            onToggleSelect()
            return
        }
        val layout = textLayoutResult ?: run { onStartEditing(); return }
        val offset = layout.getOffsetForPosition(tapOffset)
        val wikiLink = annotatedString.getStringAnnotations(WIKI_LINK_TAG, offset, offset).firstOrNull()
        if (wikiLink != null) {
            onLinkClick(wikiLink.item)
        } else {
            onStartEditing()
        }
    }

    BasicText(
        text = annotatedString,
        style = textStyle.copy(color = textColor),
        onTextLayout = { textLayoutResult = it },
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(annotatedString, isInSelectionMode) {
                detectTapGestures(
                    onLongPress = { tapOffset -> onLongPressSelect?.invoke() ?: dispatchTap(tapOffset) },
                    onTap = { tapOffset -> dispatchTap(tapOffset) }
                )
            }
    )
}
