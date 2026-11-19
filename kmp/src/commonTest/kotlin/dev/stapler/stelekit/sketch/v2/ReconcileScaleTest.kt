// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.u
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/** Review-fix D4: reconcile indexes bound elements once instead of scanning the scene per container. */
class ReconcileScaleTest {
    @Test
    fun reconcile_should_LinkEveryLabelToItsContainer_AtFiveThousandElements() {
        val containers = 2_500
        var scene = SketchSceneV2.empty()
        for (i in 0 until containers) {
            scene = scene.add(rect("r$i", i, 0)).fold({ error("$it") }, { it })
            val label = TextElement(ElementBase(ElementId("t$i")), u(i), u(0), u(5), u(5), text = "l", containerId = ElementId("r$i"))
            scene = scene.add(label).fold({ error("$it") }, { it })
        }

        val started = TimeSource.Monotonic.markNow()
        val result = reconcile(scene)
        println("reconcile of ${scene.order.size} elements: ${started.elapsedNow()}")

        for (i in 0 until containers) {
            val r = result.scene[ElementId("r$i")] as RectElement
            assertEquals(listOf(ElementId("t$i") to BoundKind.Text), r.boundElements.map { it.id to it.kind })
        }
        assertEquals(emptySet(), reconcile(result.scene).changed, "idempotent")
    }
}
