// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.2.3b: random scene mutations (add, replace, remove over a small id pool, so references dangle often) always
 * leave the reconciled scene satisfying the invariants, judged by an oracle written here and not shared with
 * `reconcile`, and reconciling twice changes nothing. The command-sequence variant lives in `HistoryV2PropertyTest`.
 */
class ReconcilePropertyTest {
    private val pool = 8
    private val u = SceneUnit.ZERO
    private val groups = listOf("g0", "g1", "g2")

    private fun randomId(r: Random) = ElementId("e${r.nextInt(pool + 2)}")

    private fun randomElement(r: Random, id: ElementId): SceneElement {
        val base = ElementBase(
            id = id,
            groupIds = List(r.nextInt(3)) { GroupId(groups[r.nextInt(groups.size)]) },
            frameId = if (r.nextInt(3) == 0) randomId(r) else null,
        )
        val refs = List(r.nextInt(4)) { BoundRef(randomId(r), if (r.nextBoolean()) BoundKind.Arrow else BoundKind.Text) }
        fun binding() = if (r.nextBoolean()) Binding(randomId(r), 0.5, 0.5) else null
        return when (r.nextInt(6)) {
            0 -> RectElement(base, u, u, u, u, boundElements = refs)
            1 -> DiamondElement(base, u, u, u, u, boundElements = refs)
            2 -> EllipseElement(base, u, u, SceneUnit.ofInt(1), SceneUnit.ofInt(1), boundElements = refs)
            3 -> ArrowElement(base, emptyList(), startBinding = binding(), endBinding = binding())
            4 -> TextElement(base, u, u, u, u, text = "t", containerId = if (r.nextBoolean()) randomId(r) else null)
            else -> FrameElement(base, u, u, u, u)
        }
    }

    private fun mutate(scene: SketchSceneV2, r: Random): SketchSceneV2 {
        val id = randomId(r)
        return when (r.nextInt(3)) {
            0 -> if (id in scene) scene else scene.add(randomElement(r, id)).fold({ scene }, { it })
            1 -> if (id in scene) scene.replace(randomElement(r, id)).fold({ scene }, { it }) else scene
            else -> scene.remove(id).fold({ scene }, { it })
        }
    }

    private fun containers(e: SceneElement): List<BoundRef>? = when (e) {
        is RectElement -> e.boundElements
        is DiamondElement -> e.boundElements
        is EllipseElement -> e.boundElements
        else -> null
    }

    private fun violations(scene: SketchSceneV2): List<String> {
        val out = mutableListOf<String>()
        val all = scene.elementsInOrder
        for (e in all) {
            if (e.base.groupIds.size != e.base.groupIds.toSet().size) out += "${e.id} duplicate group"
            e.base.frameId?.let { f ->
                val target = scene[f]
                if (target !is FrameElement || f == e.id || e is FrameElement) out += "${e.id} bad frame $f"
            }
            if (e is ArrowElement) {
                listOfNotNull(e.startBinding, e.endBinding).forEach { b ->
                    val target = scene[b.elementId]
                    if (target == null || target.id == e.id || target is ArrowElement) out += "${e.id} bad binding ${b.elementId}"
                    containers(target ?: e)?.let { refs -> if (BoundRef(e.id, BoundKind.Arrow) !in refs) out += "${e.id} not listed by ${b.elementId}" }
                }
            }
            if (e is TextElement) e.containerId?.let { c ->
                val target = scene[c]
                val holds = target is RectElement || target is DiamondElement || target is EllipseElement || target is ArrowElement
                if (!holds) out += "${e.id} bad container $c"
                containers(target ?: e)?.let { refs -> if (BoundRef(e.id, BoundKind.Text) !in refs) out += "${e.id} not listed by $c" }
            }
            containers(e)?.let { refs ->
                if (refs.map { it.id }.toSet().size != refs.size) out += "${e.id} duplicate bound ref"
                refs.forEach { if (scene[it.id] == null) out += "${e.id} lists missing ${it.id}" }
            }
        }
        all.flatMap { e -> e.base.groupIds.distinct() }.groupingBy { it }.eachCount().filterValues { it < 2 }.keys.forEach { out += "singleton group $it" }
        return out
    }

    @Test
    fun reconcile_should_LeaveInvariantsTrueAndBeIdempotent_When_RandomMutationsApplied() = runTest {
        checkAll(Arb.long()) { seed ->
            val r = Random(seed)
            var scene = SketchSceneV2.empty()
            repeat(60) {
                scene = reconcile(mutate(scene, r)).scene
                assertEquals(emptyList(), violations(scene), "seed $seed")
                assertTrue(reconcile(scene).isClean, "not idempotent, seed $seed")
            }
        }
    }

    @Test
    fun reconcile_should_RepairAnyRandomDirtyScene_When_BuiltWithoutReconciling() = runTest {
        checkAll(Arb.long()) { seed ->
            val r = Random(seed)
            var scene = SketchSceneV2.empty()
            repeat(30) { scene = mutate(scene, r) }
            val repaired = reconcile(scene)
            assertEquals(emptyList(), violations(repaired.scene), "seed $seed")
            assertTrue(reconcile(repaired.scene).isClean, "seed $seed")
        }
    }
}
