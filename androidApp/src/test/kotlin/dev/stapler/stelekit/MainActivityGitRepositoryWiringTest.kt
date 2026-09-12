// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.FileSystem
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for plan.md Task 5.1.2a: [buildGitRepository] must wire whatever [FileSystem]
 * instance it's given straight into [dev.stapler.stelekit.git.AndroidGitRepository] — never a
 * fresh, disconnected [dev.stapler.stelekit.platform.PlatformFileSystem]. Exercises the exact
 * production call site `MainActivity`'s `remember { buildGitRepository(applicationContext,
 * app.fileSystem) }` block calls, not a parallel/tautological construction.
 *
 * Plain JVM/Robolectric unit test — [buildGitRepository] is a plain function, no Compose test
 * rule or live Activity composition needed. `AndroidGitRepository.fileSystem` is public (not
 * `internal`) specifically so this cross-module (`:androidApp` depends on `:kmp`) assertion can
 * read it back — see [buildGitRepository]'s KDoc and `AndroidGitRepository.kt`'s `fileSystem`
 * property comment for why `internal` doesn't compile across that module boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MainActivityGitRepositoryWiringTest {

    /** Trivial stub — only needs to be a distinguishable instance for the `===` check below. */
    private class FakeFileSystem : FileSystem {
        override fun getDefaultGraphPath(): String = ""
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = false
        override fun directoryExists(path: String): Boolean = false
        override fun createDirectory(path: String): Boolean = false
        override fun deleteFile(path: String): Boolean = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    @Test
    fun `buildGitRepository wires the passed-in fileSystem instance, not a fresh one`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val fakeFileSystem = FakeFileSystem()

        val gitRepository = buildGitRepository(context, fakeFileSystem)

        assertSame(fakeFileSystem, gitRepository.fileSystem)
    }
}
