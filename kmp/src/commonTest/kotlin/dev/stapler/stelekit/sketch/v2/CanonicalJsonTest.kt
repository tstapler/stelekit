// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJsonError
import dev.stapler.stelekit.util.ContentHasher
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.1.2: canonical JSON (own shortest-digit doubles, sorted keys, header-first). Runs on every target. */
class CanonicalJsonTest {
    private fun fmt(d: Double): String = CanonicalJson.formatDouble(d).getOrElse { error("non-finite") }

    private fun num(d: Double): JsonPrimitive = CanonicalJson.number(d).getOrElse { error("non-finite") }

    private fun text(e: JsonObject, header: List<String> = emptyList()): String =
        CanonicalJson.encode(e, header).getOrElse { error("encode failed: $it") }.decodeToString()

    @Test
    fun planFixtureValues() {
        assertEquals(listOf("0.1", "0.00001", "123456789.125", "0", "4"), listOf(0.1, 1e-5, 123456789.125, -0.0, 4.0).map(::fmt))
    }

    @Test
    fun edgeCasesMatchEcmaScript() {
        val expected = mapOf(
            1e21 to "1e+21", 1e20 to "100000000000000000000", 1e-6 to "0.000001", 1e-7 to "1e-7", 1.5e300 to "1.5e+300",
            5e-324 to "5e-324", Double.MAX_VALUE to "1.7976931348623157e+308", 0.1 + 0.2 to "0.30000000000000004",
            9007199254740992.0 to "9007199254740992", -123.456 to "-123.456", 100.0 to "100", 1.0E7 to "10000000",
            0.001 to "0.001", 4.35 to "4.35", 2.2250738585072014e-308 to "2.2250738585072014e-308", 123e-20 to "1.23e-18",
            0.5 to "0.5", 1.0 to "1", 12345.678 to "12345.678",
        )
        expected.forEach { (d, s) -> assertEquals(s, fmt(d), "value $d") }
    }

    @Test
    fun decodeOfEncode_should_EqualTheInput_ForRandomBitPatterns_Property() = runTest {
        val finite = Arb.long().map { Double.fromBits(it) }.filter { it.isFinite() }
        checkAll(2_000, finite) { d ->
            val back = CanonicalJson.parseDouble(fmt(d))
            assertTrue(back != null && back == d, "canonical ${fmt(d)} of bits ${d.toRawBits()} parsed back as $back")
        }
    }

    @Test
    fun decodeOfEncode_should_EqualTheInput_ForSixteenthsAndUniformValues() {
        val rnd = Random(7)
        repeat(5_000) {
            val sixteenths = rnd.nextInt(-4_000_000, 4_000_000) / 16.0
            val uniform = rnd.nextDouble(0.0, 1_000.0)
            assertTrue(CanonicalJson.parseDouble(fmt(sixteenths)) == sixteenths, "sixteenths $sixteenths")
            assertTrue(CanonicalJson.parseDouble(fmt(uniform)) == uniform, "uniform $uniform -> ${fmt(uniform)}")
        }
    }

    @Test
    fun nonFinite_should_BeRejected() {
        for (d in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertIs<CanonicalJsonError.NonFinite>(assertIs<Either.Left<CanonicalJsonError>>(CanonicalJson.number(d)).value)
            assertIs<CanonicalJsonError.NonFinite>(assertIs<Either.Left<CanonicalJsonError>>(CanonicalJson.formatDouble(d)).value)
            val tree = buildJsonObject { put("x", JsonArray(listOf(JsonPrimitive(d)))) }
            assertIs<CanonicalJsonError.NonFinite>(assertIs<Either.Left<CanonicalJsonError>>(CanonicalJson.encode(tree)).value)
        }
    }

    @Test
    fun headerKeys_should_LeadAndTheRestSorted() {
        val sheet = buildJsonObject {
            put("elements", JsonArray(listOf(buildJsonObject { put("z", 1); put("a", num(0.5)) })))
            put("layers", JsonArray(emptyList()))
            put("extras", buildJsonObject { put("b", JsonNull); put("a", true) })
            put("sheetUuid", "s2")
            put("schema", 2)
        }
        val out = text(sheet, listOf("schema", "sheetUuid"))
        assertEquals(
            "{\"schema\":2,\"sheetUuid\":\"s2\",\"elements\":[{\"a\":0.5,\"z\":1}],\"extras\":{\"a\":true,\"b\":null},\"layers\":[]}",
            out,
        )
        assertTrue(out.startsWith("{\"schema\":"))
        assertTrue(out.indexOf("\"sheetUuid\":") < out.indexOf("\"elements\":"))
        assertEquals("{\"a\":1,\"schema\":2,\"sheetUuid\":\"s2\"}", text(buildJsonObject { put("sheetUuid", "s2"); put("schema", 2); put("a", 1) }))
    }

    @Test
    fun numbers_should_BeNormalised_WhenLiteralsComeFromParsedText() {
        val parsed = CanonicalJson.decode("{\"a\":1.0,\"b\":-0,\"c\":1E2,\"d\":0.10,\"e\":123456789012345678}".encodeToByteArray())
        val out = CanonicalJson.encode(parsed.getOrElse { error("decode") }).getOrElse { error("encode") }.decodeToString()
        assertEquals("{\"a\":1,\"b\":0,\"c\":100,\"d\":0.1,\"e\":123456789012345680}", out)
    }

