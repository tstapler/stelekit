// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only (the spec builder lives in jvmMain)
package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Property: any spec the generator can write builds without errors, and build then encode then decode gives the same
 * scene, the same bytes on re-encode, a clean `validate`, a `describe` and an SVG. The generator stays inside the
 * spec's rules (ids unique, openings inside their wall), so a failure is a bug in the builder or the codec, not in the spec.
 */
class SketchSpecRoundTripPropertyTest {
    @Test
    fun `generated plan specs round trip through the codec`() = runTest {
        checkAll(ITERATIONS, Arb.int()) { seed -> checkSpec(planSpec(Random(seed))) }
    }

    @Test
    fun `generated diagram specs round trip through the codec`() = runTest {
        checkAll(ITERATIONS, Arb.int()) { seed -> checkSpec(diagramSpec(Random(seed))) }
    }

    private fun checkSpec(spec: String) {
        val outcome = SketchSpecBuilder(Json.parseToJsonElement(spec) as JsonObject).build()
        assertTrue(outcome.issues.errors.isEmpty(), "errors ${outcome.issues.errors} for $spec")
        val doc = assertNotNull(outcome.doc)
        val bytes = (SheetCodecV2.encode(doc) as Either.Right).value
        val decoded = (SheetCodecV2.decode(bytes) as Either.Right).value
        assertEquals(doc.scene, decoded.scene, spec)
        assertEquals(doc.extras, decoded.extras)
        assertTrue(bytes.contentEquals((SheetCodecV2.encode(decoded) as Either.Right).value), "re-encode differs for $spec")
        val report = SketchValidate.validate(bytes)
        assertTrue(report.ok && report.warnings.isEmpty(), report.render())
        assertTrue(SketchDescribe.describe(decoded).startsWith("SKETCH SHEET"))
        assertTrue(SketchRender.svg(decoded, RenderRequest(dimensions = true)).contains("<svg"))
    }

    private fun planSpec(r: Random): String {
        val rooms = r.nextInt(1, MAX_SHAPES + 1)
        val sizes = (0 until rooms).map { listOf(r.nextInt(6, 25), r.nextInt(6, 25)) }
        val shapes = (0 until rooms).joinToString(",") { i ->
            """{"id":"room$i","x":"${i * 30}'","y":"${r.nextInt(0, 5)}'","w":"${sizes[i][0]}'","h":"${sizes[i][1]}'","label":"Room ${word(r)}"}"""
        }
        val openings = (0 until rooms).joinToString(",") { i ->
            val wallFeet = sizes[i][0]
            val width = r.nextInt(2, 4)
            val offset = r.nextInt(0, wallFeet - width + 1)
            val type = if (r.nextBoolean()) "door" else "window"
            val wall = if (r.nextBoolean()) "north" else "south"
            """{"id":"op$i","type":"$type","on":"room$i","wall":"$wall","offset":"$offset'","width":"$width'"}"""
        }
        val dims = (0 until rooms).joinToString(",") { i -> """{"shape":"room$i","side":"${listOf("north", "south", "east", "west").random(r)}"}""" }
        return """{"spec":1,"mode":"plan","title":"${word(r)}","shapes":[$shapes],"openings":[$openings],"dimensions":[$dims]}"""
    }

    private fun diagramSpec(r: Random): String {
        val n = r.nextInt(2, MAX_SHAPES + 3)
        val kinds = listOf("rect", "ellipse", "diamond")
        val shapes = (0 until n).joinToString(",") { i ->
            val place = if (i == 0) """"x":${r.nextInt(0, 50)},"y":${r.nextInt(0, 50)}""" else """"rightOf":"n${i - 1}","gap":${r.nextInt(20, 120)}"""
            """{"id":"n$i","kind":"${kinds.random(r)}",$place,"w":${r.nextInt(60, 200)},"h":${r.nextInt(40, 120)},"label":"${word(r)} ${word(r)}"}"""
        }
        val arrows = (1 until n).joinToString(",") { i -> """{"id":"e$i","from":"n${i - 1}","to":"n$i","label":"${word(r)}","head":"${listOf("end", "start", "both", "none").random(r)}"}""" }
        return """{"spec":1,"mode":"diagram","shapes":[$shapes],"arrows":[$arrows]}"""
    }

    private fun word(r: Random): String = WORDS.random(r)

    private companion object {
        const val ITERATIONS = 60
        const val MAX_SHAPES = 4
        val WORDS = listOf("alpha", "kitchen", "store", "API", "queue", "bath", "hall", "cache", "x1", "main")
    }
}
