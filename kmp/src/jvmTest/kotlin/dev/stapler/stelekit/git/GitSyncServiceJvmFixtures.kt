// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem

/**
 * Builds a real [GitSyncService] with fake/stub collaborators — the same shape repeated,
 * byte-for-byte, across `StelekitViewModelSyncStateTest`, `StelekitViewModelSyncStateIntegrationTest`,
 * and `GitSetupScreenScreenshotTest` before this extraction.
 */
fun buildTestGitSyncService(
    gitRepository: GitRepository = StubGitRepository(),
    configRepository: GitConfigRepository = StubConfigRepository(sampleConfig.right()),
): GitSyncService {
    val fileSystem = FakeFileSystem()
    val graphLoader = GraphLoader(
        fileSystem = fileSystem,
        pageRepository = InMemoryPageRepository(),
        blockRepository = InMemoryBlockRepository(),
    )
    val graphWriter = GraphWriter(fileSystem)
    return GitSyncService(
        gitRepository = gitRepository,
        graphLoader = graphLoader,
        graphWriter = graphWriter,
        editLock = EditLock(),
        configRepository = configRepository,
        networkMonitor = NetworkMonitor(),
        fileSystem = fileSystem,
    )
}
