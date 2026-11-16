// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.rememberSketchTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter
import dev.stapler.stelekit.ui.sketch.v2.SketchFontState
import dev.stapler.stelekit.ui.sketch.v2.rememberSketchFontState

/** Which renderer draws a sheet (Story 6.1.1): the v1 preview, the v2 draw list, or neither because a newer app wrote it. */
enum class SketchRenderRoute {
    Classic, Modern, Newer;

    companion object {
        /** The ONE dispatch on a sheet's `schema_version` (the DB mirror); anything above 2 is newer than this build. */
        fun of(schemaVersion: Long): SketchRenderRoute = when {
            schemaVersion > SheetPaths.SCHEMA_V2 -> Newer
            schemaVersion == SheetPaths.SCHEMA_V2 -> Modern
            else -> Classic
        }
    }
}

/** What an embed's non-drawing states offer; an action that is null is not shown (a host with no sketch list or no block cannot do it). */
data class SketchEmbedActions(
    /** Removes the embed text from its block and nothing else (UX-61). */
    val onRemoveEmbed: (() -> Unit)? = null,
    /** Opens the sketch list to pick another sketch (UX-61). */
    val onChooseAnother: (() -> Unit)? = null,
    /** Opens the sketch page; the storage-unavailable placeholder's [Open page] (UX-135). */
    val onOpenPage: (() -> Unit)? = null,
)

/** What drawing a v2 sheet needs: a text measurer for layout and the painter's text and image resources. */
class ModernPreviewEnvironment(val metrics: TextMetricsProvider, val resources: PainterResources)

const val SKETCH_PREVIEW_NEWER_TAG = "sketch-preview-newer"
const val SKETCH_PREVIEW_UNAVAILABLE_TAG = "sketch-preview-unavailable"
const val SKETCH_PREVIEW_MODERN_TAG = "sketch-preview-modern"
const val SKETCH_REMOVE_EMBED_TAG = "sketch-embed-remove"
const val SKETCH_CHOOSE_ANOTHER_TAG = "sketch-embed-choose-another"
const val SKETCH_OPEN_PAGE_TAG = "sketch-embed-open-page"

internal const val NEWER_SCHEMA_TEXT = "Update SteleKit to view"
internal const val STORAGE_UNAVAILABLE_TEXT = "New-editor sketch. Open it in the desktop or Android app."
internal const val REMOVE_EMBED_LABEL = "Remove embed"
internal const val CHOOSE_ANOTHER_LABEL = "Choose another"
internal const val OPEN_PAGE_LABEL = "Open page"

private const val MODERN_PADDING_DP = 12f
private const val DEFAULT_ASPECT = 2f
private const val MIN_ASPECT = 0.4f
private const val MAX_ASPECT = 4f
private val MODERN_MAX_HEIGHT = 360.dp

/** Width over height of the v2 preview box, clamped so a long thin sketch neither vanishes nor takes over the page; 2 for no content. */
internal fun modernPreviewAspect(bounds: SceneBounds?): Float {
    if (bounds == null || bounds.height <= 0.0 || bounds.width <= 0.0) return DEFAULT_ASPECT
    return (bounds.width / bounds.height).toFloat().coerceIn(MIN_ASPECT, MAX_ASPECT)
}

/** The extent of what a v2 preview shows: every element on a visible layer. */
internal fun modernPreviewBounds(scene: SketchSceneV2): SceneBounds? {
    val layers = scene.layers.associateBy { it.id }
    return SceneBounds.of(scene.elementsInOrder.filter { (layers[it.base.layerId] ?: Layer.DefaultLayer).visible })
}

/** The environment a real screen uses: the bundled-font text measurer and the font families, loaded once per composition. */
@Composable
fun rememberModernPreviewEnvironment(): ModernPreviewEnvironment {
    val metrics = rememberSketchTextMetricsProvider()
    val fonts = rememberSketchFontState()
    val measurer = rememberTextMeasurer()
    return remember(metrics, fonts, measurer) {
        ModernPreviewEnvironment(
            metrics,
            PainterResources(textMeasurer = measurer, fontFamily = { id -> (fonts.value as? SketchFontState.Loaded)?.families?.forExcalidrawId(id) }),
        )
    }
}

private class NoteButton(val label: String, val tag: String, val action: (() -> Unit)?)

/**
 * The one place an embed's state becomes pixels (Story 6.1.1): the v1 preview draws a classic sheet, the v2 draw list a
 * new-editor sheet, and a newer-schema sheet, a missing sketch and a platform with no file storage each get a labelled note
 * instead of a crash. [modern] is only built when a v2 sheet is shown, so a page of v1 embeds never loads the fonts.
 */
@Composable
internal fun SketchRenderDispatcher(
    preview: SketchPreview?,
    actions: SketchEmbedActions,
    modern: ModernPreviewEnvironment?,
    modifier: Modifier = Modifier,
) {
    when (preview) {
        null -> Box(modifier.fillMaxWidth().heightIn(min = PLACEHOLDER_MIN_HEIGHT))
        SketchPreview.Missing -> NoteWithActions(
            MISSING_SKETCH_TEXT,
            listOf(NoteButton(REMOVE_EMBED_LABEL, SKETCH_REMOVE_EMBED_TAG, actions.onRemoveEmbed), NoteButton(CHOOSE_ANOTHER_LABEL, SKETCH_CHOOSE_ANOTHER_TAG, actions.onChooseAnother)),
            modifier,
        )
        SketchPreview.Empty -> PreviewNote(EMPTY_SKETCH_TEXT, modifier)
        SketchPreview.NewerSchema -> PreviewNote(NEWER_SCHEMA_TEXT, modifier.testTag(SKETCH_PREVIEW_NEWER_TAG))
        SketchPreview.StorageUnavailable -> NoteWithActions(
            STORAGE_UNAVAILABLE_TEXT,
            listOf(NoteButton(OPEN_PAGE_LABEL, SKETCH_OPEN_PAGE_TAG, actions.onOpenPage)),
            modifier.testTag(SKETCH_PREVIEW_UNAVAILABLE_TAG),
        )
        is SketchPreview.Drawing -> ClassicPreviewContent(preview, modifier)
        is SketchPreview.ModernDrawing -> ModernDrawingPreview(preview.scene, modern ?: rememberModernPreviewEnvironment(), modifier.testTag(SKETCH_PREVIEW_MODERN_TAG))
    }
}

@Composable
private fun NoteWithActions(text: String, buttons: List<NoteButton>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PreviewNote(text)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (button in buttons) {
                val action = button.action ?: continue
                TextButton(onClick = action, modifier = Modifier.testTag(button.tag)) { Text(button.label) }
            }
        }
    }
}

@Composable
private fun ModernDrawingPreview(scene: SketchSceneV2, environment: ModernPreviewEnvironment, modifier: Modifier = Modifier) {
    val bounds = remember(scene) { modernPreviewBounds(scene) }
    val fontStatus = environment.metrics.fontStatus
    val ops = remember(scene, environment, fontStatus) { DrawListBuilder(environment.metrics).build(scene, RenderStyle.Sketchy) }
    val painter = remember(environment) { ScenePainter(resources = environment.resources) }
    val density = LocalDensity.current.density
    Canvas(modifier.fillMaxWidth().aspectRatio(modernPreviewAspect(bounds)).heightIn(max = MODERN_MAX_HEIGHT).clipToBounds()) {
        val viewport = SketchViewport.of().fit(bounds, size.width.toDouble(), size.height.toDouble(), (MODERN_PADDING_DP * density).toDouble())
        painter.paint(this, ops, viewport, null)
    }
}
