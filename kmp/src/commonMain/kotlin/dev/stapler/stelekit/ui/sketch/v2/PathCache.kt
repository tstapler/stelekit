// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.graphics.Path
import dev.stapler.stelekit.sketch.v2.render.PathSegment
import dev.stapler.stelekit.sketch.v2.scene.ElementId

/**
 * Compose [Path]s of draw ops, kept while the op's segment list is the same instance: `DrawListBuilder` hands out
 * the same list for an unchanged element, so a pan or a drag of another element reuses every path (Task 2.1.3b).
 * One entry per (element, slot), where the slot is the index of the path op within its element. Not thread-safe;
 * used from the draw phase only.
 */
class PathCache {
    private class Entry(val segments: List<PathSegment>, val path: Path)

    private val entries = HashMap<ElementId, ArrayList<Entry?>>()

    /** How many paths were built (not served from the cache) over this cache's life; tests read it. */
    var builtCount: Int = 0
        private set

    val size: Int get() = entries.size

    fun pathFor(id: ElementId, slot: Int, segments: List<PathSegment>): Path {
        val list = entries.getOrPut(id) { ArrayList() }
        while (list.size <= slot) list.add(null)
        val hit = list[slot]
        if (hit != null && hit.segments === segments) return hit.path
        val path = toPath(segments)
        list[slot] = Entry(segments, path)
        builtCount++
        return path
    }

    /** Drops the entries of elements not in [live], so deleted elements do not hold paths. */
    fun retain(live: Set<ElementId>) {
        entries.keys.retainAll(live)
    }

    private fun toPath(segments: List<PathSegment>): Path {
        val path = Path()
        for (s in segments) {
            when (s) {
                is PathSegment.MoveTo -> path.moveTo(s.x.toFloat(), s.y.toFloat())
                is PathSegment.LineTo -> path.lineTo(s.x.toFloat(), s.y.toFloat())
                is PathSegment.CubicTo -> path.cubicTo(s.c1x.toFloat(), s.c1y.toFloat(), s.c2x.toFloat(), s.c2y.toFloat(), s.x.toFloat(), s.y.toFloat())
                PathSegment.Close -> path.close()
            }
        }
        return path
    }
}
