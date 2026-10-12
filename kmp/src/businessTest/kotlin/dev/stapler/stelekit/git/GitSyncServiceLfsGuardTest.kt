// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.lfs.LfsGuardCoordinator
import dev.stapler.stelekit.git.lfs.LfsMode
import dev.stapler.stelekit.git.lfs.LfsModeSource
import dev.stapler.stelekit.git.lfs.LfsOid
import dev.stapler.stelekit.git.lfs.LfsPlatformCapabilities
import dev.stapler.stelekit.git.lfs.LfsPointer
import dev.stapler.stelekit.git.lfs.LfsPointerCodec
import dev.stapler.stelekit.git.lfs.LfsViolation
import dev.stapler.stelekit.git.lfs.LfsViolationReason
import dev.stapler.stelekit.git.lfs.MemoryFileSystem
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The LFS safety floor at the service level: on every staging entry point of [GitSyncService] a raw binary at a
 * path `.gitattributes` stores with LFS is held back (sync, commit) or refused (merge resolution), never staged.
 */
class GitSyncServiceLfsGuardTest {
    private val graphId = sampleConfig.graphId
    private val photo = "assets/photo.png"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(3_000)
    private val pointer = LfsPointerCodec.serialize(
        LfsPointer(LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"), 3_000_000),
    )

    private class MapSettings : Settings {
        val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class RecordingRepo(
        var status: GitStatus,
        var staged: List<LfsViolation> = emptyList(),
        override var cleanFilterActive: Boolean = false,
        var stagedThrows: Boolean = false,
        var anythingStaged: Boolean = true,
    ) : StubGitRepository() {
        val stageCalls = mutableListOf<Set<String>>()
        val commits = mutableListOf<String>()
        val pushes = IntArray(1)
        val resolved = mutableListOf<String>()

        override suspend fun status(config: GitConfig) = status.right()
        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> {
            stageCalls += excludePaths
            return Unit.right()
        }
        override suspend fun stagedLfsViolations(config: GitConfig): List<LfsViolation> {
            if (stagedThrows) error("index unreadable")
            return staged
        }
        override suspend fun hasStagedChanges(config: GitConfig) = anythingStaged
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
            commits += message
            return "abc123".right()
        }
        override suspend fun fetch(config: GitConfig) = FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
            pushes[0]++
            return Unit.right()
        }
        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide) = Unit.right()
        override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> {
            resolved += filePath
            return Unit.right()
        }
    }

    private class Fixture(val service: GitSyncService, val repo: RecordingRepo, val fs: MemoryFileSystem, val settings: MapSettings)

    private fun fixture(
        modified: List<String> = emptyList(),
        untracked: List<String> = emptyList(),
        files: Map<String, ByteArray> = emptyMap(),
        gitattributes: String? = "assets/** filter=lfs\n",
        coordinator: LfsGuardCoordinator? = null,
        configure: (RecordingRepo) -> Unit = {},
        capabilities: LfsPlatformCapabilities = LfsPlatformCapabilities.GUARD_ONLY_PLATFORM,
    ): Fixture {
        val fs = MemoryFileSystem(files.mapKeys { "/repo/${it.key}" }.toMutableMap())
        gitattributes?.let { fs.files["/repo/.gitattributes"] = it.encodeToByteArray() }
        val repo = RecordingRepo(GitStatus(true, untracked, modified)).also(configure)
        val settings = MapSettings()
        val stub = StubFileSystem()
        val service = GitSyncService(
            gitRepository = repo,
            graphLoader = GraphLoader(stub, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = stub),
            editLock = EditLock(),
            configRepository = StubConfigRepository(sampleConfig.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = graphId,
            settings = settings,
            lfsCapabilities = capabilities,
            lfsGuardCoordinator = coordinator,
            isOnline = { true },
        )
        return Fixture(service, repo, fs, settings)
    }

    @Test
    fun sync_should_HoldRawBinaryAndCommitTheRest_When_AssetsAreLfsTracked() = runBlocking {
        val f = fixture(modified = listOf("pages/a.md", photo), files = mapOf("pages/a.md" to "# a".encodeToByteArray(), photo to png))

        val result = f.service.sync(graphId)

        assertTrue(result.isRight(), "a held file must not fail the sync: $result")
        assertEquals(listOf(setOf(photo)), f.repo.stageCalls)
        assertEquals(1, f.repo.commits.size)
        assertEquals(1, f.repo.pushes[0])
        val state = assertIs<SyncState.LfsActionRequired>(f.service.syncState.value)
        assertEquals(listOf(photo), state.held.map { it.path })
        assertEquals(LfsViolationReason.RawBinary, state.held.single().reason)
        f.service.shutdown()
    }

    @Test
    fun sync_should_HoldUntrackedRawBinaryToo_When_FileIsNew() = runBlocking {
        val f = fixture(untracked = listOf(photo), modified = listOf("pages/a.md"), files = mapOf(photo to png))

        f.service.sync(graphId)

        assertEquals(listOf(setOf(photo)), f.repo.stageCalls)
        f.service.shutdown()
    }

    @Test
    fun sync_should_HoldNothing_When_TrackedFileIsAValidPointer() = runBlocking {
        val f = fixture(modified = listOf(photo), files = mapOf(photo to pointer))

        f.service.sync(graphId)

        assertEquals(listOf(emptySet<String>()), f.repo.stageCalls)
        assertEquals(1, f.repo.commits.size)
        assertIs<SyncState.Success>(f.service.syncState.value)
        f.service.shutdown()
    }

    @Test
    fun sync_should_HoldNothing_When_TrackedFileIsEmpty() = runBlocking {
        val f = fixture(modified = listOf(photo), files = mapOf(photo to ByteArray(0)))

        f.service.sync(graphId)

        assertEquals(listOf(emptySet<String>()), f.repo.stageCalls)
        f.service.shutdown()
    }

    @Test
    fun sync_should_SkipTheCommit_When_EverythingStagedWasHeld() = runBlocking {
        val f = fixture(modified = listOf(photo), files = mapOf(photo to png), configure = { it.anythingStaged = false })

        val result = f.service.sync(graphId)

        assertTrue(result.isRight())
        assertTrue(f.repo.commits.isEmpty(), "no empty commit while only held files changed")
        assertEquals(1, f.repo.pushes[0], "the sync still pushes")
        assertIs<SyncState.LfsActionRequired>(f.service.syncState.value)
        f.service.shutdown()
    }

    @Test
    fun sync_should_NotConsultTheIndexForCommitNeed_When_NothingWasHeld() = runBlocking {
        val f = fixture(modified = listOf("pages/a.md"), files = mapOf("pages/a.md" to "x".encodeToByteArray()), configure = { it.anythingStaged = false })

        f.service.sync(graphId)

        assertEquals(1, f.repo.commits.size, "hasStagedChanges is only consulted after a hold-back")
        f.service.shutdown()
    }

    @Test
    fun sync_should_HonourNestedGitattributes_When_RuleLivesInASubdirectory() = runBlocking {
        val f = fixture(
            modified = listOf("pages/img/x.png", "pages/y.png"),
            files = mapOf(
                "pages/img/x.png" to png,
                "pages/y.png" to png,
                "pages/img/.gitattributes" to "*.png filter=lfs\n".encodeToByteArray(),
            ),
            gitattributes = null,
        )

        f.service.sync(graphId)

        assertEquals(listOf(setOf("pages/img/x.png")), f.repo.stageCalls)
        f.service.shutdown()
    }

    @Test
    fun sync_should_SeeAnUncommittedGitattributesEdit_When_TheWorkingTreeFileChanged() = runBlocking {
        val f = fixture(modified = listOf(".gitattributes", "x.png"), files = mapOf("x.png" to png), gitattributes = "*.png filter=lfs\n")

        f.service.sync(graphId)

        assertEquals(listOf(setOf("x.png")), f.repo.stageCalls)
        f.service.shutdown()
    }

    @Test
    fun sync_should_RefuseTheWholeCommit_When_HoldBackIsFlippedOff() = runBlocking {
        val coordinator = LfsGuardCoordinator(MemoryFileSystem().also { fs ->
            fs.files["/repo/.gitattributes"] = "assets/** filter=lfs\n".encodeToByteArray()
            fs.files["/repo/$photo"] = png
        }, holdBack = false)
        val f = fixture(modified = listOf("pages/a.md", photo), coordinator = coordinator)

        val result = f.service.sync(graphId)

        val error = assertIs<Either.Left<DomainError.GitError>>(result).value
        assertEquals(DomainError.GitError.LfsRawBinaryRefused(listOf(photo)), error)
        assertTrue(f.repo.stageCalls.isEmpty(), "nothing is staged when the whole commit is refused")
        assertTrue(f.repo.commits.isEmpty())
        assertIs<SyncState.Error>(f.service.syncState.value)
        f.service.shutdown()
    }

    @Test
    fun sync_should_JudgeNothing_When_ThePlatformGuardsInItsOwnWritePath() = runBlocking {
        val coordinator = LfsGuardCoordinator(MemoryFileSystem().also { it.files["/repo/.gitattributes"] = "* filter=lfs\n".encodeToByteArray() }, delegated = true)
        val f = fixture(modified = listOf(photo), coordinator = coordinator, files = mapOf(photo to png))

        f.service.sync(graphId)

        assertEquals(listOf(emptySet<String>()), f.repo.stageCalls)
        f.service.shutdown()
    }

    @Test
    fun sync_should_NotJudgeTheWorkingFile_When_AutoModeAndACleanFilterIsActive() = runBlocking {
        val f = fixture(
            modified = listOf("assets/b.bin"),
            files = mapOf("assets/b.bin" to ByteArray(3_000)),
            configure = { it.cleanFilterActive = true },
            capabilities = LfsPlatformCapabilities(transferSupported = true, androidLfsEnabled = false),
        )
        LfsModeSource(f.settings).set(graphId, LfsMode.AUTO)

        f.service.sync(graphId)

        assertEquals(listOf(emptySet<String>()), f.repo.stageCalls, "the filter converts it at git add; the index check decides")
        assertIs<SyncState.Success>(f.service.syncState.value)
        f.service.shutdown()
    }

    @Test
    fun sync_should_HoldAndUnstage_When_AutoModeFilterLeftRawBytesInTheIndex() = runBlocking {
        val escaped = LfsViolation("assets/b.bin", LfsViolationReason.RawBinary, ".gitattributes:1")
        val f = fixture(
            modified = listOf("assets/b.bin"),
            files = mapOf("assets/b.bin" to ByteArray(3_000)),
            configure = { it.cleanFilterActive = true; it.staged = listOf(escaped) },
            capabilities = LfsPlatformCapabilities(transferSupported = true, androidLfsEnabled = false),
        )
        LfsModeSource(f.settings).set(graphId, LfsMode.AUTO)

        f.service.sync(graphId)

        assertEquals(listOf(escaped), assertIs<SyncState.LfsActionRequired>(f.service.syncState.value).held)
        f.service.shutdown()
    }

    @Test
    fun sync_should_HoldTheWorkingFile_When_ModeIsGuardOnlyEvenIfACleanFilterIsReported() = runBlocking {
        val f = fixture(
            modified = listOf("assets/b.bin"),
            files = mapOf("assets/b.bin" to ByteArray(3_000)),
            configure = { it.cleanFilterActive = true },
            capabilities = LfsPlatformCapabilities(transferSupported = true, androidLfsEnabled = false),
        )
        LfsModeSource(f.settings).set(graphId, LfsMode.GUARD_ONLY)

        f.service.sync(graphId)

        assertEquals(listOf(setOf("assets/b.bin")), f.repo.stageCalls)
        f.service.shutdown()
    }

    @Test
    fun sync_should_FailClosed_When_TheIndexCannotBeVerified() = runBlocking {
        val f = fixture(modified = listOf("pages/a.md"), files = mapOf("pages/a.md" to "x".encodeToByteArray()), configure = { it.stagedThrows = true })

        val result = f.service.sync(graphId)

        assertIs<DomainError.GitError.CommitFailed>(assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertTrue(f.repo.commits.isEmpty())
        f.service.shutdown()
    }

    @Test
    fun commitLocalChanges_should_HoldRawBinaryAndStillCommit_When_AssetsAreLfsTracked() = runBlocking {
        val f = fixture(modified = listOf("pages/a.md", photo), files = mapOf(photo to png))

        val result = f.service.commitLocalChanges(graphId)

        assertEquals("abc123", assertIs<Either.Right<String?>>(result).value)
        assertEquals(listOf(setOf(photo)), f.repo.stageCalls)
        assertIs<SyncState.LfsActionRequired>(f.service.syncState.value)
        f.service.shutdown()
    }

    @Test
    fun commitLocalChanges_should_ReturnNullWithoutCommitting_When_OnlyHeldFilesChanged() = runBlocking {
        val f = fixture(modified = listOf(photo), files = mapOf(photo to png), configure = { it.anythingStaged = false })

        val result = f.service.commitLocalChanges(graphId)

        assertEquals(null, assertIs<Either.Right<String?>>(result).value)
        assertTrue(f.repo.commits.isEmpty())
        f.service.shutdown()
    }

    @Test
    fun resolveConflicts_should_RefuseBeforeMarkResolved_When_SideResolutionLeavesRawBinary() = runBlocking {
        val f = fixture(files = mapOf(photo to png))

        val result = f.service.resolveConflicts(
            graphId,
            listOf(ConflictFile("/repo/$photo", "/repo/$photo", emptyList())),
            sideResolutions = mapOf("/repo/$photo" to MergeSide.REMOTE),
        )

        assertEquals(DomainError.GitError.LfsRawBinaryRefused(listOf(photo)), assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertTrue(f.repo.resolved.isEmpty(), "markResolved must never stage the raw file")
        assertTrue(f.repo.commits.isEmpty())
        f.service.shutdown()
    }

    @Test
    fun resolveConflicts_should_RefuseBeforeMarkResolved_When_HunkResolutionWritesRawText() = runBlocking {
        val f = fixture(files = mapOf(photo to png))

        val result = f.service.resolveConflicts(
            graphId,
            listOf(ConflictFile("/repo/$photo", "/repo/$photo", emptyList(), rawContent = "plain text")),
            hunkResolutions = mapOf("/repo/$photo" to emptyList()),
        )

        assertIs<DomainError.GitError.LfsRawBinaryRefused>(assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertTrue(f.repo.resolved.isEmpty())
        f.service.shutdown()
    }

    @Test
    fun resolveConflicts_should_StageAValidPointer_When_ResolvedSideIsAPointer() = runBlocking {
        val f = fixture(files = mapOf(photo to pointer))

        val result = f.service.resolveConflicts(
            graphId,
            listOf(ConflictFile("/repo/$photo", "/repo/$photo", emptyList())),
            sideResolutions = mapOf("/repo/$photo" to MergeSide.LOCAL),
        )

        assertTrue(result.isRight(), "$result")
        assertEquals(listOf("/repo/$photo"), f.repo.resolved)
        f.service.shutdown()
    }

    @Test
    fun applyJournalMerge_should_RefuseBeforeMarkResolved_When_MergedContentLandsAtATrackedPath() = runBlocking {
        val f = fixture(gitattributes = "journals/** filter=lfs\n")

        val result = f.service.applyJournalMerge(graphId, "/repo/journals/2026_01_01.md", "- merged\n")

        assertIs<DomainError.GitError.LfsRawBinaryRefused>(assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertTrue(f.repo.resolved.isEmpty())
        assertFalse(f.repo.commits.isNotEmpty())
        f.service.shutdown()
    }

    @Test
    fun syncState_should_ReturnToSuccess_When_TheHeldFileIsFixedBeforeTheNextSync() = runBlocking {
        val f = fixture(modified = listOf(photo), files = mapOf(photo to png))
        f.service.sync(graphId)
        assertIs<SyncState.LfsActionRequired>(f.service.syncState.value)

        f.fs.files["/repo/$photo"] = pointer
        f.service.sync(graphId)

        assertIs<SyncState.Success>(f.service.syncState.value)
        f.service.shutdown()
    }
}
