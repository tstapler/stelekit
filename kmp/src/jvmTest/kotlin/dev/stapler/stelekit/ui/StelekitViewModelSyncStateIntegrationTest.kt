// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.buildTestGitSyncService
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `IT-4.3.2-A` (Epic 4.3, Story 4.3.2): with `localChangesCountFlow == null` — the default for
 * every existing call site (JVM/Android's [dev.stapler.stelekit.desktop.Main]'s `StelekitApp(...)`
 * call and every pre-Epic-4.3 test) — [StelekitViewModel.syncState] must be byte-for-byte
 * identical to the pre-feature `activeGitSyncService.flatMapLatest { ... }` output: it mirrors
 * whatever the active [GitSyncService] reports, and [SyncState.LocalChangesPending] is never
 * emitted, no matter what state the underlying service is in.
 *
 * This is the explicit no-regression-to-JVM/Android proof Success Metric #6 (plan.md) requires.
 */
class StelekitViewModelSyncStateIntegrationTest {

    private fun buildGitSyncService(gitRepository: GitRepository): GitSyncService =
        buildTestGitSyncService(gitRepository = gitRepository)

    /**
     * Builds a [StelekitViewModel] exactly as `jvmMain`'s `desktop/Main.kt` does today —
     * `localChangesCountFlow` is never passed, so it defaults to `null`
     * ([StelekitViewModelDependencies.localChangesCountFlow]'s default).
     */
    private fun makeViewModel(
        activeGitSyncService: MutableStateFlow<GitSyncService?>,
    ): StelekitViewModel {
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fileSystem = FakeFileSystem()
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                graphWriter = GraphWriter(fileSystem),
                platformSettings = InMemorySettings(),
                scope = scope,
                activeGitSyncService = activeGitSyncService,
                // localChangesCountFlow intentionally omitted — defaults to null, matching every
                // JVM/Android call site (desktop Main.kt never passes it).
            )
        )
    }

    private suspend fun awaitValue(vm: StelekitViewModel, expected: SyncState, timeoutMs: Long = 2_000) {
        withTimeout(timeoutMs) {
            var current = vm.syncState.value
            while (current != expected) {
                kotlinx.coroutines.delay(10)
                current = vm.syncState.value
            }
        }
    }

    @Test
    fun `IT-4_3_2-A syncState mirrors GitSyncService state exactly when localChangesCountFlow is null`() = runBlocking {
        val gitRepository = object : StubGitRepository() {
            override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> =
                GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/foo.md")).right()

            override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> =
                DomainError.GitError.CommitFailed("stage failed").left()
        }
        val service = buildGitSyncService(gitRepository)
        val activeGitSyncService = MutableStateFlow<GitSyncService?>(null)
        val vm = makeViewModel(activeGitSyncService)

        // 1. No active service — mirrors the pre-Epic-4.3 default: Idle.
        assertEquals(SyncState.Idle, vm.syncState.value)

        // 2. Activate the service — vm.syncState must mirror service.syncState exactly (both Idle).
        activeGitSyncService.value = service
        awaitValue(vm, SyncState.Idle)
        assertEquals(service.syncState.value, vm.syncState.value)

        // 3. Drive the real GitSyncService into a non-Idle, in-progress-shaped state
        // (Committing — same technique as StelekitViewModelSyncStateTest's TC-4.3.2-B) and
        // confirm the ViewModel's syncState mirrors it byte-for-byte — never
        // SyncState.LocalChangesPending, regardless of what count a hypothetical dirty-file
        // flow might report, because localChangesCountFlow is null here.
        service.commitLocalChanges("test-graph")
        awaitValue(vm, SyncState.Committing)
        assertEquals(
            service.syncState.value,
            vm.syncState.value,
            "syncState must be byte-for-byte identical to the raw GitSyncService output when " +
                "localChangesCountFlow is null",
        )
    }
}
