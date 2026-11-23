package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.stapler.stelekit.ui.components.SketchEmbedRenderer
import dev.stapler.stelekit.sketch.previewAspectRatio
import dev.stapler.stelekit.sketch.sceneBounds

/** Supplies journal sketch previews; null in tests and on screens that have no graph. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell, next to LocalOpenSketchAction
val LocalSketchPreviewLoader = staticCompositionLocalOf<SketchPreviewLoader?> { null }

const val SKETCH_PREVIEW_TAG = "sketch-preview"
const val SKETCH_PREVIEW_NOT_SHOWN_TAG = "sketch-preview-not-shown"

private const val PREVIEW_PADDING_DP = 12f
private const val PREVIEW_STROKE_DP = 2f
internal const val EMPTY_SKETCH_TEXT = "Empty sketch — tap to draw"
internal const val MISSING_SKETCH_TEXT = "Sketch not found"

/** Opens the sketch list so the user can pick another sketch for an embed whose sketch is gone; null where there is no list. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell, next to LocalOpenSketchAction
val LocalChooseSketchAction = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * A read-only drawing of the sketch behind [slug]; the caller makes it tappable. Reloads when that sketch is saved.
 * [actions] are what the missing-sketch and storage-unavailable states offer (UX-61, UX-135).
 */
@Composable
fun SketchPreviewBlock(slug: String, modifier: Modifier = Modifier, actions: SketchEmbedActions = SketchEmbedActions()) {
    val loader = LocalSketchPreviewLoader.current
    var reloadTick by remember(slug) { mutableIntStateOf(0) }
    if (loader != null) {
        LaunchedEffect(loader, slug) { loader.saved.collect { if (it == slug) reloadTick++ } }
    }
    val preview by produceState<SketchPreview?>(null, loader, slug, reloadTick) {
        value = loader?.load(slug) ?: SketchPreview.Missing
    }
    SketchPreviewContent(preview, modifier.testTag(SKETCH_PREVIEW_TAG), actions.copy(onChooseAnother = actions.onChooseAnother ?: LocalChooseSketchAction.current))
}

/** What `LocalSketchEmbedRenderer` is set to: block UI draws sketch embeds through [SketchPreviewBlock]. */
val SketchEmbedPreviewRenderer: SketchEmbedRenderer = { slug, modifier, onRemoveEmbed, onOpenPage ->
    SketchPreviewBlock(slug = slug, modifier = modifier, actions = SketchEmbedActions(onRemoveEmbed = onRemoveEmbed, onOpenPage = onOpenPage))
}

@Composable
internal fun SketchPreviewContent(
    preview: SketchPreview?,
    modifier: Modifier = Modifier,
    actions: SketchEmbedActions = SketchEmbedActions(),
    modern: ModernPreviewEnvironment? = null,
) {
    SketchRenderDispatcher(preview, actions, modern, modifier)
}

@Composable
internal fun ClassicPreviewContent(preview: SketchPreview.Drawing, modifier: Modifier = Modifier) {
    Column(modifier) {
        DrawingPreview(preview)
        if (preview.notShownCount > 0) {
            Text(
                notShownText(preview.notShownCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).testTag(SKETCH_PREVIEW_NOT_SHOWN_TAG),
            )
        }
    }
}

internal fun notShownText(count: Int): String = if (count == 1) "1 element not shown in this preview" else "$count elements not shown in this preview"

internal val PLACEHOLDER_MIN_HEIGHT = 64.dp

@Composable
internal fun PreviewNote(text: String, modifier: Modifier = Modifier) {
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
