// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitCommit
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.MergeResult
import dev.stapler.stelekit.git.MergeSide
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphManagerCloneTest {

    /** Minimal Settings stub backed by an in-memory map. */
    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Minimal FileSystem stub — all operations return safe defaults. */
    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    /**
     * Minimal fake GitRepository — every method throws [UnsupportedOperationException]
     * except [clone], which returns [cloneResult].
     */
    private class FakeGitRepository(
        private val cloneResult: Either<DomainError.GitError, Unit>,
    ) : GitRepository {
        override suspend fun clone(
            url: String,
            localPath: String,
            auth: GitAuth,
            onProgress: (String) -> Unit,
        ): Either<DomainError.GitError, Unit> = cloneResult

        override suspend fun isGitRepo(path: String): Boolean = throw UnsupportedOperationException()
        override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> = throw UnsupportedOperationException()
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = throw UnsupportedOperationException()
        override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> = throw UnsupportedOperationException()
        override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = throw UnsupportedOperationException()
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> = throw UnsupportedOperationException()
        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> = throw UnsupportedOperationException()
        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> = throw UnsupportedOperationException()
        override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> = throw UnsupportedOperationException()
        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> = throw UnsupportedOperationException()
        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> = throw UnsupportedOperationException()
        override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> = throw UnsupportedOperationException()
        override suspend fun hasDetachedHead(config: GitConfig): Boolean = throw UnsupportedOperationException()
        override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> = throw UnsupportedOperationException()
    }

    private fun graphManager(): GraphManager = GraphManager(
        platformSettings = StubSettings(),
        driverFactory = DriverFactory(),
        fileSystem = StubFileSystem(),
        defaultBackend = GraphBackend.IN_MEMORY,
    )

    @Test
    fun `cloneAndAdd returns graphId on success`() = runTest {
        val gm = graphManager()
        val fakeGit = FakeGitRepository(cloneResult = Unit.right())

        val result = gm.cloneAndAdd(
            gitRepository = fakeGit,
            url = "https://example.com/repo.git",
            localPath = "/tmp/cloned-graph",
            auth = GitAuth.None,
            onProgress = {},
        )

        assertIs<Either.Right<String>>(result, "Expected Right but got: $result")
        val graphId = result.value
        assertTrue(graphId.isNotEmpty(), "graphId should be a non-empty string")

        val registry = gm.graphRegistry.value
        assertTrue(
            registry.graphs.any { it.id == graphId },
            "Graph with id=$graphId should appear in registry after cloneAndAdd"
        )
    }

    @Test
    fun `cloneAndAdd propagates clone failure without registering graph`() = runTest {
        val gm = graphManager()
        val expectedError = DomainError.GitError.CloneFailed("network error")
        val fakeGit = FakeGitRepository(cloneResult = expectedError.left())

        val graphCountBefore = gm.graphRegistry.value.graphs.size

        val result = gm.cloneAndAdd(
            gitRepository = fakeGit,
            url = "https://example.com/bad-repo.git",
            localPath = "/tmp/bad-clone",
            auth = GitAuth.None,
            onProgress = {},
        )

        assertIs<Either.Left<DomainError.GitError>>(result, "Expected Left but got: $result")
        assertEquals(expectedError, result.value, "Propagated error should match the one returned by clone")

        val graphCountAfter = gm.graphRegistry.value.graphs.size
        assertFalse(
            gm.graphRegistry.value.graphs.any { it.path == "/tmp/bad-clone" },
            "Failed clone must not register a new graph"
        )
        assertEquals(graphCountBefore, graphCountAfter, "Graph count should not change after a failed clone")
    }
}
