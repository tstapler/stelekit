// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.image.ImageDerivativeService
import dev.stapler.stelekit.platform.image.ImageDerivativeSet
import dev.stapler.stelekit.platform.image.ImageTier
import dev.stapler.stelekit.platform.image.ImageTierResolver
import kotlinx.coroutines.CancellationException

/**
 * What to hand to Coil for one image at one [ImageTier]. [model] is null until derivative lookup
 * (or lazy generation) finishes, so the full-size original is never decoded just to be replaced.
 * [placeholder] is the thumbnail path to show while a larger tier loads.
 */
data class TieredImageModel(val model: String?, val placeholder: String?)

/**
 * Resolves [reference] (a `../assets/...` link, absolute path or `saf://` path) to the best file for
 * [tier]. Missing derivatives are generated lazily on first view; remote images and platforms
 * without a decoder use [reference] unchanged. [recordedThumbnail] is `ImageAnnotation.thumbnailPath`.
 */
@Composable
fun rememberTieredImageModel(
    reference: String,
    tier: ImageTier,
    graph: EffectiveNotesPath? = LocalNotesPath.current,
    recordedThumbnail: String? = null,
): State<TieredImageModel> {
    val fileSystem = LocalFileSystem.current
    val original = remember(reference, graph) { ImageTierResolver.localOriginalPath(reference, graph) }
    val initial = TieredImageModel(model = if (original == null || tier == ImageTier.ORIGINAL) reference else null, placeholder = null)
    return produceState(initialValue = initial, reference, tier, graph, fileSystem, recordedThumbnail) {
        // produceState keeps the previous key's value until the producer assigns; a reused list
        // slot would otherwise show the previous image while this one is looked up.
        value = initial
        if (original == null || tier == ImageTier.ORIGINAL) {
            value = TieredImageModel(reference, null)
            return@produceState
        }
        if (graph == null || fileSystem == null) {
            value = TieredImageModel(reference, null)
            return@produceState
        }
        val service = ImageDerivativeService.shared
        val derivatives: ImageDerivativeSet = try {
            val found = service.existing(fileSystem, graph, original, recordedThumbnail)
            val complete = if (tier == ImageTier.THUMBNAIL) found.thumbnail != null else found.display != null
            if (complete) found else service.ensure(fileSystem, graph, original, recordedThumbnail)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            ImageDerivativeSet.NONE
        }
        val picked = ImageTierResolver.pick(tier, original, derivatives)
        value = TieredImageModel(
            model = if (picked == original) reference else picked,
            placeholder = derivatives.thumbnail.takeIf { tier == ImageTier.DISPLAY },
        )
    }
}
