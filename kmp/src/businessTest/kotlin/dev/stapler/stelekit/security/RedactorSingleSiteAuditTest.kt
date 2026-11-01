// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.security

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1 "One redactor": the secret-pattern prefixes appear in exactly one production file, so a
 * second scrubber (which would drift from the first) fails here. Tests are excluded by construction:
 * [WiringInputs.productionSources] holds only `*Main` source sets and `androidApp/src/main`.
 */
class RedactorSingleSiteAuditTest {
    private val prefixes = listOf("ghp_", "github_pat_", "glpat-", "AKIA")
    private val auditedSets = setOf("commonMain", "jvmCommonMain", "jvmMain", "androidMain", "wasmJsMain", "androidApp.main")

    private fun filesContainingAPrefix(sources: Map<String, String>): List<String> =
        sources.filterKeys { it.substringBefore('/') in auditedSets }
            .filterValues { text -> prefixes.any { it in text } }
            .keys.sorted()

    @Test
    fun secretPrefixes_should_AppearInExactlyOneProductionFile_And_ItIsTheRedactor() {
        val sources = WiringInputs.productionSources
        assertTrue(sources.keys.any { it.endsWith("security/Redactor.kt") }, "Redactor.kt must be in the audited sources")
        assertEquals(
            listOf("commonMain/dev/stapler/stelekit/security/Redactor.kt"),
            filesContainingAPrefix(sources),
        )
    }

    @Test
    fun audit_should_FlagASecondFile_When_AnotherScrubberCopiesAPrefix() {
        val withCopy = WiringInputs.productionSources +
            ("commonMain/dev/stapler/stelekit/util/Scrub.kt" to "val p = Regex(\"ghp_[a-z]+\")")
        assertEquals(2, filesContainingAPrefix(withCopy).size)
    }
}
