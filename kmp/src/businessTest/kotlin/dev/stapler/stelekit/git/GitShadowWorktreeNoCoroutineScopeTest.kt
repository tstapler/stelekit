// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.git

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural enforcement for ADR-019's `GitShadowWorktree` equivalence claim (Epic 4.1, Story
 * 4.1.1): `GitShadowWorktree` needs no `CoroutineScope`/scope-cancellation lifecycle because every
 * `suspend fun` runs inline under `GitWorktreeLocks.lockFor(shadowKey)` and its per-graph isolation
 * comes from `AndroidGitRepository.shadowWorktreeFor`'s content-hash-keyed map, not from cancelling
 * a scope. That claim is "directly verifiable by reading the file" today, but nothing pinned it
 * against a future PR quietly adding a `CoroutineScope` field or a `.launch`/`.async` call — at
 * which point ADR-019's documented equivalence would go stale without anyone noticing. This test
 * reads the source text directly (same idiom as [dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest]
 * — structural assertion over source/declared members, not executed behavior) and fails loudly if
 * either half of the claim stops being true.
 *
 * Lives in `businessTest` rather than `androidUnitTest`, following the same source-set precedent as
 * `MigrationRunnerSchemaSyncTest`: this is a plain-text/regex check that runs on the JVM and needs
 * no Android runtime, so it reads `GitShadowWorktree.kt` (an `androidMain` file) as a file on disk
 * rather than compiling against it.
 */
class GitShadowWorktreeNoCoroutineScopeTest {

    /** Walks up from a classpath resource to the repo root, then resolves the androidMain source file. */
    private val sourceFile: File by lazy {
        val resource = javaClass.classLoader.getResource("demo-graph/pages")
            ?: fail("demo-graph/pages not found on classpath — check that commonMain resources are on the test classpath")
        var dir = File(resource.toURI())
        while (dir != dir.parentFile) {
            val candidate = dir.resolve(
                "src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt"
            )
            if (candidate.exists()) return@lazy candidate
            dir = dir.parentFile
        }
        fail("Could not locate GitShadowWorktree.kt walking up from: $resource")
    }

    private val source: String by lazy { sourceFile.readText() }

    @Test
    fun `GitShadowWorktree declares no CoroutineScope-typed property`() {
        // Matches both an explicitly-typed property ('val scope: CoroutineScope') and a
        // constructor-call property whose type is inferred ('val scope = CoroutineScope(...)').
        val scopeProperty = Regex(
            """(val|var)\s+\w+\s*(:\s*CoroutineScope\b|=\s*CoroutineScope\s*\()"""
        )
        val matches = scopeProperty.findAll(source).map { it.value.trim() }.toList()

        assertTrue(
            matches.isEmpty(),
            "GitShadowWorktree.kt now declares a CoroutineScope-typed property (${matches.joinToString()}), " +
                "contradicting ADR-019's documented equivalence: 'GitShadowWorktree owns no " +
                "CoroutineScope and launches no coroutines — every suspend fun runs inline under " +
                "GitWorktreeLocks.lockFor(shadowKey)'. Either revert this field, or update ADR-019's " +
                "GitShadowWorktree section (docs/adr/ADR-019-graph-scoped-session-lifecycle.md) to " +
                "document the new lifecycle shape instead of letting the claim go silently stale."
        )
    }

    @Test
    fun `GitShadowWorktree calls no dot-launch or dot-async coroutine builder`() {
        val coroutineBuilderCall = Regex("""\.(launch|async)\s*\(""")
        val matches = coroutineBuilderCall.findAll(source).map { it.value }.toList()

        assertTrue(
            matches.isEmpty(),
            "GitShadowWorktree.kt now calls a coroutine builder (${matches.joinToString()}), " +
                "contradicting ADR-019's documented equivalence that every suspend fun in this class " +
                "runs inline under GitWorktreeLocks.lockFor(shadowKey) rather than launching detached " +
                "work. Either remove the call, or update ADR-019's GitShadowWorktree section " +
                "(docs/adr/ADR-019-graph-scoped-session-lifecycle.md) to document the new lifecycle shape."
        )
    }
}
