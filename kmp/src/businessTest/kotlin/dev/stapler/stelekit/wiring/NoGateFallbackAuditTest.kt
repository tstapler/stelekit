// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A private `RepoOpGate()` anywhere but a composition root would give that caller its own exclusion domain
 * and silently defeat single-flight, so main sources may construct one only in [WiringRoots] (ADR-001).
 */
class NoGateFallbackAuditTest {
    companion object {
        /** A clone worker that starts before `Application.onCreate` has no peer to exclude, so it logs and uses a private gate. */
        private const val CLONE_WORKER_PRIVATE_GATE = "androidMain/dev/stapler/stelekit/git/GitCloneWorker.kt"

        private val CONSTRUCTION = Regex("""\bRepoOpGate\(\)""")

        /** Non-root files that construct a gate; comment lines are ignored. */
        fun violations(sources: Map<String, String>, roots: Set<String>): List<String> =
            sources.filter { (path, text) ->
                path !in roots && text.lineSequence().any { line ->
                    val code = line.trimStart()
                    !code.startsWith("//") && !code.startsWith("*") && !code.startsWith("/*") && CONSTRUCTION.containsMatchIn(code)
                }
            }.keys.sorted()
    }

    @Test
    fun repoOpGate_should_BeConstructedOnlyInCompositionRoots() {
        val sources = WiringInputs.productionSources
        val roots = WiringRoots.resolve(sources).requireComplete().keys

        assertEquals(emptyList(), violations(sources, roots + CLONE_WORKER_PRIVATE_GATE), "RepoOpGate() outside a composition root")
        assertTrue(
            sources.filterKeys { it in roots }.values.any { CONSTRUCTION.containsMatchIn(it) },
            "no root constructs the gate; an empty scan would pass vacuously",
        )
    }

    @Test
    fun violations_should_NameAFallbackConstruction_When_PlantedOutsideARoot() {
        val planted = mapOf(
            "commonMain/dev/x/Fallback.kt" to "val g = deps.gate ?: RepoOpGate()\n",
            "commonMain/dev/x/Comment.kt" to "// RepoOpGate() is built in the roots\n",
            "jvmMain/dev/x/Main.kt" to "val g = RepoOpGate()\n",
        )

        assertEquals(listOf("commonMain/dev/x/Fallback.kt"), violations(planted, setOf("jvmMain/dev/x/Main.kt")))
    }
}
