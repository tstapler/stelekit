// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.hit.HitTester
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * Eraser (Story 3.2.3, UX-101). While the pointer is down every element the path currently touches is previewed as
 * erased (30% opacity, dashed outline: the cue is neither colour nor opacity alone) and nothing is deleted until
 * release; release deletes all of them in ONE command. A tap (no travel) erases only the topmost element under it,
 * which is also what the Crosshair "Apply" does. Esc, a platform cancel or a second pointer restores everything with
 * no command. Locked and hidden-layer elements are never previewed; the count of locked ones the path crossed is
 * reported as [ToolNotice.LockedNotErased].
 */
class EraserTool : Tool {
    override val id: ToolId = ToolId.Eraser

    override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult {
        if (event.type != InputType.Down) return ToolResult.Nothing
        val top = ctx.hitTester.hit(event.point, ctx.zoom)
        val state = Erasing(this, event.pointerId, event.point, linkedSetOf<ElementId>().apply { top?.let(::add) }, lockedAt(ctx.hitTester, event.point, ctx.zoom))
        return ToolResult(preview = state.preview(ctx), next = state)
    }

    private fun lockedAt(hits: HitTester, at: ScenePoint, zoom: Double): Set<ElementId> = hits.hitLocked(at, zoom).toSet()

    private class Erasing(
        private val owner: EraserTool,
        private val pointerId: Int,
        /** Where the path was last sampled; stays at the press point until the pointer leaves the tap slop. */
        private val last: ScenePoint,
        private val touched: LinkedHashSet<ElementId>,
        private val locked: Set<ElementId>,
        private val dragged: Boolean = false,
    ) : Tool {
        override val id: ToolId get() = ToolId.Eraser

        override fun onEvent(event: InputEvent, ctx: ToolContext): ToolResult = when {
            event.type == InputType.Escape || event.type == InputType.Cancel -> ToolResult(preview = null, next = owner)
            event.type == InputType.Down && event.pointerId != pointerId -> ToolResult(preview = null, next = owner)
            event.pointerId != pointerId -> ToolResult.Nothing
            event.type == InputType.Move -> extend(event.point, ctx).let { next -> ToolResult(preview = next.preview(ctx), next = next) }
            event.type == InputType.Up -> finish(extend(event.point, ctx))
            else -> ToolResult.Nothing
        }

        private fun extend(to: ScenePoint, ctx: ToolContext): Erasing {
            val hits = ctx.hitTester
            val more = LinkedHashSet(touched)
            val skipped = LinkedHashSet(locked)
            val dx = to.x.value - last.x.value
            val dy = to.y.value - last.y.value
            // A tap, even with a little jitter, erases only what was under the press (the topmost element).
            if (!dragged && hypot(dx, dy) * ctx.zoom <= TAP_SLOP_PX) return this
            val steps = min(MAX_SAMPLES.toDouble(), ceil(hypot(dx, dy) * ctx.zoom / SAMPLE_STEP_PX)).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                val p = ScenePoint(unit(last.x.value + dx * t), unit(last.y.value + dy * t))
                more += hits.hitAll(p, ctx.zoom)
                skipped += hits.hitLocked(p, ctx.zoom)
            }
            return Erasing(owner, pointerId, to, more, skipped, dragged = true)
        }

        fun preview(ctx: ToolContext): ToolPreview = ToolPreview(
            touched.mapNotNull { ctx.scene[it] }.map { e ->
                e.withBase(e.base.copy(opacity = PREVIEW_OPACITY, style = e.base.style.copy(strokeStyle = StrokeStyle.Dashed)))
            },
        )

        private fun finish(done: Erasing): ToolResult {
            val notice = if (done.locked.isNotEmpty()) ToolNotice.LockedNotErased(done.locked.size) else null
            val command = if (done.touched.isEmpty()) null else EraseCommand(done.touched.toList())
            return ToolResult(command, null, owner, notice)
        }

        private fun unit(v: Double): SceneUnit = SceneUnit.of(v).getOrNull() ?: SceneUnit.ZERO
    }

    private companion object {
        /** Erase-preview opacity percentage. */
        const val PREVIEW_OPACITY = 30

        /** A press that never travels further than this many screen pixels is a tap. */
        const val TAP_SLOP_PX = 4.0

        /** The path is sampled this many screen pixels apart so a fast stroke cannot step over a thin element. */
        const val SAMPLE_STEP_PX = 4.0

        /** Bound on samples per move event, so one jump across a huge canvas costs bounded work. */
        const val MAX_SAMPLES = 512
    }
}
