// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.2.1d: the pure engine (everything under `sketch/v2/` except `sketch/v2/runtime/`) imports nothing from `ui`, `db`,
 * `repository`, `projectbridge`, `merge` or `platform`, and declares no `CoroutineScope` or `launch`.
 * Everything that does lives in `sketch/v2/runtime` (RT).
 */
class SketchV2EngineImportDirectionTest {
    private val enginePrefix = "sketch/v2/"
    private val runtimePrefix = "sketch/v2/runtime/"
    private val forbiddenImport = Regex("""(?m)^\s*import\s+dev\.stapler\.stelekit\.(ui|db|repository|projectbridge|merge|platform)\b""")
    private val scopeUse = Regex("""\bCoroutineScope\s*\(|\bGlobalScope\b|\.launch\s*[({]|(?<![\w.])launch\s*[({]|\brememberCoroutineScope\b""")

    private fun engineFiles(sources: Map<String, String>): Map<String, String> =
        sources.mapKeys { it.key.substringAfter("dev/stapler/stelekit/", "") }
            .filterKeys { it.startsWith(enginePrefix) && !it.startsWith(runtimePrefix) }

    private fun violations(sources: Map<String, String>): List<String> =
        engineFiles(sources).filter { (_, text) -> forbiddenImport.containsMatchIn(text) || scopeUse.containsMatchIn(stripComments(text)) }.keys.sorted()

    private fun stripComments(text: String): String =
        text.lineSequence().filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }.joinToString("\n")

    @Test
    fun detector_should_FlagPlantedImportAndScope_And_IgnoreRuntimeAndComments() {
        val planted = mapOf(
            "commonMain/kotlin/dev/stapler/stelekit/sketch/v2/scene/Bad.kt" to "import dev.stapler.stelekit.db.GraphManager\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/v2/commands/Bad2.kt" to "val s = CoroutineScope(Dispatchers.Default)\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/v2/commands/Bad3.kt" to "fun f(s: Any) { s.launch { } }\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/v2/runtime/Fine.kt" to "import dev.stapler.stelekit.db.GraphManager\nval s = CoroutineScope(x)\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/v2/scene/Doc.kt" to "// uses CoroutineScope( in prose\n * launch { } too\n",
        )
        assertEquals(
            listOf("sketch/v2/commands/Bad2.kt", "sketch/v2/commands/Bad3.kt", "sketch/v2/scene/Bad.kt"),
            violations(planted),
        )
    }

    @Test
    fun engine_should_HaveNoForbiddenImportsOrScopes_When_RealSourcesScanned() {
        val engine = engineFiles(WiringInputs.productionSources)
        assertTrue(engine.keys.any { it.startsWith("sketch/v2/scene/") }, "scene sources not loaded")
        assertEquals(emptyList(), violations(WiringInputs.productionSources))
    }
}
