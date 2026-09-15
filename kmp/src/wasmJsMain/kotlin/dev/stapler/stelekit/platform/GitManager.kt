package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.WasmGitWriteService
import dev.stapler.stelekit.git.buildCommitMessage
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.git.model.PendingCommit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual object GitManagerFactory {
    /**
     * Epic 4.2 (Task 4.2.1a): [GitManagerFactory.create] is an `expect`/`actual` zero-arg factory
     * shared with `JvmGitManager`/`AndroidGitManager` — its signature cannot change here without
     * touching those actuals, which is out of scope for this epic. `GitManagerFactory.create()`
     * itself has zero real call sites in the UI today (verified), so this builds a [JsGitManager]
     * whose `graphId` resolver always returns `null` — i.e. it is permanently in the "no GitConfig"
     * state and behaves exactly like the pre-Epic-4.2 stub. The real, wired-up construction (a
     * `graphId`/[GitConfigRepository] tied to the active graph, sharing the same
     * [WasmGitWriteService] instance [dev.stapler.stelekit.git.WasmGitRepository] uses) is Epic
     * 4.3's `Main.kt` wiring concern — that code is expected to construct [JsGitManager] directly
     * via its primary constructor rather than through this factory.
     */
    actual fun create(): GitManager {
        val fileSystem = PlatformFileSystem()
        return JsGitManager(
            graphId = { null },
            configRepository = NoopGitConfigRepository,
            fileSystem = fileSystem,
            gitWriteService = WasmGitWriteService.withDefaultClient(fileSystem),
            configResolver = { null },
        )
    }
}

// ponytail: no GitConfig saved for the active graph is still a fully supported, deliberate state
// (git sync is opt-in) — this is the literal stub error surfaced whenever that's true.
private const val NOT_SUPPORTED = "Git sync is not available on the web platform"

/**
 * Epic 4.2 (Story 4.2.1): resolvable-host-configuration message shared by every method below that
 * needs a [GitHostConfig] (commit/push/pull) — mirrors
 * [dev.stapler.stelekit.git.WasmGitRepository.resolveHostConfig]'s identical literal so both
 * classes surface the same diagnostic for the same failure mode.
 */
private const val HOST_UNRESOLVED_MESSAGE = "Unable to resolve git host configuration for this graph"

/**
 * BUG-005 Phase 2 close-out (Epic 4.2): a thin [GitManager] delegate over the same
 * [WasmGitWriteService] write engine [dev.stapler.stelekit.git.WasmGitRepository] wraps (Epic
 * 4.1). No UI call site constructs this today (`GitManagerFactory.create()` is unused), but the
 * `GitManager` interface is public and BUG-005's text names it explicitly — this closes that gap
 * genuinely rather than leaving a permanent stub reachable via a different path.
 *
 * Every operation resolves the active graph's [GitConfig] (via [graphId] + [configRepository])
 * first; if none is saved, every method returns the exact pre-Epic-4.2 [NOT_SUPPORTED] stub error
 * unchanged (a deliberate zero-regression guarantee, see `IT-4.2.1-A`). Once a [GitConfig] exists,
 * `commit`/`push`/`pull` additionally resolve a [GitHostConfig] via [configResolver] — the same
 * resolver shape [dev.stapler.stelekit.git.WasmGitRepository] takes, so Epic 4.3's `Main.kt` can
 * construct both classes from one shared lookup — and delegate to [gitWriteService], the SAME
 * instance/logic [dev.stapler.stelekit.git.WasmGitRepository] calls (see `IT-4.2.1-B`).
 *
 * `status`/`isDirty` only need a saved [GitConfig] to pass the gate (they read purely local
 * [PlatformFileSystem] state, exactly like
 * [dev.stapler.stelekit.git.WasmGitRepository.status]) — they never need [configResolver].
 */
class JsGitManager(
    private val graphId: () -> String?,
    private val configRepository: GitConfigRepository,
    private val fileSystem: PlatformFileSystem,
    private val gitWriteService: WasmGitWriteService,
    private val configResolver: suspend (GitConfig) -> GitHostConfig?,
) : GitManager {

    /** `null` when no [GitConfig] is saved for the active graph — the universal stub gate. */
    private suspend fun resolveConfig(): GitConfig? {
        val id = graphId() ?: return null
        return configRepository.getConfig(id).getOrElse { null }
    }

    override suspend fun commit(message: String): GitResult<String> {
        val config = resolveConfig() ?: return GitResult.Error(NOT_SUPPORTED)
        val hostConfig = configResolver(config) ?: return GitResult.Error(HOST_UNRESOLVED_MESSAGE)
        val baseSha = fileSystem.getBaseSha()
        return gitWriteService.commit(config, hostConfig, baseSha, message).fold(
            { err -> GitResult.Error(err.message) },
            {
                val sha = (fileSystem.getPendingCommit() as? PendingCommit.Staged)?.commitSha ?: baseSha
                GitResult.Success(sha)
            },
        )
    }

    override suspend fun push(): Either<DomainError, Unit> {
        val config = resolveConfig()
            ?: return DomainError.NetworkError.HttpError(501, NOT_SUPPORTED).left()
        val hostConfig = configResolver(config)
            ?: return DomainError.GitError.AuthFailed(HOST_UNRESOLVED_MESSAGE).left()
        val gitLabContext = if (hostConfig.type == GitHostType.GITLAB) {
            WasmGitWriteService.GitLabPushContext(
                config = config,
                baseSha = fileSystem.getBaseSha(),
                message = buildCommitMessage(config),
            )
        } else {
            null
        }
        // Same WasmGitWriteService.push() call WasmGitRepository.push() makes — not a second,
        // independently re-implemented path (Epic 4.2 acceptance criterion, IT-4.2.1-B).
        return gitWriteService.push(hostConfig, fileSystem.getPendingCommit(), gitLabContext)
    }

    override suspend fun pull(): Either<DomainError, Unit> {
        val config = resolveConfig()
            ?: return DomainError.NetworkError.HttpError(501, NOT_SUPPORTED).left()
        val hostConfig = configResolver(config)
            ?: return DomainError.GitError.AuthFailed(HOST_UNRESOLVED_MESSAGE).left()
        val baseSha = fileSystem.getBaseSha()
        return gitWriteService.fetch(hostConfig, baseSha).flatMap { fetchResult ->
            if (!fetchResult.hasRemoteChanges) {
                Unit.right()
            } else {
                gitWriteService.merge(config, hostConfig, baseSha).map { }
            }
        }
    }

    override suspend fun status(): GitResult<String> {
        resolveConfig() ?: return GitResult.Error(NOT_SUPPORTED)
        val snapshot = fileSystem.getDirtySnapshot()
        return if (snapshot.isEmpty()) {
            GitResult.Success("Working tree clean")
        } else {
            GitResult.Success("Modified: ${snapshot.keys.sorted().joinToString(", ")}")
        }
    }

    override suspend fun isDirty(): GitResult<Boolean> {
        resolveConfig() ?: return GitResult.Error(NOT_SUPPORTED)
        return GitResult.Success(fileSystem.dirtyFileCountFlow.value > 0)
    }
}

/**
 * Harmless always-empty [GitConfigRepository] used only by [GitManagerFactory.create]'s
 * permanently-unconfigured default instance above — its `graphId` resolver always returns `null`,
 * so this is never actually queried (see [JsGitManager.resolveConfig]'s short-circuit).
 */
private object NoopGitConfigRepository : GitConfigRepository {
    override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = null.right()
    override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
    override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
    override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> = flowOf(null.right())
}
