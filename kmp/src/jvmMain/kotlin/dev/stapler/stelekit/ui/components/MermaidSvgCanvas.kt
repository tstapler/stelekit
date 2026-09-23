package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM

/**
 * Draws a rendered Mermaid [svg] string on a Compose `Canvas` via Skia's [SVGDOM] parser —
 * already on the classpath through Compose Desktop/skiko, no new heavyweight dependency.
 * Silently draws nothing if [svg] fails to parse (malformed/unsupported SVG); the caller is
 * expected to have already validated [svg] came from a successful [MermaidRenderResult.Rendered].
 */
@Composable
fun MermaidSvgCanvas(svg: String, modifier: Modifier = Modifier) {
    val dom = remember(svg) {
        runCatching { SVGDOM(Data.makeFromBytes(svg.toByteArray(Charsets.UTF_8))) }.getOrNull()
    } ?: return

    // SVGDOM owns the Data it was built from (skiko's SVGDOM constructor consumes/wraps it, it
    // doesn't just hold a reference) — closing SVGDOM alone is sufficient; the Data doesn't need
    // its own separate close. Runs on every svg change (remember's key) and on disposal, so the
    // previous native object is never leaked mid-session or when this leaves composition.
    DisposableEffect(dom) {
        onDispose { dom.close() }
    }

    // BoxWithConstraints-derived widthPx already bounds width; height has no signal at all from
    // any ancestor (this can sit in a column with unbounded height), so aspectRatio derived from
    // the SVG's own viewBox is what gives the Canvas a nonzero height to measure against.
    val ratio = remember(svg) { mermaidSvgAspectRatio(svg) }
    Canvas(modifier.aspectRatio(ratio)) {
        dom.setContainerSize(size.width, size.height)
        drawIntoCanvas { canvas ->
            dom.render(canvas.nativeCanvas)
        }
    }
}
