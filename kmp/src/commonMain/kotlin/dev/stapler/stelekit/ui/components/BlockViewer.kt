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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
    suggestionMatcher: AhoCorasickMatcher? = null,
    onSuggestionClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
    onSuggestionRightClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
    onUrlRightClick: ((String) -> Unit)? = null,
    /** Page names in the local DB; used for FR-14 cross-section link rendering. */
    localPageNames: Set<String> = emptySet(),
    /** When true, wikilinks absent from [localPageNames] render as "?" badges. */
    hasSectionFilter: Boolean = false,
    /** Called when the user taps a cross-section unavailable link (FR-14). */
    onUnavailableLinkTap: () -> Unit = {},
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignore if can't open URL
            }
        },
        onClick = if (isShiftDown) onShiftClick else onStartEditing,
        modifier = modifier.fillMaxWidth(),
        isInSelectionMode = isInSelectionMode,
        onToggleSelect = onToggleSelect,
        onLongPressSelect = onLongPressSelect,
        suggestionMatcher = suggestionMatcher,
        onSuggestionClick = onSuggestionClick,
        onSuggestionRightClick = onSuggestionRightClick,
        onUrlRightClick = onUrlRightClick,
        localPageNames = localPageNames,
        hasSectionFilter = hasSectionFilter,
        onUnavailableLinkTap = onUnavailableLinkTap,
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
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    /** Invoked on a genuine long-press (no movement) that isn't over a suggestion span — enters row selection mode. Null suppresses this (e.g. platforms where long-press starts a drag instead). */
    onLongPressSelect: (() -> Unit)? = null,
    suggestionMatcher: AhoCorasickMatcher? = null,
    onSuggestionClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
    onSuggestionRightClick: (canonicalName: String, contentStart: Int, contentEnd: Int) -> Unit = { _, _, _ -> },
    onUrlRightClick: ((String) -> Unit)? = null,
    /** Page names in the local DB; used for FR-14 cross-section link rendering. */
    localPageNames: Set<String> = emptySet(),
    /** When true, wikilinks absent from [localPageNames] render as "?" badges. */
    hasSectionFilter: Boolean = false,
    /** Called when the user taps a cross-section unavailable link (FR-14). */
    onUnavailableLinkTap: () -> Unit = {},
) {
    val blockRefBg = StelekitTheme.colors.blockRefBackground
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    // Compute suggestion spans off the composition thread. Each block runs its own
    // produceState coroutine, so work spreads across frames rather than spiking
    // all visible blocks simultaneously when the matcher changes.
    val suggestionSpans by produceState(
        initialValue = emptyList<AhoCorasickMatcher.MatchSpan>(),
        key1 = text,
        key2 = suggestionMatcher,
    ) {
        value = withContext(Dispatchers.Default) {
            extractSuggestions(text, suggestionMatcher)
        }
    }
    val annotatedString = remember(text, linkColor, textColor, resolvedRefs, blockRefBg, codeBg, suggestionSpans, localPageNames, hasSectionFilter) {
        parseMarkdownWithStyling(
            text = text,
            linkColor = linkColor,
            textColor = textColor,
            blockRefBackgroundColor = blockRefBg,
            resolvedRefs = resolvedRefs,
            codeBackground = codeBg,
            suggestionSpans = suggestionSpans,
            suggestionColor = linkColor,
            localPageNames = localPageNames,
            hasSectionFilter = hasSectionFilter,
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

    // Shared tap-dispatch logic (selection toggle > annotation priority chain > onClick).
    // Used by onTap directly, and by onLongPress as a fallback when neither a suggestion
    // span nor a genuine row-level long-press applies (see onLongPress below), so that on
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
        val layout = textLayoutResult ?: run { onClick(); return }
        val offset = layout.getOffsetForPosition(tapOffset)
        val annotations = annotatedString.getStringAnnotations(start = offset, end = offset)

        // Priority: Wiki Link > Cross-section Unavailable > Tag > Page Suggestion > Markdown Link > URL > Image > Default
        val wikiLink = annotations.firstOrNull { it.tag == WIKI_LINK_TAG }
        val crossSectionUnavailable = annotations.firstOrNull { it.tag == CROSS_SECTION_UNAVAILABLE_TAG }
        val tag = annotations.firstOrNull { it.tag == TAG_TAG }
        val suggestion = annotations.firstOrNull { it.tag == PAGE_SUGGESTION_TAG }
        val link = annotations.firstOrNull { it.tag == "link" }
        val url = annotations.firstOrNull { it.tag == "url" }
        val image = annotations.firstOrNull { it.tag == "image" }

        when {
            wikiLink != null -> onLinkClick(wikiLink.item)
            // FR-14: unavailable cross-section link — show tooltip, do NOT navigate
            crossSectionUnavailable != null -> onUnavailableLinkTap()
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

    BasicText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        onTextLayout = { textLayoutResult = it },
        modifier = modifier
            .padding(vertical = 4.dp)
            // isInSelectionMode is a key (not just annotatedString) so this coroutine
            // relaunches -- and picks up the current isInSelectionMode/onToggleSelect/
            // onLongPressSelect closures -- whenever selection mode toggles without the
            // block's own content changing. Without this, a long-press that enters
            // selection mode leaves the running coroutine's onTap closure pinned to the
            // stale isInSelectionMode=false it captured at launch, so the very next tap
            // (meant to toggle selection) falls through to onClick/link dispatch instead --
            // reintroducing the tap-vs-selection race this fix exists to eliminate.
            .pointerInput(annotatedString, isInSelectionMode) {
                detectTapGestures(
                    onLongPress = { tapOffset ->
                        val layout = textLayoutResult
                        val suggestion = if (layout != null) {
                            val offset = layout.getOffsetForPosition(tapOffset)
                            annotatedString.getStringAnnotations(PAGE_SUGGESTION_TAG, offset, offset).firstOrNull()
                        } else {
                            null
                        }
                        if (suggestion != null) {
                            val decoded = decodeSuggestionAnnotation(suggestion.item)
                            if (decoded != null) {
                                onSuggestionRightClick(decoded.first, decoded.second, decoded.third)
                                return@detectTapGestures
                            }
                        }
                        // Not over a suggestion span: either a genuine row-level long-press
                        // (enters selection mode) or, on platforms that suppress row-level
                        // long-press-to-select (Android), this must still resolve as an
                        // ordinary tap so a held/slow tap doesn't silently do nothing.
                        onLongPressSelect?.invoke() ?: dispatchTap(tapOffset)
                    },
                    onTap = { tapOffset -> dispatchTap(tapOffset) }
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
                    } else if (onUrlRightClick != null) {
                        val urlAnnotation = annotatedString
                            .getStringAnnotations("url", charOffset, charOffset).firstOrNull()
                            ?: annotatedString.getStringAnnotations("link", charOffset, charOffset).firstOrNull()
                        if (urlAnnotation != null) {
                            onUrlRightClick(urlAnnotation.item)
                        }
                    }
                }
            }
    )
}
