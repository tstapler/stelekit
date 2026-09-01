// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [AndroidGitRepository.shadowWorktreeFor]'s direct-access-wins decision logic (Task
 * 8.2.2a) and [GitShadowWorktree.sweepOrphans]'s time-based orphan sweep (Task 8.2.2b), per
 * `project_plans/android-git-saf-shadow-worktree/implementation/plan.md` Epic 8.2, Story 8.2.2.
 */
@RunWith(RobolectricTestRunner::class)
class GitPathResolverChainTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(pathResolver: (String) -> String?): AndroidGitRepository =
        AndroidGitRepository(
            context = context,
            sshKeyProvider = null,
            credentialAccess = FakeCredentialAccess(),
            pathResolver = pathResolver,
            fileSystem = FakeSafFileSystem(),
        )

    // ── Task 8.2.2a: shadowWorktreeFor() decision logic ─────────────────────────────────────

    @Test
    fun `shadowWorktreeFor returns null when pathResolver resolves the repoRoot directly`() {
        val repository = newRepository(pathResolver = { "/real/direct/path" })
        assertNull(repository.shadowWorktreeFor("saf://content%3A%2F%2Ftree%2Fprimary%3Awiki"))
    }

    @Test
    fun `shadowWorktreeFor returns null for a non-saf repoRoot even when pathResolver returns null`() {
        val repository = newRepository(pathResolver = { null })
        assertNull(repository.shadowWorktreeFor("/local/real/path"))
    }

    @Test
    fun `shadowWorktreeFor returns a cached non-null shadow worktree when pathResolver returns null for a saf root`() {
        val repository = newRepository(pathResolver = { null })
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki"

        val first = repository.shadowWorktreeFor(repoRoot)
        assertNotNull(first)
        assertTrue(File(first.worktreeRootPath).isDirectory)

        val second = repository.shadowWorktreeFor(repoRoot)
        assertSame(first, second, "shadowWorktreeFor must cache and return the same instance for the same repoRoot")
    }

    @Test
    fun `shadowWorktreeFor produces distinct instances for repoRoots sharing a tree uri but different subpaths`() {
        val repository = newRepository(pathResolver = { null })
        val treeUri = "content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki"
        val rootA = "saf://$treeUri/subdirA"
        val rootB = "saf://$treeUri/subdirB"

        val worktreeA = repository.shadowWorktreeFor(rootA)
        val worktreeB = repository.shadowWorktreeFor(rootB)
        assertNotNull(worktreeA)
        assertNotNull(worktreeB)
        assertNotEquals(worktreeA.shadowKey, worktreeB.shadowKey, "Blocker 3 regression: distinct subpaths must not collide")
        assertNotEquals(worktreeA.worktreeRootPath, worktreeB.worktreeRootPath)
    }

    // ── Task 8.2.2b: sweepOrphans preserves recently-used, deletes stale, spares markerless ──

    @Test
    fun `sweepOrphans deletes only the stale directory and preserves the recently-used and markerless ones`() {
        val graphsDir = File(context.filesDir, "graphs")

        val freshGraphDir = File(graphsDir, "fresh-key")
        val freshShadowDir = File(freshGraphDir, "gitshadow").apply { mkdirs() }
        File(freshGraphDir, ".last-used").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis())
        }

        val staleGraphDir = File(graphsDir, "stale-key")
        val staleShadowDir = File(staleGraphDir, "gitshadow").apply { mkdirs() }
        File(staleGraphDir, ".last-used").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis() - 60_000L) // 60s in the past
        }

        val markerlessGraphDir = File(graphsDir, "markerless-key")
        val markerlessShadowDir = File(markerlessGraphDir, "gitshadow").apply { mkdirs() }
        // Deliberately no ".last-used" sibling — e.g. a fresh clone that crashed before its first
        // real git operation.

        // A short window: well above the "fresh" marker's age (~0ms), well below the "stale"
        // marker's age (~60s), so this is an unambiguous partition, not a timing-sensitive one.
        val shortMaxAgeMillis = 5_000L
        GitShadowWorktree.sweepOrphans(context, shortMaxAgeMillis)

        assertTrue(freshShadowDir.exists(), "recently-touched shadow directory must survive the sweep")
        assertFalse(staleShadowDir.exists(), "stale shadow directory must be deleted by the sweep")
        assertTrue(
            markerlessShadowDir.exists(),
            "a gitshadow directory with no .last-used marker must NOT be deleted — ambiguous absence is never eager evidence of staleness",
        )
    }
}
