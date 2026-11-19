// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
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
 * Task 1.1.5a: decode is total over hostile bytes (truncated, deeply nested, oversized, NaN-bearing, mutated) and
 * stays inside `SceneLimits`; full-precision random doubles round trip exactly, which on wasmJs is the check that the
 * codec's own digit generation and parser, not the platform's, are in use.
 */
class SheetCodecV2FuzzTest {
    private val limits = SceneLimits.Default
    private val valid = SheetFixtures.bytes(SheetFixtures.everyKind())
    private val config = PropTestConfig(iterations = ITERATIONS)

    private fun mutate(r: Random): ByteArray = when (r.nextInt(MUTATIONS)) {
        0 -> valid.copyOf(r.nextInt(valid.size))
        1 -> valid.copyOf().also { b -> repeat(1 + r.nextInt(FLIPS)) { b[r.nextInt(b.size)] = r.nextInt(BYTE_VALUES).toByte() } }
        2 -> valid.copyOfRange(0, r.nextInt(valid.size)) + r.nextBytes(r.nextInt(GARBAGE)) + valid.copyOfRange(r.nextInt(valid.size), valid.size)
        else -> valid.decodeToString().replace(Regex("[0-9]+"), if (r.nextBoolean()) "NaN" else "1e999").encodeToByteArray()
    }

    @Test
    fun decode_should_NeverThrowOrExceedLimits_When_BytesAreMutated() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val result = SheetCodecV2.decode(mutate(Random(seed)), limits)
            result.onRight { doc ->
                assertTrue(doc.scene.size <= limits.maxElements)
                assertTrue(doc.constraints.size <= limits.maxConstraints)
                assertTrue(doc.scene.layers.size <= limits.maxLayers + 1)
                assertTrue(SheetCodecV2.encode(doc, limits).isRight(), "a decoded sheet must re-encode, seed $seed")
            }
        }
    }

    @Test
    fun decode_should_BeLeft_When_AnyNumberIsReplacedByNaN() {
        val text = valid.decodeToString()
        val numbers = Regex("(?<=[:\\[,])-?[0-9]+(\\.[0-9]+)?(e[+-]?[0-9]+)?(?=[,\\]}])").findAll(text).toList()
        assertTrue(numbers.size > MIN_NUMBERS, "fixture should carry numbers")
        for (m in numbers) {
            val bad = text.replaceRange(m.range, "NaN")
            assertTrue(SheetCodecV2.decode(bad.encodeToByteArray(), limits).isLeft(), "NaN at ${m.range}")
        }
    }

    @Test
    fun decode_should_BeTotal_When_NestingIsDeepOrSheetIsHuge() {
        for (depth in listOf(limits.maxJsonDepth - 4, limits.maxJsonDepth + 1, DEEP_PROBE)) {
            val nested = "[".repeat(depth) + "]".repeat(depth)
            val bytes = "{\"schema\":2,\"sheetUuid\":\"s\",\"extras\":{\"x\":$nested}}".encodeToByteArray()
            val r = SheetCodecV2.decode(bytes, limits)
            if (depth + 2 > limits.maxJsonDepth) assertTrue(r.isLeft(), "depth $depth") else assertTrue(r.isRight(), "depth $depth")
        }
        val tiny = SceneLimits(maxSheetBytes = valid.size - 1L)
        assertTrue(SheetCodecV2.decode(valid, tiny).isLeft())
        assertTrue(SheetCodecV2.decode(valid, SceneLimits(maxSheetBytes = valid.size.toLong())).isRight())
    }

    @Test
    fun roundTrip_should_BeExact_When_DocumentsHaveFullPrecisionRandomDoubles() = runTest {
        checkAll(config, Arb.long()) { seed ->
            val r = Random(seed)
            fun coord() = SheetFixtures.u((r.nextDouble() - 0.5) * COORD_SPAN)
            var scene = SketchSceneV2.empty()
            repeat(1 + r.nextInt(ELEMENTS)) { i ->
                val base = ElementBase(id = ElementId("e$i"), seed = r.nextInt(), version = 1 + r.nextInt(VERSIONS))
                val e = when (r.nextInt(3)) {
                    0 -> RectElement(base, coord(), coord(), coord(), coord(), (r.nextDouble() - 0.5) * ANGLE_SPAN)
                    1 -> EllipseElement(base, coord(), coord(), SheetFixtures.u(1.0 + r.nextDouble()), SheetFixtures.u(1.0 + r.nextDouble()))
                    else -> LineElement(base, List(1 + r.nextInt(POINTS)) { SheetFixtures.pt(r.nextDouble() * COORD_SPAN, r.nextDouble()) })
                }
                scene = scene.add(e).fold({ error("fixture") }, { it })
            }
            val doc = SketchDocumentV2(scene, "u-$seed")
            val bytes = SheetCodecV2.encode(doc).fold({ error("encode $it") }, { it })
            val decoded = SheetCodecV2.decode(bytes).fold({ error("decode $it seed $seed") }, { it })
            assertEquals(doc, decoded, "seed $seed")
            assertTrue(bytes.contentEquals(SheetCodecV2.encode(decoded).fold({ error("encode") }, { it })), "bytes, seed $seed")
        }
    }

    private companion object {
        const val ITERATIONS = 200
        const val MUTATIONS = 4
        const val FLIPS = 8
        const val BYTE_VALUES = 256
        const val GARBAGE = 24
        const val MIN_NUMBERS = 10
        const val DEEP_PROBE = 5_000
        const val COORD_SPAN = 100_000.0
        const val ANGLE_SPAN = 12.0
        const val ELEMENTS = 8
        const val VERSIONS = 1_000
        const val POINTS = 6
    }
}