    @Test
    fun strings_should_BeEscapedCanonically_AndLoneSurrogatesPreserved() {
        val s = "q\"b\\n\n\t\u0001\u007fé😀\ud800x"
        val out = text(buildJsonObject { put("k", s) })
        assertEquals("{\"k\":\"q\\\"b\\\\n\\n\\t\\u0001\u007fé😀\\ud800x\"}", out)
    }

    @Test
    fun depth_should_BeBoundedBySceneLimits() {
        var el: kotlinx.serialization.json.JsonElement = JsonPrimitive(1)
        repeat(70) { el = JsonArray(listOf(el)) }
        assertIs<CanonicalJsonError.TooDeep>(assertIs<Either.Left<CanonicalJsonError>>(CanonicalJson.encode(el)).value)
        val deepText = "[".repeat(70) + "]".repeat(70)
        assertIs<CanonicalJsonError.TooDeep>(assertIs<Either.Left<CanonicalJsonError>>(CanonicalJson.decode(deepText.encodeToByteArray())).value)
        assertIs<Either.Right<*>>(CanonicalJson.decode(("[".repeat(60) + "]".repeat(60)).encodeToByteArray()))
    }

    @Test
    fun invalidUtf8_and_malformedJson_should_BeMalformed() {
        assertIs<CanonicalJsonError.Malformed>(assertIs<Either.Left<CanonicalJsonError>>(CanonicalJson.decode(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d))).value)
        assertIs<CanonicalJsonError.Malformed>(assertIs<Either.Left<CanonicalJsonError>>(CanonicalJson.decode("{\"a\":".encodeToByteArray())).value)
    }

    /** Same digits on every target: the FNV-1a hash of 5,000 canonical strings per class, recorded on JVM 21 and asserted on all. */
    @Test
    fun corpusHashes_should_EqualTheJvmRecordedValues_OnEveryTarget() {
        fun fnv(items: List<String>): Int {
            var h = -0x7ee3623b
            for (s in items) { for (c in s) h = (h xor c.code) * 16777619; h = (h xor 10) * 16777619 }
            return h
        }
        val sixteenths = Random(11).let { r -> List(5_000) { fmt(r.nextInt(-4_000_000, 4_000_000) / 16.0) } }
        val uniform = Random(12).let { r -> List(5_000) { fmt(r.nextDouble(0.0, 1_000.0)) } }
        val bits = Random(13).let { r -> List(5_000) { fmt(Double.fromBits(r.nextLong()).let { d -> if (d.isFinite()) d else 1.5 }) } }
        println("CANONICAL_CORPUS_HASHES sixteenths=${fnv(sixteenths)} uniform=${fnv(uniform)} bits=${fnv(bits)}")
        assertEquals(EXPECTED_SIXTEENTHS, fnv(sixteenths))
        assertEquals(EXPECTED_UNIFORM, fnv(uniform))
        assertEquals(EXPECTED_BITS, fnv(bits))
    }

    /** Golden bytes of a fixture scene: the SHA-256 is recorded on JVM and must be identical on wasmJs and Android. */
    @Test
    fun fixtureScene_should_HaveTheSameSha256_OnEveryTarget() {
        val rnd = Random(2026)
        val elements = List(40) { i ->
            buildJsonObject {
                put("id", "e$i")
                put("type", if (i % 3 == 0) "rect" else "line")
                put("x", num(rnd.nextInt(-100_000, 100_000) / 16.0))
                put("y", num(rnd.nextInt(-100_000, 100_000) / 16.0))
                put("w", num(rnd.nextDouble(0.0, 500.0)))
                put("angle", num(rnd.nextDouble(-3.2, 3.2)))
                put("label", "Rohr äö 😀 $i")
            }
        }
        val scene = buildJsonObject {
            put("schema", 2)
            put("sheetUuid", "fixture-sheet")
            put("elements", JsonArray(elements))
            put("viewport", buildJsonObject { put("zoom", num(1.0 / 3.0)); put("scrollX", num(-0.0)) })
        }
        val bytes = CanonicalJson.encode(scene, listOf("schema", "sheetUuid")).getOrElse { error("encode") }
        val sha = ContentHasher.sha256(bytes)
        println("CANONICAL_FIXTURE_SHA256 $sha bytes=${bytes.size}")
        assertEquals(EXPECTED_FIXTURE_SHA, sha)
        assertEquals(bytes.decodeToString(), CanonicalJson.encode(CanonicalJson.decode(bytes).getOrElse { error("decode") }, listOf("schema", "sheetUuid")).getOrElse { error("re-encode") }.decodeToString())
    }

    private companion object {
        const val EXPECTED_SIXTEENTHS = -250843496
        const val EXPECTED_UNIFORM = -483261546
        const val EXPECTED_BITS = -821973315
        const val EXPECTED_FIXTURE_SHA = "d9eadbcaeffb9ec39fafac6bb7ba503651854c515bc2e80460d56073002fa7ed"
    }
}
