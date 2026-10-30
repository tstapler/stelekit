// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

import dev.stapler.stelekit.model.EffectiveNotesPath

const val THUMBNAIL_MAX_EDGE_PX: Int = 256
const val DISPLAY_MAX_EDGE_PX: Int = 1600
const val DERIVATIVE_JPEG_QUALITY: Int = 85

/** Quality tiers an image can be shown at. The original is never modified; the other two are derived copies. */
enum class ImageTier(val maxEdgePx: Int?) {
    THUMBNAIL(THUMBNAIL_MAX_EDGE_PX),
    DISPLAY(DISPLAY_MAX_EDGE_PX),
    ORIGINAL(null),
}

/** Paths of the derived copies that exist for an image; `null` means "not generated (yet)". */
data class ImageDerivativeSet(val thumbnail: String?, val display: String?) {
    companion object {
        val NONE = ImageDerivativeSet(null, null)
    }
}

/**
 * Where derivatives live: `<graph>/.stelekit/cache/images/<hash>.thumb.jpg` / `.display.jpg`.
 *
 * The name is a hash of the original's path, so it is deterministic without a schema column, works
 * for plain markdown images that have no annotation row, and is regenerable if lost. It sits in
 * `.stelekit/` (next to the sidecars), not `assets/`, so it never appears in synced markdown or the
 * asset browser; ignore `.stelekit/cache/` in git if `.stelekit/` is tracked.
 */
object ImageDerivativePaths {
    const val CACHE_SUBDIR = ".stelekit/cache/images"

    fun pathFor(graph: EffectiveNotesPath, originalPath: String, tier: ImageTier): String? {
        val suffix = when (tier) {
            ImageTier.THUMBNAIL -> "thumb"
            ImageTier.DISPLAY -> "display"
            ImageTier.ORIGINAL -> return null
        }
        return graph.child("$CACHE_SUBDIR/${stableKey(originalPath)}.$suffix.jpg")
    }

    /** 64-bit FNV-1a of [originalPath] as 16 hex chars. */
    fun stableKey(originalPath: String): String {
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        for (b in originalPath.encodeToByteArray()) {
            h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }
}

object ImageTierResolver {
    /** Falls back to the next tier up when a derivative is missing; the original always exists. */
    fun pick(tier: ImageTier, original: String, derivatives: ImageDerivativeSet): String = when (tier) {
        ImageTier.THUMBNAIL -> derivatives.thumbnail ?: derivatives.display ?: original
        ImageTier.DISPLAY -> derivatives.display ?: original
        ImageTier.ORIGINAL -> original
    }

    /**
     * Absolute path of a markdown/annotation image reference that derivatives can be built from, or
     * null for remote or unsafe references (those are shown as-is).
     */
    fun localOriginalPath(reference: String, graph: EffectiveNotesPath?): String? {
        if (reference.startsWith("../assets/")) {
            val rest = reference.removePrefix("../assets/")
            val unsafe = rest.contains('\\') || rest.lowercase().contains("%2e") || rest.split('/').any { it == ".." }
            return if (graph == null || unsafe) null else graph.child("assets/$rest")
        }
        return when {
            reference.startsWith("saf://") -> reference
            reference.contains("://") -> null
            reference.startsWith("/") -> reference
            else -> null
        }
    }
}

/**
 * Largest power-of-two `inSampleSize` that still leaves the longer edge >= [minEdge] (so the
 * sampled decode can be scaled down to [minEdge] without upscaling), but never less than what the
 * [maxPixels] decode budget requires. 1 when dimensions are unknown.
 */
fun inSampleSizeForEdge(width: Int, height: Int, minEdge: Int, maxPixels: Long = MAX_DECODE_PIXELS): Int {
    if (width <= 0 || height <= 0 || minEdge <= 0) return 1
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= minEdge && sample < (1 shl 10)) sample *= 2
    return maxOf(sample, inSampleSizeFor(width, height, maxPixels))
}
