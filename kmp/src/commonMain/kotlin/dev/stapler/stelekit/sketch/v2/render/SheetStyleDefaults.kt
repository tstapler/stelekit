// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle

/** Source of the per-element `seed` stored with each element (Excalidraw draws it in [1, 2^31)). */
fun interface SeedSource {
    fun nextSeed(): Int
}

/** Reproducible seeds for tests and replays: the n-th seed depends only on the start value. */
class SequenceSeedSource(start: Int) : SeedSource {
    private val random = SeededRandom(start)

    override fun nextSeed(): Int = (random.next() * SEED_SPACE).toInt().coerceAtLeast(1)

    private companion object {
        const val SEED_SPACE = 2147483647.0
    }
}

/**
 * The per-sheet style setting (owner decision OD-5): Clean is the default for new sheets, Sketchy is selectable per
 * sheet. It decides the [RenderStyle] the sheet is drawn with and the `roughness` a NEW element starts with; an
 * element's own `roughness` (0 = Clean) stays editable afterwards, so Sketchy is also selectable per element.
 */
data class SheetStyleDefaults(val renderStyle: RenderStyle = RenderStyle.Clean) {
    /** Roughness of a new element: 0 on a Clean sheet, Excalidraw's "Artist" level (1) on a Sketchy one. */
    val newElementRoughness: Int
        get() = if (renderStyle == RenderStyle.Sketchy) ARTIST_ROUGHNESS else 0

    fun styleForNewElement(style: ElementStyle = ElementStyle()): ElementStyle = style.copy(roughness = newElementRoughness)

    companion object {
        const val ARTIST_ROUGHNESS = 1
        val Default = SheetStyleDefaults()
    }
}

/** Seeds are assigned once, when an element is created, and persisted; edits never reseed (the look must not shimmer). */
object ElementSeeds {
    /** [base] unchanged when it already has a seed, else a copy with a fresh one. */
    fun ensureSeed(base: ElementBase, source: SeedSource): ElementBase = if (base.seed != 0) base else base.copy(seed = source.nextSeed())
}
