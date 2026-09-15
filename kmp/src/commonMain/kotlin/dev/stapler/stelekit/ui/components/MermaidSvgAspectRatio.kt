package dev.stapler.stelekit.ui.components

/** Fallback when [mermaidSvgAspectRatio] can't find usable dimensions — a bit wider than tall. */
const val MERMAID_DEFAULT_ASPECT_RATIO = 16f / 9f

private val VIEW_BOX_REGEX = Regex("""viewBox\s*=\s*"\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)\s+([\d.]+)\s*"""")
private val WIDTH_ATTR_REGEX = Regex("""\bwidth\s*=\s*"([\d.]+)(?:px)?"""")
private val HEIGHT_ATTR_REGEX = Regex("""\bheight\s*=\s*"([\d.]+)(?:px)?"""")

/**
 * Derives a diagram's width/height ratio from a rendered `<svg>` string's `viewBox` (preferred,
 * since mermaid.js always emits one) or its `width`/`height` attributes. Both the JVM Skia canvas
 * and the Android WebView host size themselves via `Modifier.aspectRatio(...)` using this value —
 * without it, an unbounded-height parent (e.g. the message list's `Column`) measures the diagram
 * surface at zero height even though the render itself succeeded.
 *
 * This is layout sizing, not a security boundary, so a plain regex is enough — no need for a full
 * XML parse. Falls back to [MERMAID_DEFAULT_ASPECT_RATIO] when [svg] has no parseable positive
 * width/height (malformed SVG, missing attributes) rather than crashing or producing a zero/NaN
 * ratio that would break `aspectRatio`'s own precondition.
 */
fun mermaidSvgAspectRatio(svg: String): Float {
    val viewBoxMatch = VIEW_BOX_REGEX.find(svg)
    val width = viewBoxMatch?.groupValues?.get(1)?.toFloatOrNull()
        ?: WIDTH_ATTR_REGEX.find(svg)?.groupValues?.get(1)?.toFloatOrNull()
    val height = viewBoxMatch?.groupValues?.get(2)?.toFloatOrNull()
        ?: HEIGHT_ATTR_REGEX.find(svg)?.groupValues?.get(1)?.toFloatOrNull()

    if (width == null || height == null || width <= 0f || height <= 0f) return MERMAID_DEFAULT_ASPECT_RATIO
    return width / height
}
