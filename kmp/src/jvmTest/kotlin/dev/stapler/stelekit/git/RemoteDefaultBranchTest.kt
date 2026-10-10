// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.testsupport.BareOriginFixtures
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteDefaultBranchTest {
    private val temps = mutableListOf<File>()
    private val repository = JvmGitRepository()

    @AfterTest
    fun tearDown() = temps.forEach { it.deleteRecursively() }

    private fun origin(branch: String, seed: Boolean = true) =
        BareOriginFixtures.createBareOrigin("stelekit_detect_", branch, seed).also { temps += it }

    @Test
    fun `origin whose HEAD is master is detected as master`() = runTest {
        val result = repository.detectDefaultBranch(origin("master").absolutePath, GitAuth.None)
        assertEquals(DefaultBranchDetection.Detected("master"), result)
    }

    @Test
    fun `two branches at the same commit with an unresolvable HEAD are ambiguous`() = runTest {
        val origin = origin("master")
        Git.open(origin).use { git ->
            val repo = git.repository
            val tip = repo.exactRef("refs/heads/master").objectId
            val create = repo.updateRef("refs/heads/main")
            create.setNewObjectId(tip)
            create.update()
            repo.updateRef(Constants.HEAD).link("refs/heads/trunk")
        }
        val result = repository.detectDefaultBranch(origin.absolutePath, GitAuth.None)
        assertEquals(DefaultBranchDetection.Ambiguous(listOf("main", "master")), result)
    }

    @Test
    fun `origin with no branches is EmptyRemote`() = runTest {
        val result = repository.detectDefaultBranch(origin("main", seed = false).absolutePath, GitAuth.None)
        assertEquals(DefaultBranchDetection.EmptyRemote, result)
    }

    @Test
    fun `an unreachable remote is Unreachable and never claims a missing branch`() = runTest {
        val result = repository.detectDefaultBranch("/nonexistent/stelekit/remote", GitAuth.None)
        assertTrue(result is DefaultBranchDetection.Unreachable, "got $result")
    }
}
