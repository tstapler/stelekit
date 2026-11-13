package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.fitPreview
import dev.stapler.stelekit.sketch.previewAspectRatio
import dev.stapler.stelekit.sketch.sceneBounds

/** Supplies journal sketch previews; null in tests and on screens that have no graph. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell, next to LocalOpenSketchAction
val LocalSketchPreviewLoader = staticCompositionLocalOf<SketchPreviewLoader?> { null }

const val SKETCH_PREVIEW_TAG = "sketch-preview"

private const val PREVIEW_PADDING_DP = 12f
private const val PREVIEW_STROKE_DP = 2f
internal const val EMPTY_SKETCH_TEXT = "Empty sketch — tap to draw"
internal const val MISSING_SKETCH_TEXT = "Sketch not on this device yet"

/** A read-only drawing of the sketch behind [slug]; the caller makes it tappable. Reloads when that sketch is saved. */
@Composable
fun SketchPreviewBlock(slug: String, modifier: Modifier = Modifier) {
    val loader = LocalSketchPreviewLoader.current
    var reloadTick by remember(slug) { mutableIntStateOf(0) }
    if (loader != null) {
        LaunchedEffect(loader, slug) { loader.saved.collect { if (it == slug) reloadTick++ } }
    }
    val preview by produceState<SketchPreview?>(null, loader, slug, reloadTick) {
        value = loader?.load(slug) ?: SketchPreview.Missing
    }
    SketchPreviewContent(preview, modifier.testTag(SKETCH_PREVIEW_TAG))
}

@Composable
internal fun SketchPreviewContent(preview: SketchPreview?, modifier: Modifier = Modifier) {
    when (preview) {
        null -> Box(modifier.fillMaxWidth().heightIn(min = PLACEHOLDER_MIN_HEIGHT))
        SketchPreview.Missing -> PreviewNote(MISSING_SKETCH_TEXT, modifier)
        SketchPreview.Empty -> PreviewNote(EMPTY_SKETCH_TEXT, modifier)
        is SketchPreview.Drawing -> DrawingPreview(preview, modifier)
    }
}

private val PLACEHOLDER_MIN_HEIGHT = 64.dp

@Composable
private fun PreviewNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = PLACEHOLDER_MIN_HEIGHT), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun DrawingPreview(preview: SketchPreview.Drawing, modifier: Modifier = Modifier) {
    val scene = preview.document.visibleScene
    val bounds = remember(scene) { sceneBounds(scene) }
    val cache = remember { SketchRenderCache() }
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current.density
    Canvas(modifier.fillMaxWidth().aspectRatio(previewAspectRatio(bounds)).heightIn(max = PREVIEW_MAX_HEIGHT).clipToBounds()) {
        val box = bounds ?: return@Canvas
        val fit = fitPreview(box, size.width, size.height, PREVIEW_PADDING_DP * density) ?: return@Canvas
        val stroke = PREVIEW_STROKE_DP * density / fit.scale
        withTransform({
            translate(fit.originX, fit.originY)
            scale(fit.scale, fit.scale, pivot = Offset.Zero)
        }) {
            for (shape in cache.shapesFor(scene)) drawPath(shape.path, ink, style = Stroke(stroke))
        }
    }
}

private val PREVIEW_MAX_HEIGHT = 360.dp
