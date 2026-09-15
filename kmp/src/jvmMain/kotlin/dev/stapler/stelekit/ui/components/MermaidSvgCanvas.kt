package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
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

    Canvas(modifier) {
        dom.setContainerSize(size.width, size.height)
        drawIntoCanvas { canvas ->
            dom.render(canvas.nativeCanvas)
        }
    }
}
