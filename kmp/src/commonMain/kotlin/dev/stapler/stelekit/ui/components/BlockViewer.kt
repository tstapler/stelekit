package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.ui.theme.StelekitTheme

/**
 * Read-only rendered view of a block's content with clickable wiki links,
 * tags, markdown links, and plain URLs.
 */
@Composable
internal fun BlockViewer(
    content: String,
    textColor: Color,
    linkColor: Color,
    resolvedRefs: Map<String, String>,
    onLinkClick: (String) -> Unit,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
    isShiftDown: Boolean = false,
    onShiftClick: () -> Unit = {},
    suggestionMatcher: AhoCorasickMatcher? = null,
    onSuggestionClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
    onSuggestionRightClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    WikiLinkText(
        text = content,
        textColor = if (textColor != Color.Unspecified) textColor else MaterialTheme.colorScheme.onBackground,
        linkColor = linkColor,
        resolvedRefs = resolvedRefs,
        onLinkClick = onLinkClick,
        onUrlClick = { url ->
            try {
                uriHandler.openUri(url)
            } catch (e: Exception) {
                // Ignore if can't open URL
            }
        },
        onClick = if (isShiftDown) onShiftClick else onStartEditing,
        modifier = modifier.fillMaxWidth(),
        suggestionMatcher = suggestionMatcher,
        onSuggestionClick = onSuggestionClick,
        onSuggestionRightClick = onSuggestionRightClick,
    )
}

/**
 * Renders text with clickable wiki links [[Page Name]], #tags,
 * markdown links, and auto-detected URLs.
 */
@Composable
fun WikiLinkText(
    text: String,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    resolvedRefs: Map<String, String> = emptyMap(),
    onLinkClick: (String) -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    onClick: () -> Unit = {},
    suggestionMatcher: AhoCorasickMatcher? = null,
    onSuggestionClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
    onSuggestionRightClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
) {
    val blockRefBg = StelekitTheme.colors.blockRefBackground
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val annotatedString = remember(text, linkColor, textColor, resolvedRefs, blockRefBg, codeBg, suggestionMatcher) {
        parseMarkdownWithStyling(
            text = text,
            linkColor = linkColor,
            textColor = textColor,
            blockRefBackgroundColor = blockRefBg,
            resolvedRefs = resolvedRefs,
            codeBackground = codeBg,
            suggestionMatcher = suggestionMatcher,
            suggestionColor = linkColor,
        )
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Helper: decode a PAGE_SUGGESTION annotation value into triple or null
    fun decodeSuggestionAnnotation(item: String): Triple<String, Int, Int>? {
        val parts = item.split("|")
        if (parts.size != 3) return null
        val contentStart = parts[1].toIntOrNull() ?: return null
        val contentEnd = parts[2].toIntOrNull() ?: return null
        return Triple(parts[0], contentStart, contentEnd)
    }

    BasicText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        onTextLayout = { textLayoutResult = it },
        modifier = modifier
            .padding(vertical = 4.dp)
            .pointerInput(annotatedString) {
                detectTapGestures(
                    onLongPress = { tapOffset ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(tapOffset)
                        val suggestion = annotatedString
                            .getStringAnnotations(PAGE_SUGGESTION_TAG, offset, offset)
                            .firstOrNull()
                        if (suggestion != null) {
                            val decoded = decodeSuggestionAnnotation(suggestion.item)
                            if (decoded != null) {
                                onSuggestionRightClick(decoded.first, decoded.second, decoded.third)
                                return@detectTapGestures
                            }
                        }
                    },
                    onTap = { tapOffset ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(tapOffset)
                        val annotations = annotatedString.getStringAnnotations(start = offset, end = offset)

                        // Priority: Wiki Link > Tag > Page Suggestion > Markdown Link > URL > Image > Default
                        val wikiLink = annotations.firstOrNull { it.tag == WIKI_LINK_TAG }
                        val tag = annotations.firstOrNull { it.tag == TAG_TAG }
                        val suggestion = annotations.firstOrNull { it.tag == PAGE_SUGGESTION_TAG }
                        val link = annotations.firstOrNull { it.tag == "link" }
                        val url = annotations.firstOrNull { it.tag == "url" }
                        val image = annotations.firstOrNull { it.tag == "image" }

                        when {
                            wikiLink != null -> onLinkClick(wikiLink.item)
                            tag != null -> onLinkClick(tag.item)
                            suggestion != null -> {
                                val decoded = decodeSuggestionAnnotation(suggestion.item)
                                if (decoded != null) {
                                    onSuggestionClick(decoded.first, decoded.second, decoded.third)
                                }
                            }
                            link != null -> onUrlClick(link.item)
                            url != null -> onUrlClick(url.item)
                            image != null -> onUrlClick(image.item)
                            else -> onClick()
                        }
                    }
                )
            }
            .pointerInput("rightClick", annotatedString) {
                // Desktop secondary-button (right-click) detection.
                // Uses PointerEventPass.Main without consuming events so the tap
                // handler above is unaffected. The menu is shown on RELEASE so the
                // Popup's onDismissRequest doesn't fire on the same event cycle.
                awaitEachGesture {
                    // Wait for a secondary (right) button press
                    val press = awaitPointerEvent(PointerEventPass.Main)
                    if (press.type != PointerEventType.Press || !press.buttons.isSecondaryPressed) return@awaitEachGesture
                    val pressPos = press.changes.firstOrNull()?.position ?: return@awaitEachGesture

                    // Wait for the matching release
                    var releasePos = pressPos
                    while (true) {
                        val next = awaitPointerEvent(PointerEventPass.Main)
                        if (next.type == PointerEventType.Release) {
                            releasePos = next.changes.firstOrNull()?.position ?: pressPos
                            break
                        }
                    }

                    val layout = textLayoutResult ?: return@awaitEachGesture
                    val charOffset = layout.getOffsetForPosition(releasePos)
                    val suggestion = annotatedString
                        .getStringAnnotations(PAGE_SUGGESTION_TAG, charOffset, charOffset)
                        .firstOrNull()
                    if (suggestion != null) {
                        val decoded = decodeSuggestionAnnotation(suggestion.item)
                        if (decoded != null) {
                            onSuggestionRightClick(decoded.first, decoded.second, decoded.third)
                        }
                    }
                }
            }
    )
}
