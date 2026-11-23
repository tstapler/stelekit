// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.snap

import dev.stapler.stelekit.sketch.SnapKind

/** What a guide aligns to, ignoring how far it runs: two guides with the same key are the same snap target. */
data class GuideKey(val axis: GuideAxis, val position: Double, val kind: SnapKind)

fun SnapGuide.key() = GuideKey(axis, position, kind)

/**
 * Decides when a drag earns a haptic tick: when a guide for a snap target the last drag sample did not have appears. Staying on
 * one target ticks once, sliding to another ticks again, and ticks closer than [minGapMs] are dropped so a drag skimming past a
 * row of edges does not buzz. Pure and clock-free: the caller passes `nowMs`.
 */
class SnapTicker(private val minGapMs: Long = DEBOUNCE_MS) {
    private var seen: Set<GuideKey> = emptySet()
    private var lastTickMs: Long? = null

    /** Feeds one drag sample's [guides]; true when the platform should tick now. Grid guides never tick: they change at every cell crossing and would buzz. */
    fun onGuides(guides: List<SnapGuide>, nowMs: Long): Boolean {
        val keys = guides.filter { it.kind != SnapKind.GRID }.mapTo(HashSet()) { it.key() }
        val fresh = keys.any { it !in seen }
        seen = keys
        val last = lastTickMs
        if (!fresh || (last != null && nowMs - last < minGapMs)) return false
        lastTickMs = nowMs
        return true
    }

    companion object {
        const val DEBOUNCE_MS = 80L
    }
}
