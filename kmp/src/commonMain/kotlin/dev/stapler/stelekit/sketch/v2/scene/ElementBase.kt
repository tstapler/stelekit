// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import kotlinx.serialization.json.JsonObject

enum class FillStyle { Hachure, CrossHatch, Solid, Zigzag }

enum class StrokeStyle { Solid, Dashed, Dotted }

enum class RoundnessKind { Legacy, ProportionalRadius, AdaptiveRadius }

/** Corner treatment; Excalidraw's `roundness` is null (sharp) or a type and value. */
data class Roundness(val kind: RoundnessKind, val value: Double? = null) {
    init {
        require(value?.isFinite() ?: true) { "roundness value must be finite" }
    }
}

/** Look of an element. Colours are CSS strings (`#rrggbb` or `transparent`), kept opaque for round trips. */
data class ElementStyle(
    val strokeColor: String = "#1e1e1e",
    val backgroundColor: String = "transparent",
    val fillStyle: FillStyle = FillStyle.Solid,
    val strokeWidth: Double = 2.0,
    val strokeStyle: StrokeStyle = StrokeStyle.Solid,
    val roughness: Int = 1,
    val roundness: Roundness? = null,
) {
    init {
        require(strokeWidth.isFinite() && strokeWidth >= 0.0) { "strokeWidth must be finite and non-negative" }
    }
}

/**
 * Fields shared by every element kind. `version` bumps per edit and drives caches; `ordinal` is the per-kind auto
 * number, assigned once and never reused (Story 3.5.7); `extras` carries unknown fields for round trips.
 */
data class ElementBase(
    val id: ElementId,
    val layerId: LayerId = LayerId.Default,
    val groupIds: List<GroupId> = emptyList(),
    val frameId: ElementId? = null,
    val zKey: ZKey = ZKey("a0"),
    val locked: Boolean = false,
    val opacity: Int = FULL_OPACITY,
    val style: ElementStyle = ElementStyle(),
    val seed: Int = 0,
    val version: Int = 1,
    val ordinal: Int = 0,
    val name: String? = null,
    val extras: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(opacity in 0..FULL_OPACITY) { "opacity is a percentage" }
    }

    companion object {
        const val FULL_OPACITY = 100
    }
}
