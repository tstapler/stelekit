// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-002 canonical doubles: under `sketch/` (codec, library, importer, export, bridge) a double enters JSON only through
 * `CanonicalJson.number` and leaves it through `CanonicalJson.parseDouble` / `doubleOf`, because wasmJs `Double.toString`
 * and `String.toDouble` do not round-trip. Flags `doubleOrNull`, `toDoubleOrNull`, `.double`, `.content.toDouble` and a
 * `JsonPrimitive(` of a coordinate, size, angle or font value. Typed user text in `ui/` is out of scope.
 */
class SketchCanonicalNumberGuardTest {
    private val scope = "sketch/"

    /** Files allowed to read numbers the platform way, with the reason; none today (the v1 `SvgReader` paths do not match). */
    private val allowlist: Map<String, String> = emptyMap()

    private val patterns = listOf(
        Regex("""doubleOrNull|toDoubleOrNull|floatOrNull|\.content\.toDouble|\.jsonPrimitive\.double\b|\.double\b|\.float\b"""),
        Regex("""JsonPrimitive\([^)]*(\.x\.value|\.y\.value|[wW]idth|[hH]eight|fontSize|lineHeight|\bangle\b|strokeWidth|toDouble\(\))"""),
    )

    private fun offending(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return false
        return patterns.any { it.containsMatchIn(t) }
    }

    @Test
    fun detector_should_FlagPlantedPlatformNumberUse_And_IgnoreIdsAndComments() {
        val flagged = listOf(
            "val d = p.doubleOrNull",
            "val d = text.toDoubleOrNull()",
            "val d = p.content.toDouble()",
            "val d = p.jsonPrimitive.double",
            "put(\"x\", JsonPrimitive(e.x.value))",
            "put(\"w\", JsonPrimitive(width))",
            "val a = JsonPrimitive(e.fontSize)",
        )
        for (line in flagged) assertTrue(offending(line), line)
        val fine = listOf(
            "val id = JsonPrimitive(it.value)",
            "Arrowhead.Triangle -> JsonPrimitive(\"triangle\")",
            "put(\"x\", num(x))",
            "// p.doubleOrNull is forbidden",
            " * never `.double`",
        )
        for (line in fine) assertTrue(!offending(line), line)
    }

    @Test
    fun sketchSources_should_ReadAndWriteDoublesThroughCanonicalJson_When_Scanned() {
        val sources = WiringInputs.productionSources.mapKeys { it.key.substringAfter("dev/stapler/stelekit/", "") }
        assertTrue(sources.keys.any { it.startsWith("sketch/v2/library/") }, "library sources not loaded")
        val hits = ArrayList<String>()
        for ((rel, text) in sources.toSortedMap()) {
            if (!rel.startsWith(scope) || rel in allowlist) continue
            text.lineSequence().forEachIndexed { i, line -> if (offending(line)) hits += "$rel:${i + 1} ${line.trim()}" }
        }
        assertEquals(emptyList(), hits, "use CanonicalJson.number / parseDouble / doubleOf")
    }
}
