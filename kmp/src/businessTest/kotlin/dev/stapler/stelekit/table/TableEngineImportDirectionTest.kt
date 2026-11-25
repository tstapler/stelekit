// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.table

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** REQ-22/REQ-25: the table package tree stays pure, and package `table` never reaches into `table.formula`. */
class TableEngineImportDirectionTest {
    private val forbidden = Regex(
        """(?m)^\s*import\s+(dev\.stapler\.stelekit\.(ui|db|git|merge)\b|androidx\.)""",
    )
    private val formulaImport = Regex("""(?m)^\s*import\s+dev\.stapler\.stelekit\.table\.formula\b""")
    private val tablePackage = Regex("""(?m)^package\s+dev\.stapler\.stelekit\.table\s*$""")
    private val tableDir = "/dev/stapler/stelekit/table/"

    private fun layerViolations(sources: Map<String, String>): List<String> =
        sources.filter { (path, text) -> tableDir in path && forbidden.containsMatchIn(text) }.keys.toList()

    private fun directionViolations(sources: Map<String, String>): List<String> =
        sources.filter { (_, text) -> tablePackage.containsMatchIn(text) && formulaImport.containsMatchIn(text) }.keys.toList()

    private fun tableSources(): Map<String, String> =
        WiringInputs.productionSources.filterKeys { it.startsWith("commonMain/") && tableDir in it }

    @Test
    fun engine_should_ImportNoUiDbGitMergeOrAndroidx_When_ScanningTableSources() {
        val planted = mapOf(
            "/dev/stapler/stelekit/table/Bad.kt" to
                "package dev.stapler.stelekit.table\nimport dev.stapler.stelekit.ui.components.WikiLinkText\n",
        )
        assertEquals(1, layerViolations(planted).size)
        val planted2 = mapOf(
            "/dev/stapler/stelekit/table/formula/Bad.kt" to "import androidx.compose.runtime.Composable\n",
        )
        assertEquals(1, layerViolations(planted2).size)
        assertEquals(emptyList(), layerViolations(tableSources()))
    }

    @Test
    fun allowedImports_should_NotBeReported() {
        val ok = mapOf(
            "/dev/stapler/stelekit/table/Ok.kt" to
                "package dev.stapler.stelekit.table\nimport dev.stapler.stelekit.table.formula.Expr\nimport kotlin.math.max\n",
        )
        assertEquals(emptyList(), layerViolations(ok))
    }

    @Test
    fun tablePackage_should_NotImportTableFormula_When_Scanning() {
        val planted = mapOf(
            "/dev/stapler/stelekit/table/Bad.kt" to
                "package dev.stapler.stelekit.table\nimport dev.stapler.stelekit.table.formula.RefRewriter\n",
        )
        assertEquals(1, directionViolations(planted).size)
        val formulaToTable = mapOf(
            "/dev/stapler/stelekit/table/formula/Ok.kt" to
                "package dev.stapler.stelekit.table.formula\nimport dev.stapler.stelekit.table.CellAddress\n",
        )
        assertEquals(emptyList(), directionViolations(formulaToTable))
        assertEquals(emptyList(), directionViolations(tableSources()))
    }

    @Test
    fun scanner_should_SeeTableAndFormulaSources() {
        val keys = tableSources().keys
        assertTrue(keys.any { it.endsWith("/table/CellAddress.kt") }, "table/ sources not bundled: $keys")
        assertTrue(keys.any { "/table/formula/" in it }, "table/formula/ sources not bundled")
    }
}
