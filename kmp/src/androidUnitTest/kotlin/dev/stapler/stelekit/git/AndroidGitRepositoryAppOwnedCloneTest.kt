// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Story 2.2.2 (Task 2.2.2d): [AndroidGitRepository.shadowWorktreeFor]'s `AppOwned` branch — a
 * plain (non-`saf://`) clone destination must never trigger SAF resolution — and a regression
 * guard that the pre-existing SAF/"Browse…" clone path is untouched by that reorder. See
 * `project_plans/app-owned-storage-clone/implementation/plan.md` Epic 2.2, Story 2.2.2, and
 * `validation.md`'s REQ-2 mapping.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidGitRepositoryAppOwnedCloneTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(pathResolverCalls: MutableList<String>): AndroidGitRepository =
        AndroidGitRepository(
            context = context,
            sshKeyProvider = null,
            credentialAccess = FakeCredentialAccess(),
            pathResolver = { path -> pathResolverCalls.add(path); null },
            fileSystem = FakeSafFileSystem(),
        )

    @Test
    fun `shadowWorktreeFor should SkipSafResolutionAndUseAppOwnedBranch when LocationIsAppOwned`() {
        val pathResolverCalls = mutableListOf<String>()
        val repository = newRepository(pathResolverCalls)
        val appOwnedRepoRoot = context.filesDir.resolve("graphs/g-app-owned").absolutePath

        val worktree = repository.shadowWorktreeFor(appOwnedRepoRoot)

        assertNull(worktree, "an AppOwned (plain, non-saf://) repoRoot must never use a shadow worktree")
        assertEquals(
            emptyList(),
            pathResolverCalls,
            "pathResolver only ever resolves saf:// input — it must not be invoked at all for an AppOwned repoRoot",
        )
    }

    @Test
    fun `shadowWorktreeFor should PreserveExistingSafCloneBehaviorByteForByte when BrowseSelected`() {
        val pathResolverCalls = mutableListOf<String>()
        val repository = newRepository(pathResolverCalls)
        val safRepoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki"

        val worktree = repository.shadowWorktreeFor(safRepoRoot)

        assertNotNull(worktree, "a saf:// repoRoot picked via \"Browse…\" must still get a shadow worktree, exactly as before this Epic")
        assertEquals(listOf(safRepoRoot), pathResolverCalls, "the pre-existing saf:// path still consults pathResolver first")
    }
}
