// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 1.2.4: no numeric cap outside [SceneLimits]. Scans the production sources of the engine (`sketch/v2`,
 * `sketch/excalidraw`, `sketch/constraints`, `ui/sketch/v2`) and `projectbridge` for a literal that looks like a cap
 * (right of a comparison, `take(`, `coerceAtMost(`, a `limit`/`max`/`cap`-named constant, a size/count check).
 * A non-cap numeric is listed with a reason in `scene-limits-allowlist.txt`; the list may only shrink (a stale line fails).
 */
class SceneLimitsGrepTest {
    private val scope = listOf("sketch/v2/", "sketch/excalidraw/", "sketch/constraints/", "ui/sketch/v2/", "projectbridge/")
    private val definition = "sketch/v2/scene/SceneLimits.kt"

    private val num = """-?(?:0x[0-9A-Fa-f_]+|\d[\d_]*(?:\.\d+)?(?:[eE]-?\d+)?)[LlFfuU]*"""
    private val patterns = listOf(
        Regex("""(?<![-=<>!])(?:<=|>=|<|>)\s*($num)(?![\w.])"""),
        Regex("""\b(?:take|takeLast|drop|limit|coerceAtMost|coerceIn)\(\s*($num)\b"""),
        Regex("""\bva[lr]\s+\w*(?:[Ll]imit|[Mm]ax|[Cc]ap)\w*\s*(?::\s*\w+\s*)?=\s*($num)\b"""),
        Regex("""\b(?:size|length|count)\s*(?:==|!=)\s*($num)\b"""),
    )

    /** Literals that are never a cap: zero, one, minus one and the identity floats. */
    private fun trivial(literal: String): Boolean = literal.trimEnd('L', 'l', 'F', 'f', 'u', 'U').replace("_", "").let {
        it in setOf("0", "1", "-1", "0.0", "1.0", "-1.0", "0.5")
    }

    private fun strip(line: String): String {
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return ""
        return t.replace(Regex(""""(?:[^"\\]|\\.)*""""), "\"\"").substringBefore("//")
    }

    private fun hits(): List<String> {
        val sources = WiringInputs.productionSources
        assertTrue(sources.isNotEmpty(), "no production sources loaded")
        val out = mutableListOf<String>()
        for ((key, text) in sources.toSortedMap()) {
            val rel = key.substringAfter("dev/stapler/stelekit/", "")
            if (rel.isEmpty() || rel == definition || scope.none { rel.startsWith(it) }) continue
            for (line in text.lineSequence()) {
                val code = strip(line)
                if (code.isEmpty()) continue
                val capLike = patterns.any { p -> p.findAll(code).any { !trivial(it.groupValues[1]) } }
                if (capLike) out += "$rel :: ${line.trim()}"
            }
        }
        return out.distinct()
    }

    private fun allowlist(): List<String> =
        WiringInputs.dataFile("scene-limits-allowlist.txt").lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringBeforeLast(" => ") }.toList()

    @Test
    fun limits_should_BeTheOnlyNumericCaps_When_SourcesGrepped() {
        val found = hits().toSet()
        val allowed = allowlist().toSet()
        val unlisted = found - allowed
        assertTrue(unlisted.isEmpty(), "numeric cap literal outside SceneLimits (move it to SceneLimits or allow-list with a reason):\n" + unlisted.joinToString("\n"))
    }

    @Test
    fun allowlist_should_OnlyShrink_When_EveryLineStillMatchesASource() {
        val found = hits().toSet()
        val stale = allowlist().toSet() - found
        assertTrue(stale.isEmpty(), "stale scene-limits-allowlist.txt lines (delete them):\n" + stale.joinToString("\n"))
        assertTrue(allowlist().size <= MAX_ALLOWLIST_ENTRIES, "allowlist grew past $MAX_ALLOWLIST_ENTRIES; it may only shrink")
    }

    @Test
    fun detector_should_FlagPlantedCaps_AndIgnoreTrivialNumbers() {
        val planted = listOf("if (n > 5000) fail()", "xs.take(200)", "val maxThings = 12", "x.coerceAtMost(64)", "if (list.size == 7) x")
        for (line in planted) assertTrue(patterns.any { p -> p.findAll(line).any { !trivial(it.groupValues[1]) } }, line)
        val benign = listOf("if (n > 0) ok()", "if (x < -1) y()", "val a = 3 + 4", "List<Int>", "a -> 5")
        for (line in benign) assertTrue(patterns.none { p -> p.findAll(line).any { !trivial(it.groupValues[1]) } }, line)
    }

    @Test
    fun sceneLimits_should_ListEveryConstructorField_InItsMap() {
        val fields = SceneLimits::class.java.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(fields.map { it.name }.sorted(), SceneLimits.Default.asMap().keys.sorted())
    }

    private companion object {
        const val MAX_ALLOWLIST_ENTRIES = 8
    }
}
