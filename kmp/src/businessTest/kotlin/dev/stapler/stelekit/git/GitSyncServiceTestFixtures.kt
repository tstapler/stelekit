// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository

/**
 * Shared [GitSyncService] test fixtures for `GitSyncServiceTest`, `GitSyncServiceErrorRoutingTest`,
 * and `GitSyncServiceConflictResolutionTest` — these three were originally one 751-line
 * `GitSyncServiceTest.kt` (kotlin-file-size finding); kept in one file here so the split doesn't
 * re-duplicate the same stub/config/builder boilerplate three times over.
 *
 * [StubFileSystem]/[StubConfigRepository]/[sampleConfig] live in `dev.stapler.stelekit.git.testsupport`
 * (`commonTest`), shared with `jvmTest`/`androidUnitTest` — see [StubGitRepository]'s KDoc for why.
 */

/** [CredentialAccess] stub that reports the vault as locked. */
internal object LockedCredentialAccess : CredentialAccess {
    override fun retrieve(key: String): String? = null
    override fun store(key: String, value: String) {}
    override fun delete(key: String) {}
    override fun isAvailable(): Boolean = false
}

/** Builds a [GitSyncService] wired with the provided stubs and safe no-op defaults. */
internal fun buildGitSyncTestService(
    gitRepository: GitRepository = StubGitRepository(),
    configRepository: GitConfigRepository,
    fileSystem: FileSystem = StubFileSystem(),
    networkMonitor: NetworkMonitor = NetworkMonitor(),
    credentialAccessProvider: (() -> CredentialAccess)? = null,
): GitSyncService {
    val stubFs = StubFileSystem()
    val graphLoader = GraphLoader(
        fileSystem = stubFs,
        pageRepository = InMemoryPageRepository(),
        blockRepository = InMemoryBlockRepository(),
    )
    val graphWriter = GraphWriter(fileSystem = fileSystem)
    return GitSyncService(
        gitRepository = gitRepository,
        graphLoader = graphLoader,
        graphWriter = graphWriter,
        editLock = EditLock(),
        configRepository = configRepository,
        networkMonitor = networkMonitor,
        fileSystem = fileSystem,
        credentialAccessProvider = credentialAccessProvider,
    )
}
