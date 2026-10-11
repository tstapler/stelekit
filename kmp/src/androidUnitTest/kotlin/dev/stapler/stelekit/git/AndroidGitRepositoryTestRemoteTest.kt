// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PR #351 review Finding B: [AndroidGitRepository.testRemote] had zero coverage — only its JVM
 * equivalent ([dev.stapler.stelekit.git.JvmGitRepositoryTest]'s `testRemote` tests) was exercised.
 * Ports the same two scenarios (success against a real, reachable remote with no local clone
 * present; failure against a nonexistent remote) to Android via Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidGitRepositoryTestRemoteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): AndroidGitRepository =
        AndroidGitRepository(
            context = context,
            sshKeyProvider = null,
            credentialAccess = FakeCredentialAccess(),
            pathResolver = { null },
            fileSystem = FakeSafFileSystem(),
        )

    @Test
    fun `testRemote succeeds against a remote with no local clone present`() = runTest {
        val bareOrigin = createTempDirectory("stelekit_android_git_bare_origin_").toFile()
        Git.init().setBare(true).setDirectory(bareOrigin).setInitialBranch("main").call().close()

        val destination = File(context.filesDir, "not-cloned-yet")
        assertFalse(destination.exists(), "destination must not exist — that's the bug this test guards against")

        val repository = newRepository()
        val result = repository.testRemote(bareOrigin.absolutePath, GitAuth.None)

        assertTrue(result.isRight(), "testRemote failed against a real, empty bare remote: $result")
        assertFalse(destination.exists(), "testRemote must not create/touch the future clone destination")
    }

    @Test
    fun `testRemote reports failure for a nonexistent remote`() = runTest {
        val missingRemote = File(context.filesDir, "does-not-exist").absolutePath

        val repository = newRepository()
        val result = repository.testRemote(missingRemote, GitAuth.None)

        assertTrue(result.isLeft(), "expected testRemote to fail against a nonexistent remote")
    }
}
