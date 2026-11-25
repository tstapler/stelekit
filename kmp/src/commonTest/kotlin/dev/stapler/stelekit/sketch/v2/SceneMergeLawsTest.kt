// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.merge.SceneMerge
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The merge laws of ADR-008 on generated sheets: one base, three devices that each apply a random stream of edits, adds and
 * deletes. Commutativity, idempotence and absorption hold byte for byte; associativity holds on content (element versions are
 * cache stamps and are normalised away, see [SceneMergeSupport.contentOnly]).
 */
class SceneMergeLawsTest {
    private val config = PropTestConfig(iterations = 300)

    private class Trio(val base: SketchDocumentV2, val a: SketchDocumentV2, val b: SketchDocumentV2, val c: SketchDocumentV2)

    private fun trio(seed: Long): Trio {
        val r = Random(seed)
        val base = SceneMergeSupport.baseDoc(r, r.nextInt(MAX_BASE) + 1)
        fun device(name: String): SketchDocumentV2 {
            var doc = base
            repeat(r.nextInt(MAX_EDITS) + 1) { i -> doc = SceneMergeSupport.randomEdit(doc, r, name, i) }
            return doc
        }
        return Trio(base, device("a"), device("b"), device("c"))
    }

    private fun merged(base: SketchDocumentV2, ours: SketchDocumentV2, theirs: SketchDocumentV2): SketchDocumentV2 =
        SceneMerge.merge(base, ours, theirs).getOrNull()!!.doc

    @Test
    fun merge_should_BeCommutative_When_DevicesEditedIndependently() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val t = trio(seed)
            assertEquals(SceneMergeSupport.bytes(merged(t.base, t.a, t.b)), SceneMergeSupport.bytes(merged(t.base, t.b, t.a)), "seed $seed")
        }
    }

    @Test
    fun merge_should_BeIdempotent_When_BothSidesAreTheSame() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val t = trio(seed)
            assertEquals(SceneMergeSupport.bytes(t.a), SceneMergeSupport.bytes(merged(t.base, t.a, t.a)), "seed $seed")
            assertEquals(SceneMergeSupport.bytes(t.a), SceneMergeSupport.bytes(merged(t.a, t.a, t.a)), "seed $seed")
        }
    }

    @Test
    fun merge_should_AbsorbItsOwnResult_When_EitherDeviceMergesItBack() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val t = trio(seed)
            val m = merged(t.base, t.a, t.b)
            assertEquals(SceneMergeSupport.bytes(m), SceneMergeSupport.bytes(merged(t.base, t.a, m)), "a absorbs, seed $seed")
            assertEquals(SceneMergeSupport.bytes(m), SceneMergeSupport.bytes(merged(t.base, t.b, m)), "b absorbs, seed $seed")
        }
    }

    @Test
    fun merge_should_BeAssociativeOnContent_When_ThreeDevicesMergeInAnyGrouping() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val t = trio(seed)
            val left = merged(t.base, merged(t.base, t.a, t.b), t.c)
            val right = merged(t.base, t.a, merged(t.base, t.b, t.c))
            val other = merged(t.base, merged(t.base, t.c, t.a), t.b)
            val expected = SceneMergeSupport.bytes(SceneMergeSupport.contentOnly(left))
            assertEquals(expected, SceneMergeSupport.bytes(SceneMergeSupport.contentOnly(right)), "seed $seed")
            assertEquals(expected, SceneMergeSupport.bytes(SceneMergeSupport.contentOnly(other)), "seed $seed")
        }
    }

    @Test
    fun merge_should_KeepEveryAddedElement_When_NobodyDeletedIt() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val t = trio(seed)
            val m = merged(t.base, t.a, t.b)
            val added = (SceneMergeSupport.ids(t.a) + SceneMergeSupport.ids(t.b)) - SceneMergeSupport.ids(t.base)
            assertTrue(SceneMergeSupport.ids(m).containsAll(added), "an added element was lost, seed $seed")
        }
    }

    @Test
    fun merge_should_ReturnAReconciledScene_When_Merged() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val t = trio(seed)
            val m = merged(t.base, t.a, t.b)
            assertTrue(reconcile(m.scene).isClean, "seed $seed")
        }
    }

    private companion object {
        const val MAX_BASE = 8
        const val MAX_EDITS = 6
    }
}
