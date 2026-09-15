package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val GENERIC_MERMAID_ACCESSIBILITY_LABEL = "Mermaid diagram — tap to view source"

/**
 * Simple line-prefix parse of mermaid's `accTitle:`/`accDescr:` directives (ux.md Surface 3) —
 * not a full mermaid grammar parse, just enough to recover the author-supplied accessible label.
 */
private fun mermaidAccessibilityLabel(sourceText: String): String {
    var accTitle: String? = null
    var accDescr: String? = null
    for (line in sourceText.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("accTitle:", ignoreCase = true) -> accTitle = trimmed.substringAfter(":").trim()
            trimmed.startsWith("accDescr:", ignoreCase = true) -> accDescr = trimmed.substringAfter(":").trim()
        }
    }
    return when {
        !accTitle.isNullOrBlank() && !accDescr.isNullOrBlank() -> "$accTitle. $accDescr"
        !accTitle.isNullOrBlank() -> accTitle
        !accDescr.isNullOrBlank() -> accDescr
        else -> GENERIC_MERMAID_ACCESSIBILITY_LABEL
    }
}

/**
 * Renders a ` ```mermaid ` fenced code block as a diagram (Task 6.1.1b), falling back to
 * [CodeFenceBlock]'s raw-text chrome — reused as-is for the loading, syntax-error, oversized-source,
 * and unsupported-platform states alike (ADR-001, ux.md Surface 1) — whenever a diagram isn't
 * available. [renderer] defaults to the platform [renderMermaid] entry point but is overridable so
 * tests can force a deterministic [MermaidRenderResult] without a live JS/WebView render.
 */
@Composable
fun MermaidBlock(
    content: String,
    onStartEditing: () -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPressSelect: (() -> Unit)? = null,
    renderer: suspend (MermaidRenderKey) -> MermaidRenderResult = ::renderMermaid,
) {
    val sourceText = remember(content) { extractCodeBody(content) }

    // Oversized/empty source is never handed to the renderer at all (REQ-8) — cheapest correct
    // behavior for a pathological or empty diagram, matching CodeFenceBlock's existing empty-body handling.
    if (sourceText.isEmpty() || sourceText.length > MAX_MERMAID_SOURCE_LENGTH) {
        CodeFenceBlock(
            content = content,
            language = "mermaid",
            onStartEditing = onStartEditing,
            modifier = modifier,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        MermaidRenderGate(
            content = content,
            sourceText = sourceText,
            widthPx = constraints.maxWidth,
            onStartEditing = onStartEditing,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
            renderer = renderer,
        )
    }
}

/** Drives the cache lookup / render / result dispatch once a layout width is known. */
@Composable
private fun MermaidRenderGate(
    content: String,
    sourceText: String,
    widthPx: Int,
    onStartEditing: () -> Unit,
    isInSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPressSelect: (() -> Unit)?,
    renderer: suspend (MermaidRenderKey) -> MermaidRenderResult,
) {
    val theme = currentThemeFingerprint()
    val key = remember(sourceText, theme, widthPx) { MermaidRenderKey(sourceText, theme, widthPx) }
    var result by remember(key) { mutableStateOf<MermaidRenderResult?>(mermaidRenderCache.get(key)) }
    // rememberUpdatedState (not a LaunchedEffect key) so a caller passing an unstable renderer
    // lambda each recomposition doesn't spuriously restart this effect and trigger an extra render.
    val currentRenderer by rememberUpdatedState(renderer)

    LaunchedEffect(key) {
        if (result == null) {
            val rendered = currentRenderer(key)
            if (rendered is MermaidRenderResult.Rendered) {
                mermaidRenderCache.put(key, rendered)
            }
            result = rendered
        }
    }

    val rendered = result
    if (rendered is MermaidRenderResult.Rendered) {
        MermaidRenderedCard(
            result = rendered,
            accessibilityLabel = remember(sourceText) { mermaidAccessibilityLabel(sourceText) },
            onStartEditing = onStartEditing,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // Failed, UnsupportedPlatform, and the not-yet-rendered (null) loading state all share
        // one visual — the raw fallback (ux.md Surface 1: "this IS the loading state").
        CodeFenceBlock(
            content = content,
            language = "mermaid",
            onStartEditing = onStartEditing,
            isInSelectionMode = isInSelectionMode,
            onToggleSelect = onToggleSelect,
            onLongPressSelect = onLongPressSelect,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The rendered-diagram card (1a): same rounded `surfaceVariant` chrome, padding, and "mermaid"
 * language label placement as [CodeFenceBlock] (ux.md Surface 1 AC7), with the diagram itself
 * in place of the monospace text, plus keyboard activation and an accessible label.
 *
 * Deliberately does *not* stack an explicit `Modifier.focusable()` on top of `Modifier.clickable`:
 * `clickable` (`AbstractClickableNode`) already delegates to its own internal `FocusableNode`, so
 * an additional `.focusable()` here would add a *second* focus target on the same element —
 * confirmed empirically (`MermaidBlockFocusTest`) to cause exactly the keyboard "double-tab-stop"
 * ux.md's Surface 2 explicitly calls out as the regression to avoid. `.onKeyEvent` is kept
 * explicit (rather than relying solely on `clickable`'s own Enter/Space handling) so Enter/Space
 * activation is guaranteed regardless of the platform diagram surface underneath.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MermaidRenderedCard(
    result: MermaidRenderResult.Rendered,
    accessibilityLabel: String,
    onStartEditing: () -> Unit,
    isInSelectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onLongPressSelect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = accessibilityLabel
                role = Role.Button
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.Spacebar -> {
                        if (isInSelectionMode) onToggleSelect() else onStartEditing()
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                onLongClick = onLongPressSelect,
                onClick = { if (isInSelectionMode) onToggleSelect() else onStartEditing() },
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "mermaid",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
            MermaidDiagramSurface(result = result, modifier = Modifier.fillMaxWidth())
        }
    }
}
