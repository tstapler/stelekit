// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 4.1.3d (ADR-003 item 4): `sketch.constraints` imports nothing from `ui`, `db`, `repository` or `merge` (nor
 * `projectbridge`, `platform`), and declares no `CoroutineScope`; mirrors `JobsScheduleImportDirectionTest`.
 * Also the single home of the not-constrainable wording (Task 4.2.1d): a second copy of the string fails here.
 */
class SketchSolverImportDirectionTest {
    private val prefix = "sketch/constraints/"
    private val forbiddenImport = Regex("""(?m)^\s*import\s+dev\.stapler\.stelekit\.(ui|db|repository|projectbridge|merge|platform)\b""")
    private val scopeUse = Regex("""\bCoroutineScope\s*\(|\bGlobalScope\b|\.launch\s*[({]|(?<![\w.])launch\s*[({]""")

    private fun solverFiles(sources: Map<String, String>): Map<String, String> =
        sources.mapKeys { it.key.substringAfter("dev/stapler/stelekit/", "") }.filterKeys { it.startsWith(prefix) }

    private fun stripComments(text: String): String =
        text.lineSequence().filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }.joinToString("\n")

    private fun violations(sources: Map<String, String>): List<String> =
        solverFiles(sources).filter { (_, text) -> forbiddenImport.containsMatchIn(text) || scopeUse.containsMatchIn(stripComments(text)) }.keys.sorted()

    @Test
    fun detector_should_FlagPlantedImportAndScope_AndIgnoreComments() {
        val planted = mapOf(
            "commonMain/kotlin/dev/stapler/stelekit/sketch/constraints/Bad.kt" to "import dev.stapler.stelekit.db.GraphManager\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/constraints/Bad2.kt" to "val s = CoroutineScope(Dispatchers.Default)\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/constraints/Doc.kt" to "// CoroutineScope( in prose\n * launch { } too\n",
            "commonMain/kotlin/dev/stapler/stelekit/sketch/v2/Elsewhere.kt" to "import dev.stapler.stelekit.db.GraphManager\n",
        )
        assertEquals(listOf("sketch/constraints/Bad.kt", "sketch/constraints/Bad2.kt"), violations(planted))
    }

    @Test
    fun solver_should_HaveNoForbiddenImportsOrScopes_When_RealSourcesScanned() {
        val files = solverFiles(WiringInputs.productionSources)
        assertTrue(files.keys.any { it == "sketch/constraints/Solver.kt" }, "solver sources not loaded: ${files.keys}")
        assertTrue(files.keys.any { it.startsWith("sketch/constraints/linalg/") })
        assertEquals(emptyList(), violations(WiringInputs.productionSources))
    }

    @Test
    fun notConstrainableWording_should_ExistInExactlyOneProductionSource() {
        val wording = "can't be constrained. Use align and distribute"
        val holders = WiringInputs.productionSources.filterValues { wording in it }.keys.map { it.substringAfter("dev/stapler/stelekit/") }
        assertEquals(listOf("sketch/v2/constraints/ConstraintEligibility.kt"), holders)
    }
}
