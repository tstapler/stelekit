// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.6.7 (engineering E2): producers emit, the queue orders, the slot renders. No producer imports `BannerSlot`,
 * `BannerSlot` imports no producer, and no `BannerCandidate` is declared outside `sketch/v2/diagnostics`.
 * A producer is a source under `sketch/v2/runtime`, `sketch/v2/bridge`, `sketch/v2/commands`, `sketch/v2/constraints`,
 * `sketch/excalidraw` or `projectbridge`, or any file outside `ui` that uses `DiagnosticsPort`.
 */
class BannerOwnershipTest {
    private val producerPrefixes = listOf(
        "sketch/v2/runtime/", "sketch/v2/bridge/", "sketch/v2/commands/", "sketch/v2/constraints/", "sketch/excalidraw/", "projectbridge/",
    )
    private val diagnosticsPrefix = "sketch/v2/diagnostics/"
    private val slotName = Regex("""\bBannerSlot\b""")
    private val slotDeclaration = Regex("""\b(?:fun|class|object|interface)\s+BannerSlot\b""")
    private val candidateDeclaration = Regex("""\b(?:class|interface|object)\s+BannerCandidate\b""")
    private val producerImport = Regex("""(?m)^\s*import\s+dev\.stapler\.stelekit\.(?:sketch\.v2\.(?:runtime|bridge|commands|constraints)|sketch\.excalidraw|projectbridge)\b""")

    private fun relative(sources: Map<String, String>): Map<String, String> =
        sources.mapKeys { it.key.substringAfter("dev/stapler/stelekit/", "") }.filterKeys { it.isNotEmpty() }

    private fun code(text: String): String =
        text.lineSequence().filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }.joinToString("\n")

    private fun isProducer(path: String, text: String): Boolean =
        producerPrefixes.any { path.startsWith(it) } || (!path.startsWith("ui/") && !path.startsWith(diagnosticsPrefix) && "DiagnosticsPort" in text)

    private fun producersImportingSlot(sources: Map<String, String>): List<String> =
        relative(sources).filter { (path, text) -> isProducer(path, text) && slotName.containsMatchIn(code(text)) }.keys.sorted()

    private fun slotImportingProducers(sources: Map<String, String>): List<String> =
        relative(sources).filter { (_, text) -> slotDeclaration.containsMatchIn(code(text)) && producerImport.containsMatchIn(text) }.keys.sorted()

    private fun candidatesDeclaredElsewhere(sources: Map<String, String>): List<String> =
        relative(sources).filter { (path, text) -> !path.startsWith(diagnosticsPrefix) && candidateDeclaration.containsMatchIn(code(text)) }.keys.sorted()

    @Test
    fun detectors_should_FlagPlantedViolations_And_IgnoreCommentsAndTheDiagnosticsPackage() {
        val base = "commonMain/kotlin/dev/stapler/stelekit/"
        val planted = mapOf(
            "${base}sketch/v2/runtime/persist/BadSaver.kt" to "import dev.stapler.stelekit.ui.sketch.v2.BannerSlot\nval x = BannerSlot\n",
            "${base}sketch/v2/runtime/persist/Fine.kt" to "// BannerSlot renders it\n",
            "${base}projectbridge/Bad.kt" to "fun f(port: DiagnosticsPort) { BannerSlot }\n",
            "${base}ui/sketch/v2/BannerSlot.kt" to "import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver\nfun BannerSlot() {}\n",
            "${base}ui/sketch/v2/Other.kt" to "class BannerCandidate\n",
            "${base}sketch/v2/diagnostics/BannerCandidate.kt" to "class BannerCandidate private constructor()\n",
            "${base}ui/sketch/v2/Queue.kt" to "fun f(c: BannerCandidate) {}\nval p: DiagnosticsPort? = null\n",
        )
        assertEquals(listOf("projectbridge/Bad.kt", "sketch/v2/runtime/persist/BadSaver.kt"), producersImportingSlot(planted))
        assertEquals(listOf("ui/sketch/v2/BannerSlot.kt"), slotImportingProducers(planted))
        assertEquals(listOf("ui/sketch/v2/Other.kt"), candidatesDeclaredElsewhere(planted))
    }

    @Test
    fun ownership_should_HoldInTheRealSources() {
        val sources = WiringInputs.productionSources
        assertTrue(relative(sources).keys.any { it.startsWith(diagnosticsPrefix) }, "diagnostics sources not loaded")
        assertEquals(emptyList(), producersImportingSlot(sources), "a producer references BannerSlot")
        assertEquals(emptyList(), slotImportingProducers(sources), "BannerSlot imports a producer")
        assertEquals(emptyList(), candidatesDeclaredElsewhere(sources), "BannerCandidate is declared outside sketch/v2/diagnostics")
    }
}
