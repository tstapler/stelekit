// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.git.merge.JournalMergeService
import dev.stapler.stelekit.git.lfs.LfsGuardCoordinator
import dev.stapler.stelekit.git.lfs.LfsGuardedStaging
import dev.stapler.stelekit.git.lfs.LfsTransferPolicySource
import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.jobs.NoJobTracker
import dev.stapler.stelekit.git.lfs.LfsPlatformCapabilities
import dev.stapler.stelekit.git.lfs.LfsViolation
import dev.stapler.stelekit.git.model.CloneDepthState
import dev.stapler.stelekit.git.merge.SidecarConflictResolver
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.model.logSummary
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import kotlin.time.Clock

/**
 * Orchestrates the full git sync cycle: commit local changes, fetch from remote,
 * merge, push, and reload changed files into the database.
 *
 * Owns its own [CoroutineScope] — never accept rememberCoroutineScope().
 *
 * Created and owned by [GraphManager]; one instance per active graph.
 */
@Suppress("TooManyFunctions", "LargeClass") // one entry point per sync operation plus the extracted merge-unit steps
class GitSyncService(
    private val gitRepository: GitRepository,
    private val graphLoader: GraphLoader,
    private val graphWriter: GraphWriter,
    private val editLock: EditLock,
    private val configRepository: GitConfigRepository,
    private val networkMonitor: NetworkMonitor,
    private val fileSystem: FileSystem,
    /** Process-wide per-repo gate; required, never defaulted (a private one would not exclude other entry points). */
    internal val gate: RepoOpGate,
    /** Returns the active [CredentialAccess] for checking vault availability before sync. Null means always available. */
    private val credentialAccessProvider: (() -> CredentialAccess)? = null,
    private val journalMergeService: JournalMergeService? = null,
    /** Graph this instance serves — one instance per active graph (see class doc). Used only to
     * key [lastSyncAt]'s persisted value in [settings]; every method still takes its own
     * `graphId` parameter for the actual git operation. */
    private val graphId: String = "",
    /** Backs [lastSyncAt] across app restarts. Null (tests, and any caller that doesn't need
     * persistence) makes [lastSyncAt] start at null and never persist. */
    private val settings: Settings? = null,
    /** Tracks whether [sync] is currently in flight, so the relocate quiesce sequence (Phase 3)
     * can await idle before moving a graph's `.git` directory. Defaults to a private instance;
     * callers that need to observe busy-ness externally (e.g. a quiesce strategy) should inject
     * a shared instance instead. */
    private val gitSyncBusyCounter: GitSyncBusyCounter = GitSyncBusyCounter(),
    /** Platform capability for Git LFS; null derives it from [gitRepository] (transfer where the repository supports it). */
    lfsCapabilities: LfsPlatformCapabilities? = null,
    /** Seam for the LFS safety floor. Tests inject one; production derives it from [settings] and [gitRepository]. */
    lfsGuardCoordinator: LfsGuardCoordinator? = null,
    /** Connectivity probe; defaults to [networkMonitor]. Tests inject one because the JVM monitor pings 8.8.8.8. */
    private val isOnline: () -> Boolean = { networkMonitor.isOnline },
    /** Extra context for the periodic-sync job only; tests inject a virtual-time dispatcher here. */
    private val periodicSyncContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    /** Wall-clock epoch millis for scheduled-sync bookkeeping; tests inject a fake. */
    private val wallClockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Shows LFS image downloads (including background catch-up) in the jobs list. */
    jobTracker: JobTracker = NoJobTracker,
    /** Whether the graph sits in a directory this process writes directly; SAF graphs are fetch-only for scheduled work. Throwing counts as false. */
    isAppOwnedPath: (RepoRoot) -> Boolean = { !it.value.startsWith("saf://") && !it.value.startsWith("content://") },
    /** Write-only record of each in-app tick for the Logs screen; the default records nothing. */
    tickRunRecorder: TickRunRecorder = TickRunRecorder.None,
    /** Jitter source for the periodic loop; tests inject a seeded one. */
    periodicRandom: kotlin.random.Random = kotlin.random.Random.Default,
    /** Waits between the automatic retries after an unknown lock owner; tests inject short ones. */
    pausedRetryPolicy: PausedRetryPolicy = PausedRetryPolicy(),
) {
    private val logger = Logger("GitSyncService")
    private val markerGuard = ConflictMarkerGuard(fileSystem)
    private val stalenessTracker = StalenessTracker.create(settings, graphId, wallClockMs)

    /** Persisted last-merged / behind-count facts behind the sync chip, the banner and the notification. */
    val staleness: StateFlow<SyncStaleness> = stalenessTracker.state

    private val guardedAbort = GuardedMergeAbort.create(gitRepository, fileSystem, wallClockMs)
    private val guards = GitChangeGuards(gitRepository, settings)
    private val lfsStaging = LfsGuardedStaging.create(gitRepository, fileSystem, settings, graphId, lfsCapabilities, lfsGuardCoordinator)
        .also { gitRepository.setLfsTransferContext(jobTracker) { LfsTransferPolicySource(settings).current() } }
    private val firstSyncConfirmation: FirstSyncConfirmation? = settings?.let { FirstSyncConfirmation(it) }

    /** Applies user-confirmed branch repairs; see [repairBranch]. */
    val branchRepair: BranchRepairService = BranchRepairService(configRepository, firstSyncConfirmation)

    private val reviewState = FirstSyncReviewState(firstSyncConfirmation, graphId)

    /** True while a review is owed (see [FirstSyncReviewState]); drives the "Review first sync" badge. */
    val firstSyncReviewPending: StateFlow<Boolean> = reviewState.pending

    /** Applies [proposal] (no sync is run) and refreshes [firstSyncReviewPending]. */
    suspend fun repairBranch(proposal: BranchRepairProposal): BranchRepairResult =
        singleFlight.run(
            RunSpec(proposal.graphId, SyncOrigin.Repair, "repair-branch"),
            fail = { BranchRepairResult.Busy },
            unreadable = { reason -> BranchRepairResult.SaveFailed(reason) },
        ) { repairBranchLocked(proposal) }

    private suspend fun repairBranchLocked(proposal: BranchRepairProposal): BranchRepairResult =
        branchRepair.apply(proposal).also {
            reviewState.onRepaired()
            // The missing-branch error described the old setting; a stale red badge would reopen a dead repair.
            val current = _syncState.value
            if (it is BranchRepairResult.Repaired && current is SyncState.Error &&
                current.error is DomainError.GitError.RemoteBranchNotFound
            ) {
                _syncState.value = SyncState.Idle
            }
        }

    /**
     * Undoes the last repair, but only if the previous branch exists on the remote right now: it was
     * missing when the repair happened, so it is probed (a fetch of that branch) instead of trusted.
     * Returns null when no repair is recorded.
     */
    suspend fun changeBackBranch(): BranchRepairResult? =
        singleFlight.run(
            RunSpec(graphId, SyncOrigin.Repair, "change-back-branch"),
            fail = { BranchRepairResult.Busy },
            unreadable = { reason -> BranchRepairResult.SaveFailed(reason) },
        ) {
            when (val change = branchRepair.changeBackProposal(gitRepository, graphId)) {
                null -> null
                is Either.Left -> change.value
                is Either.Right -> repairBranchLocked(change.value)
            }
        }


    /** Read-only facts for the first-sync review; never changes the repository. */
    val firstSyncPreviews = FirstSyncPreviewSource(gitRepository, configRepository)

    /** Branch the last repair replaced, for "Change back"; null when none is recorded. */
    fun previousBranchBeforeRepair(): String? = firstSyncConfirmation?.previousBranch(graphId)

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    private val runGuard = SyncRunGuard(_syncState, logger)

    private val _inFlight = MutableStateFlow<SyncRunInfo?>(null)

    /** The run holding this graph's repository gate, or null; written only by that holder. */
    val inFlight: StateFlow<SyncRunInfo?> = _inFlight.asStateFlow()

    /**
     * Working-tree status (dirty file count), refreshed by [refreshLocalStatus] independently of
     * the full [sync] pipeline — never mutated by [_syncState] transitions. Null until the first
     * refresh completes (graph open, each periodic poll tick, or after [sync]/[commitLocalChanges]).
     */
    private val _localStatus = MutableStateFlow<GitStatus?>(null)
    val localStatus: StateFlow<GitStatus?> = _localStatus.asStateFlow()

    /** Epoch-millis timestamp of the last successful [sync]/[applyJournalMerge], persisted in
     * [settings] so it survives app restarts and graph switches — unlike [SyncState.Success],
     * which only lives for the 3-second badge fade. */
    private val lastSync = PersistedLastSync(settings, graphId)
    val lastSyncAt: StateFlow<Long?> = lastSync.at

    /**
     * Lightweight working-tree status check — reads git status without running the full
     * commit/fetch/merge/push [sync] pipeline. Never touches the network and never mutates
     * [_syncState], so it's safe to call frequently (graph open, each periodic poll tick, after
     * [sync]/[commitLocalChanges] completes) without disturbing an in-progress sync's state.
     */
    suspend fun refreshLocalStatus(graphId: String) {
        val config = configRepository.getConfig(graphId).getOrNull() ?: return
        _localStatus.value = gitRepository.status(config).getOrNull()
        reviewState.refresh(config)
    }

    /**
     * Widens [graphId]'s shallow clone to full history (git-sync-resilience Story 2.1.4) and, on
     * success, persists the resulting [CloneDepthState.FullHistory] checkpoint. [GitRepository]
     * implementations don't hold a [GitConfigRepository] reference themselves (see
     * [GitRepository.unshallow]'s doc), so this is the call site responsible for that — this class
     * is the one collaborator that already holds both. No settings-screen UI calls this yet
     * (backend capability only, per plan.md).
     */
    suspend fun deepen(graphId: String): Either<DomainError, Unit> =
        singleFlight.run(
            RunSpec(graphId, SyncOrigin.Deepen, "deepen"),
            fail = { it.left() },
            unreadable = { reason -> DomainError.GitError.FetchFailed(reason).left() },
        ) {
            either {
            val config = configRepository.getConfig(graphId).bind() ?: raise(DomainError.DatabaseError.NotFound("GitConfig", graphId))
            gitRepository.unshallow(config).bind()
            configRepository.saveConfig(config.copy(cloneDepthState = CloneDepthState.FullHistory)).bind()
        }
        }


    // Owns its own scope — never accept rememberCoroutineScope()
    // CoroutineExceptionHandler guards against uncaught Throwable crashing the Android process.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.error("GitSyncService uncaught error", throwable)
        _syncState.value = SyncState.Error(
            DomainError.GitError.FetchFailed("Unexpected error: ${throwable.message}")
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + PlatformDispatcher.IO + exceptionHandler)

    private val singleFlight: SyncSingleFlight = SyncSingleFlight(
        gate, gitRepository, configRepository, gitSyncBusyCounter, _inFlight, wallClockMs,
        publishError = { _syncState.value = SyncState.Error(it) },
        launchFollowUp = { graphId, followUp ->
            // The one rerun a coalesced press queued: a plain manual sync that never carries the mass-change lift.
            if (!scope.isActive) {
                var next: FollowUp? = followUp
                while (next != null) next = gate.releaseOrHandOff(next.lease)
            } else {
                // ATOMIC: a scope cancelled after the check above must still start the coroutine so it releases the lease.
                scope.launch(start = CoroutineStart.ATOMIC) {
                    val result = singleFlight.runFollowUp(
                        followUp,
                        RunSpec(graphId, SyncOrigin.FollowUp, "sync"),
                        fail = { it.left() },
                        unreadable = { reason -> refuse(DomainError.GitError.FetchFailed(reason)) },
                    ) { syncLocked(graphId, SyncTrigger.Manual, liftMassChangeGuard = false) }
                    trackPausedRetry(graphId, SyncOrigin.FollowUp, result)
                }
            }
        },
        scope = scope,
    )

    init {
        // Log every state transition so sync progress/failures are visible in the global log.
        scope.launch { _syncState.collect { logger.info("syncState[$graphId] -> ${it.logSummary()}") } }
        // Populate localStatus immediately on construction (graph open) rather than waiting for
        // the first periodic poll tick or manual sync — see refreshLocalStatus's doc.
        if (graphId.isNotEmpty()) {
            // The app is open now, so a leftover "app closed" fetch-only marker from the worker no longer applies.
            stalenessTracker.onServiceStarted()
            scope.launch { refreshLocalStatus(graphId) }
        }
    }

    /** Policy-gated scheduled tick (full sync, fetch-only or skip); see [ScheduledSyncRunner]. */
    val scheduled = ScheduledSyncRunner(
        gitRepository, configRepository, editLock, gitSyncBusyCounter, firstSyncConfirmation, credentialAccessProvider,
        isOnline, wallClockMs, isAppOwnedPath, _syncState,
        fullSync = { g -> sync(g, SyncTrigger.Automatic) },
        fetch = { g -> fetchOnly(g) },
        staleness = stalenessTracker.store,
    )

    @kotlin.concurrent.Volatile private var periodicGraphId: String = graphId
    private val periodicDriver = PeriodicSyncDriver(
        tick = { refreshLocalStatus(periodicGraphId); scheduled.run(periodicGraphId) },
        scope = scope, clock = wallClockMs, isBusy = { singleFlight.isGateHeld() || gitSyncBusyCounter.isBusy.value },
        label = graphId, decision = "scheduled", launchContext = periodicSyncContext,
        random = periodicRandom, recorder = tickRunRecorder,
        constraintState = { dev.stapler.stelekit.jobs.schedule.ConstraintState(online = isOnline(), isMetered = null, charging = null) },
    )

    /** Observable state of the scheduled-sync loop (last check, next check, pause); see [PeriodicSyncDriver]. */
    val scheduledSyncStatus: StateFlow<ScheduledSyncStatus> get() = periodicDriver.status

    /** Runs one scheduled-sync tick now, even while paused; success clears the pause. */
    suspend fun resumeScheduledSync() = periodicDriver.resume()

    private val rateLimitRetry = RateLimitRetry(scope, awaitFree = { g ->
        (singleFlight.resolveGateKey(g) as? GateKeyResolution.Resolved)?.let { gate.awaitFree(it.key) }
    })

    private val pausedRetries = PausedRetryController(
        scope, wallClockMs, pausedRetryPolicy,
        awaitFree = { g -> (singleFlight.resolveGateKey(g) as? GateKeyResolution.Resolved)?.let { gate.awaitFree(it.key) } },
        attempt = { g -> sync(g, SyncTrigger.Automatic, origin = SyncOrigin.RateLimitRetry).toPausedAttemptResult() },
    )

    /** The automatic retry waiting after an unknown lock owner; null when none is, and Failed once both tries are spent. */
    val pausedRetry: StateFlow<PausedRetryInfo?> = pausedRetries.observed

    /**
     * "Retry now": one extra attempt through the gate that leaves the try number and budget alone. A press while
     * a run holds the gate coalesces and calls [onCoalesced].
     */
    fun retryNow(onCoalesced: () -> Unit = {}) = pausedRetries.retryNow(onCoalesced)

    private suspend fun trackPausedRetry(graphId: String, origin: SyncOrigin, result: Either<DomainError.GitError, SyncState.Success>) {
        if (origin == SyncOrigin.RateLimitRetry) return
        when (result.toPausedAttemptResult()) {
            PausedAttemptResult.StillUnsure -> pausedRetries.onUnsure(
                graphId,
                if (origin == SyncOrigin.UiPress || origin == SyncOrigin.FollowUp) PausedSource.User else PausedSource.Background,
            )
            PausedAttemptResult.Settled -> if (result.leftOrNull() !is DomainError.GitError.FirstSyncReviewPending) pausedRetries.onSettled()
            PausedAttemptResult.Coalesced -> Unit
        }
    }

    private fun Either<DomainError.GitError, SyncState.Success>.toPausedAttemptResult(): PausedAttemptResult {
        val error = leftOrNull() ?: return PausedAttemptResult.Settled
        return when {
            error is DomainError.GitError.SyncInProgress -> PausedAttemptResult.Coalesced
            PausedRetryPolicy.isUnsure(error) -> PausedAttemptResult.StillUnsure
            else -> PausedAttemptResult.Settled
        }
    }

    /** Publishes the state for a failed git step: rate limit (with auto-retry), expired credentials, or the plain error. */
    private fun publishFailure(
        graphId: String,
        err: DomainError.GitError,
        authFailureMeansExpired: Boolean = false,
        retry: suspend (String) -> Unit,
    ) = runGuard.publishFailure(rateLimitRetry, graphId, err, authFailureMeansExpired, retry)

    private val _reloadIncomplete = MutableStateFlow(false)
    private val incompleteReloads = MutableStateFlow<Set<String>>(emptySet())

    /** True while some merged-file reload stopped early and has not since completed; the next graph open reconciles it. */
    val reloadIncomplete: StateFlow<Boolean> = _reloadIncomplete.asStateFlow()

    private fun markReload(paths: Collection<String>, complete: Boolean) {
        val next = incompleteReloads.updateAndGet { if (complete) it - paths.toSet() else it + paths }
        _reloadIncomplete.value = next.isNotEmpty()
    }

    /**
     * Reloads [paths] into the DB under watcher suppression, then always lifts the suppression.
     * Every working-tree change a sync makes funnels through here so no exit path can skip it.
     */
    private suspend fun reloadMergedFiles(paths: List<String>) {
        val started = TimeSource.Monotonic.markNow()
        try {
            if (paths.isNotEmpty()) {
                graphLoader.beginGitMerge(paths)
                var done = 0
                for (chunk in paths.chunked(RELOAD_CHUNK_SIZE)) {
                    graphLoader.reloadFiles(chunk.map { FilePath(it) })
                    done += chunk.size
                    logger.info("reload progress done=$done/${paths.size}")
                    warnIfHeldTooLong(started.elapsedNow().inWholeMilliseconds)
                }
            }
            markReload(paths, complete = true)
        } catch (e: CancellationException) {
            markReload(paths, complete = false)
            throw e
        } catch (e: Throwable) {
            // The merged files are already on disk, so the next graph open reconciles them; nothing may reach the platform handler.
            logger.error("reload aborted reason=${e::class.simpleName}: ${e.message}")
            markReload(paths, complete = false)
        } finally {
            withContext(NonCancellable) { graphLoader.endGitMerge() }
        }
    }

    /** Test seam: how long a commit (+ reload) may hold the gate once it can no longer be cancelled. */
    internal var commitUnitTimeoutMs: Long = COMMIT_UNIT_TIMEOUT_MS

    /**
     * Runs [work] uncancellable, as #676 requires, but only the caller's wait is bounded: after the timeout the user
     * is told the unit is still running ([timedOut]), while it keeps the gate lease and owner record until it really
     * ends, so no second commit can start under it and its lock is never treated as removable.
     */
    private suspend fun <T> boundedUnit(reloads: Boolean, timedOut: (DomainError.GitError) -> T, work: suspend () -> T): T =
        withContext(NonCancellable) {
            val running = scope.async { work() }
            try {
                withTimeout(commitUnitTimeoutMs) { running.await() }
            } catch (_: TimeoutCancellationException) {
                logger.error("commit unit still running after ${commitUnitTimeoutMs}ms; keeping the lease")
                kotlin.coroutines.coroutineContext[LeaseHold]?.unit = running
                if (reloads) {
                    markReload(listOf(ABANDONED_UNIT), complete = false)
                    running.invokeOnCompletion { markReload(listOf(ABANDONED_UNIT), complete = true) }
                }
                timedOut(DomainError.GitError.CommitFailed("The commit is still running (a hook or signing prompt may be waiting). Sync stays paused until it finishes. If it never does, close and reopen SteleKit to recover."))
            }
        }

    private fun warnIfHeldTooLong(heldMs: Long) {
        if (heldMs > GATE_HELD_WARN_MS) logger.warn("gate heldMs=$heldMs exceeds ${GATE_HELD_WARN_MS}ms while reloading merged files")
    }

    /**
     * Full sync sequence ([SyncTrigger.Automatic] callers wait while a first-sync review is pending):
     * 1. Network check
     * 2. Load config
     * 3. Safety checks (detached HEAD, stale lock)
     * 4. Flush pending writes + await idle editing
     * 5. Commit local changes
     * 6. Fetch
     * 7. Merge (if remote changes available) — with watcher suppression
     * 8. Reload merged files
     * 9. Push
     */
    suspend fun sync(
        graphId: String,
        trigger: SyncTrigger = SyncTrigger.Manual,
        /** One-run override of the mass-change guard from the foreground typed-count confirm; ignored for [SyncTrigger.Automatic]. */
        liftMassChangeGuard: Boolean = false,
        /** Who started this run; logging and single-flight bookkeeping only, never guard semantics. */
        origin: SyncOrigin = if (trigger == SyncTrigger.Manual) SyncOrigin.UiPress else SyncOrigin.Scheduled,
    ): Either<DomainError.GitError, SyncState.Success> {
        // Read the persisted flag, not the cached StateFlow: the review is confirmed in settings.
        if (trigger == SyncTrigger.Automatic && firstSyncConfirmation?.isReviewPending(graphId) == true) {
            logger.info("skipping automatic sync graphId=$graphId: first-sync review is pending")
            return DomainError.GitError.FirstSyncReviewPending.left()
        }
        val result = singleFlight.run(
            RunSpec(graphId, origin, "sync", rerunOnLoss = origin == SyncOrigin.UiPress && !liftMassChangeGuard),
            fail = { it.left() },
            unreadable = { reason -> refuse(DomainError.GitError.FetchFailed(reason)) },
        ) { syncLocked(graphId, trigger, liftMassChangeGuard) }
        trackPausedRetry(graphId, origin, result)
        return result
    }

    private suspend fun syncLocked(
        graphId: String,
        trigger: SyncTrigger,
        liftMassChangeGuard: Boolean,
    ): Either<DomainError.GitError, SyncState.Success> =
        withContext(PlatformDispatcher.IO) {
            try {

            // Task 3.4.2c: a manual sync trigger always supersedes any pending scheduled retry.
            rateLimitRetry.cancel()
            val liftMassChange = liftMassChangeGuard && trigger == SyncTrigger.Manual

            // 1. Network check
            logger.info("sync/fetch start graphId=$graphId")
            if (!isOnline()) {
                logger.warn("aborting: NetworkMonitor reports offline")
                val err = DomainError.GitError.Offline
                _syncState.value = SyncState.Error(err)
                return@withContext err.left()
            }

            // 1.5 Check credential availability (vault may be locked)
            val credAccess = try { credentialAccessProvider?.invoke() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (credAccess != null && !credAccess.isAvailable()) {
                _syncState.value = SyncState.CredentialVaultLocked
                return@withContext DomainError.GitError.AuthFailed("Vault is locked — unlock the vault to resume sync").left()
            }

            // 2. Load config
            val config = when (val result = configRepository.getConfig(graphId)) {
                is Either.Left -> {
                    val err = DomainError.GitError.FetchFailed("Failed to load git config: ${result.value.message}")
                    _syncState.value = SyncState.Error(err)
                    return@withContext err.left()
                }
                is Either.Right -> result.value
            } ?: run {
                // No config — nothing to sync
                return@withContext SyncState.Success(0, 0, Clock.System.now().toEpochMilliseconds()).right()
            }

            // 3a. Detached HEAD check
            if (gitRepository.hasDetachedHead(config)) {
                val err = DomainError.GitError.DetachedHead(config.repoRoot.value)
                _syncState.value = SyncState.Error(err)
                return@withContext err.left()
            }

            // 3a2. Mid-merge / unmerged repository: nothing may be staged, committed or pushed until repaired.
            guards.repositoryState(config)?.let { return@withContext refuse(it) }

            // 3b. The stale-lock decision is the owner-record claim [singleFlight] takes before this body runs.

            // 4. Flush pending writes + await idle editing
            graphWriter.flush()
            editLock.awaitIdle()

            // 5. Commit local changes
            var localCommitsMade = 0
            var lfsHeld: List<LfsViolation> = emptyList()
            _syncState.value = SyncState.Committing
            val statusResult = gitRepository.status(config)
            if (statusResult is Either.Right && statusResult.value.hasLocalChanges) {
                if (!config.autoCommit) {
                    val onFetchFailed = { err: DomainError.GitError -> publishFailure(graphId, err, config.authType == GitAuthType.GITHUB_OAUTH) { g -> sync(g, SyncTrigger.Automatic) } }
                    return@withContext AutoCommitHold.fetchAndReport(gitRepository, config, statusResult.value, { _syncState.value = it }, onFetchFailed).left()
                }
                // A conflicted merge leaves markers in the tree; committing them would push them to the remote.
                markerGuard.findUnresolved(config, statusResult.value)?.let { unresolved ->
                    logger.warn("refusing to commit: ${unresolved.files.size} file(s) still contain conflict markers")
                    _syncState.value = unresolved.state
                    return@withContext unresolved.error.left()
                }
                val staged = lfsStaging.stage(config, statusResult.value).getOrElse { err ->
                    _syncState.value = SyncState.Error(err)
                    return@withContext err.left()
                }
                lfsHeld = staged.held
                if (staged.commitNeeded) {
                    guards.changes(config, GuardScope.Staged, liftMassChange)?.let { return@withContext refuse(it) }
                    ensureActive()
                    // Non-cancellable: once the commit starts it finishes, then the pipeline stops at the next boundary.
                    boundedUnit(false, { it.left() }) { gitRepository.commit(config, buildCommitMessage(config)) }.onLeft { err ->
                        publishFailure(graphId, err) { g -> sync(g, SyncTrigger.Automatic) }
                        return@withContext err.left()
                    }
                    localCommitsMade = 1
                }
            }

            // 6. Fetch
            ensureActive()
            _syncState.value = SyncState.Fetching
            val fetchResult = when (val r = gitRepository.fetch(config)) {
                is Either.Left -> {
                    publishFailure(graphId, r.value, config.authType == GitAuthType.GITHUB_OAUTH) { g -> sync(g, SyncTrigger.Automatic) }
                    return@withContext r.value.left()
                }
                is Either.Right -> r.value
            }

            // 7. Merge (if remote changes available). The check is outside the unit and before merge() runs, so
            // it can only stop a run that has not touched the working tree.
            var remoteCommitsMerged = 0
            var autoMergedPaths: List<String> = emptyList()
            if (fetchResult.hasRemoteChanges) {
                ensureActive()
                when (val outcome = mergeUnit(graphId, config, fetchResult)) {
                    is MergeUnitOutcome.Exit -> return@withContext outcome.result
                    is MergeUnitOutcome.Continue -> {
                        remoteCommitsMerged = outcome.remoteCommitsMerged
                        autoMergedPaths = outcome.autoMergedPaths
                    }
                }
                ensureActive()
            }

            // 9. Push
            guards.changes(config, GuardScope.NetPush, liftMassChange)?.let { return@withContext refuse(it) }
            ensureActive()
            _syncState.value = SyncState.Pushing
            // Cancellable on purpose: push does not touch the working tree, and a hung push must not hold the gate forever.
            gitRepository.push(config).onLeft { err ->
                if (err is DomainError.GitError.MergeConflict) {
                    // Task 3.2.3b: a push-time conflict (GitHub's ref-PATCH 409/422, or GitLab's
                    // commits-POST 400) is a *conflict*, not a generic push failure — route it to
                    // the same ConflictPending path merge()-time conflicts already use, so the
                    // user gets the resolution UI instead of a dead-end error toast. Benefits
                    // GitHub's push-time race identically (it was previously also mis-routed into
                    // the generic Error branch below).
                    _syncState.value = SyncState.ConflictPending(
                        err.conflictPaths.map { ConflictFile(it, it, emptyList()) }
                    )
                } else {
                    publishFailure(graphId, err) { g -> sync(g, SyncTrigger.Automatic) }
                }
                return@withContext err.left()
            }

            refreshLocalStatus(graphId)
            val success = SyncState.Success(
                localCommitsMade = localCommitsMade,
                remoteCommitsMerged = remoteCommitsMerged,
                lastSyncAt = Clock.System.now().toEpochMilliseconds(),
                autoMergedPaths = autoMergedPaths,
            )
            lastSync.record(success.lastSyncAt)
            guardedAbort.clearRecovery(graphId)
            stalenessTracker.merged(success.lastSyncAt)
            firstSyncConfirmation?.confirm(graphId, config.remoteName, config.remoteBranch)
            reviewState.onConfirmed()
            // Non-fatal: the held files need the user's attention, so the badge keeps showing them instead of fading.
            val lfsAttention = lfsHeld + gitRepository.lastPushLfsViolations
            _syncState.value = if (lfsAttention.isEmpty()) success else SyncState.LfsActionRequired(lfsAttention)
            success.right()
            } catch (e: CancellationException) {
                runGuard.cancelled()
                throw e
            } catch (e: Exception) {
                runGuard.crashed("sync", e)
            }
        }

    /**
     * Merge, auto-resolve and the reload-in-`finally` form one non-cancellable unit (#676): no cancellation
     * check separates `merge()` returning from the end of the reload. The body has no `return@withContext`,
     * which inside a nested `withContext(NonCancellable)` would bind to the inner lambda and fall through to push.
     */
    private suspend fun mergeUnit(graphId: String, config: GitConfig, fetchResult: FetchResult): MergeUnitOutcome =
        boundedUnit(
            true,
            { err -> _syncState.value = SyncState.Error(err); MergeUnitOutcome.Exit(Either.Left(err)) },
        ) { runMergeUnit(graphId, config, fetchResult) }

    private suspend fun runMergeUnit(graphId: String, config: GitConfig, fetchResult: FetchResult): MergeUnitOutcome {
        var autoMergedPaths: List<String> = emptyList()
        _syncState.value = SyncState.Merging
        val mergeResult = when (val r = gitRepository.merge(config)) {
            is Either.Left -> {
                publishFailure(graphId, r.value) { g -> sync(g, SyncTrigger.Automatic) }
                return MergeUnitOutcome.Exit(Either.Left(r.value))
            }
            is Either.Right -> r.value
        }

        // Files the block-aware merge (honoring .gitattributes) already merged cleanly need
        // no user decision. Suppress the watcher before writing them so the writes are not
        // read back as external edits; the finally below clears it on every exit path.
        // Sidecars are app-owned derived data: union them here rather than asking the user.
        val conflicts = mergeResult.conflicts.map { SidecarConflictResolver.resolve(it) ?: it }
        val autoResolvable = conflicts.filter { it.autoResolvedBy != null }
        if (autoResolvable.isNotEmpty()) graphLoader.beginGitMerge(autoResolvable.map { it.filePath })
        // The working tree changed under the DB the moment merge() returned, so reloading it is
        // not a success-path step: [reloadMergedFiles] runs from the finally below on EVERY exit
        // (conflict, journal-merge proposal, commit failure), not just the clean one.
        var reloadPaths: List<String> = mergeResult.changedFiles
        try {
            val autoResolved = autoResolveCleanMerges(gitRepository, fileSystem, config, autoResolvable, logger)
            val autoResolvedPaths = autoResolved.mapTo(HashSet()) { it.filePath }
            autoMergedPaths = autoResolved.map { it.wikiRelativePath }
            val unresolvedConflicts = conflicts.filter { it.filePath !in autoResolvedPaths }
            val unresolvedPaths = unresolvedConflicts.mapTo(HashSet()) { it.filePath }
            reloadPaths = (mergeResult.changedFiles + autoResolvedPaths).distinct().filter { it !in unresolvedPaths }

            if (mergeResult.hasConflicts && unresolvedConflicts.isEmpty()) {
                val message = buildCommitMessage(config, isMerge = true) + autoMergeNote(autoResolved)
                gitRepository.commit(config, message).onLeft { err ->
                    // Leave no MERGE_HEAD/staged files behind: the next sync would otherwise start mid-merge.
                    gitRepository.abortMerge(config).onLeft { abortErr ->
                        logger.error("abortMerge after failed auto-merge commit also failed: ${abortErr.message}")
                    }
                    _syncState.value = SyncState.Error(err)
                    return MergeUnitOutcome.Exit(Either.Left(err))
                }
            }

            if (unresolvedConflicts.isNotEmpty()) {
                // Try algorithmic journal merge for journal files
                val journalConflicts = unresolvedConflicts.filter {
                    JournalMergeService.isJournalFile(
                        it.filePath.substringAfterLast('/').substringAfterLast('\\')
                    )
                }
                // Only attempt algorithmic merge for a single journal conflict — multiple
                // conflicts require sequential resolution and must go through the manual screen.
                if (journalConflicts.size == 1 && journalMergeService != null) {
                    val proposal = try {
                        journalMergeService.propose(journalConflicts.first(), config.repoRoot)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (proposal != null) {
                        _syncState.value = SyncState.JournalMergeReady(graphId, proposal)
                        val conflictErr = DomainError.GitError.MergeConflict(
                            conflictCount = unresolvedConflicts.size,
                            conflictPaths = unresolvedConflicts.map { it.filePath },
                        )
                        return MergeUnitOutcome.Exit(Either.Left(conflictErr))
                    }
                }
                val conflictErr = DomainError.GitError.MergeConflict(
                    conflictCount = unresolvedConflicts.size,
                    conflictPaths = unresolvedConflicts.map { it.filePath },
                )
                _syncState.value = SyncState.ConflictPending(unresolvedConflicts)
                return MergeUnitOutcome.Exit(Either.Left(conflictErr))
            }

            // 8. Reload merged files — see the finally below.
        } finally {
            reloadMergedFiles(reloadPaths)
        }
        return MergeUnitOutcome.Continue(mergeResult.mergedCommitCount ?: fetchResult.remoteCommitCount, autoMergedPaths)
    }

    /** How the merge unit hands control back: keep going with the merge facts, or return this failure. */
    private sealed interface MergeUnitOutcome {
        data class Continue(val remoteCommitsMerged: Int, val autoMergedPaths: List<String>) : MergeUnitOutcome
        data class Exit(val result: Either.Left<DomainError.GitError>) : MergeUnitOutcome
    }

    /** Publishes the guard's refusal on [syncState] and returns it as the failure of the calling operation. */
    private val refuse: (DomainError.GitError) -> Either<DomainError.GitError, Nothing> = { err ->
        _syncState.value = SyncState.Error(err)
        logger.warn("refused: ${err.message}")
        err.left()
    }

    /**
     * Fetches from remote only — does not merge or push.
     * Emits [SyncState.MergeAvailable] if remote changes were found.
     * Used by background schedulers and manual "check for updates" actions.
     */
    suspend fun fetchOnly(
        graphId: String,
        origin: SyncOrigin = SyncOrigin.Scheduled,
    ): Either<DomainError.GitError, FetchResult> =
        singleFlight.run(
            RunSpec(graphId, origin, "fetch"),
            fail = { it.left() },
            unreadable = { reason -> DomainError.GitError.FetchFailed(reason).left() },
        ) {
            withContext(PlatformDispatcher.IO) {
                try {
    
                // Task 3.4.2c: a manual fetchOnly trigger always supersedes any pending scheduled retry.
                rateLimitRetry.cancel()
    
                logger.info("sync/fetch start graphId=$graphId")
                if (!isOnline()) {
                    logger.warn("aborting: NetworkMonitor reports offline")
                    val err = DomainError.GitError.Offline
                    _syncState.value = SyncState.Error(err)
                    return@withContext err.left()
                }
    
                val credAccess = try { credentialAccessProvider?.invoke() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (credAccess != null && !credAccess.isAvailable()) {
                    _syncState.value = SyncState.CredentialVaultLocked
                    return@withContext DomainError.GitError.AuthFailed("Vault is locked").left()
                }
    
                val config = when (val result = configRepository.getConfig(graphId)) {
                    is Either.Left -> {
                        return@withContext DomainError.GitError.FetchFailed(
                            "Failed to load git config: ${result.value.message}"
                        ).left()
                    }
                    is Either.Right -> result.value
                } ?: return@withContext FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
    
                _syncState.value = SyncState.Fetching
                when (val result = gitRepository.fetch(config)) {
                    is Either.Left -> {
                        val err = result.value
                        stalenessTracker.failed(err)
                        publishFailure(graphId, err, config.authType == GitAuthType.GITHUB_OAUTH) { g -> fetchOnly(g) }
                        err.left()
                    }
                    is Either.Right -> {
                        val fetchResult = result.value
                        stalenessTracker.fetched(if (fetchResult.hasRemoteChanges) fetchResult.remoteCommitCount else 0)
                        _syncState.value = if (fetchResult.hasRemoteChanges) {
                            SyncState.MergeAvailable(fetchResult.remoteCommitCount)
                        } else {
                            SyncState.Idle
                        }
                        fetchResult.right()
                    }
                }
                } catch (e: CancellationException) {
                    runGuard.cancelled()
                    throw e
                } catch (e: Exception) {
                    runGuard.crashed("fetch", e)
                }
            }
        }


    /**
     * The user-confirmed "Remove lock file": takes the gate (no owner record: the claim would refuse the very lock
     * being removed), then lets the repository delete allow-listed locks only when no live owner holds them.
     */
    suspend fun removeLockFile(graphId: String): Either<DomainError.GitError, Unit> {
        val resolved = singleFlight.resolveGateKey(graphId) as? GateKeyResolution.Resolved
            ?: return DomainError.GitError.FetchFailed("Cannot resolve the repository for $graphId").left()
        val result = gate.withExclusiveLease(
            resolved.key,
            SyncOrigin.UiPress,
            DomainError.GitError.FetchFailed("Cannot resolve the repository for $graphId"),
        ) { withContext(PlatformDispatcher.IO) { gitRepository.forceRemoveLock(resolved.config) } }
        if (result.isRight()) {
            _syncState.value = SyncState.Idle
            pausedRetries.onSettled()
        }
        return result
    }

    /**
     * Stages and commits any outstanding local changes in the wiki subdirectory.
     * Returns the commit SHA on success, or null if there was nothing to commit.
     */
    suspend fun commitLocalChanges(graphId: String): Either<DomainError.GitError, String?> =
        singleFlight.run(
            RunSpec(graphId, SyncOrigin.UiPress, "commit"),
            fail = { it.left() },
            unreadable = { reason -> DomainError.GitError.CommitFailed(reason).left() },
        ) {
            withContext(PlatformDispatcher.IO) {
                val config = when (val result = configRepository.getConfig(graphId)) {
                    is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                        result.value.message
                    ).left()
                    is Either.Right -> result.value
                } ?: return@withContext (null as String?).right()
    
                val status = when (val r = gitRepository.status(config)) {
                    is Either.Left -> return@withContext r.value.left()
                    is Either.Right -> r.value
                }
    
                if (!status.hasLocalChanges) return@withContext (null as String?).right()
    
                guards.repositoryState(config)?.let { return@withContext refuse(it) }
                _syncState.value = SyncState.Committing
                val staged = lfsStaging.stage(config, status).getOrElse { return@withContext it.left() }
                val sha = if (staged.commitNeeded) {
                    guards.changes(config, GuardScope.Staged, liftMassChange = false)?.let { return@withContext refuse(it) }
                    when (val r = boundedUnit(false, { it.left() }) { gitRepository.commit(config, buildCommitMessage(config)) }) {
                        is Either.Left -> return@withContext r.value.left()
                        is Either.Right -> r.value
                    }
                } else {
                    null
                }
                refreshLocalStatus(graphId)
                _syncState.value = if (staged.held.isEmpty()) SyncState.Idle else SyncState.LfsActionRequired(staged.held)
                sha.right()
            }
        }


    /**
     * Resolves a merge's conflicted files — some whole-file (accept local or remote entirely via
     * [sideResolutions]), some at the hunk level ([hunkResolutions], each file's line-by-line
     * choices) — then completes the merge with a single commit covering every resolved file.
     * A file must appear in exactly one of the two maps. Used by the ConflictResolutionScreen
     * "Finish Merge" flow.
     *
     * [conflicts] is the same list the caller received from [SyncState.ConflictPending] — for
     * files in [hunkResolutions], its [ConflictFile.rawContent] is used to reconstruct the
     * resolved file rather than re-reading the working tree, since a hunk-resolution's source
     * content was already captured at merge time (see [ConflictFile.rawContent]'s doc for why a
     * fresh read here would be unreliable in Android's shadow-mirror mode).
     */
    suspend fun resolveConflicts(
        graphId: String,
        conflicts: List<ConflictFile>,
        sideResolutions: Map<String, MergeSide> = emptyMap(),
        hunkResolutions: Map<String, List<dev.stapler.stelekit.git.model.ConflictHunk>> = emptyMap(),
    ): Either<DomainError.GitError, Unit> =
        singleFlight.run(
            RunSpec(graphId, SyncOrigin.Resolve, "resolve"),
            fail = { it.left() },
            unreadable = { reason -> DomainError.GitError.CommitFailed(reason).left() },
        ) {
            withContext(PlatformDispatcher.IO) {
            val config = when (val result = configRepository.getConfig(graphId)) {
                is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                    result.value.message
                ).left()
                is Either.Right -> result.value
            } ?: return@withContext DomainError.GitError.CommitFailed("No git config for $graphId").left()
    
            for ((filePath, side) in sideResolutions) {
                gitRepository.checkoutFile(config, filePath, side).onLeft { return@withContext it.left() }
                lfsStaging.refusalFor(config, filePath)?.let { return@withContext it.left() }
                gitRepository.markResolved(config, filePath).onLeft { return@withContext it.left() }
            }
    
            val resolver = ConflictResolver()
            val conflictsByPath = conflicts.associateBy { it.filePath }
            for ((filePath, hunks) in hunkResolutions) {
                val originalContent = conflictsByPath[filePath]?.rawContent ?: fileSystem.readFile(filePath)
                    ?: return@withContext DomainError.GitError.CommitFailed(
                        "Cannot read conflicted file: $filePath"
                    ).left()
    
                val resolvedContent = when (val r = resolver.applyResolutions(originalContent, hunks)) {
                    is Either.Left -> return@withContext r.value.left()
                    is Either.Right -> r.value
                }
    
                val wrote = fileSystem.writeFile(filePath, resolvedContent)
                if (!wrote) {
                    return@withContext DomainError.GitError.CommitFailed(
                        "Failed to write resolved content to: $filePath"
                    ).left()
                }
    
                lfsStaging.refusalFor(config, filePath)?.let { return@withContext it.left() }
                gitRepository.markResolved(config, filePath).onLeft { return@withContext it.left() }
            }
    
            val resolvedPaths = (sideResolutions.keys + hunkResolutions.keys).toList()
            markerGuard.firstWithMarkers(resolvedPaths)?.let { return@withContext markerGuard.refusal(it).left() }
            // No mass-change check here: during a merge the staged diff against HEAD includes the remote's own deletions.
            guards.markers(config, GuardScope.Staged)?.let { return@withContext refuse(it) }
    
            val message = buildCommitMessage(config, isMerge = true)
            boundedUnit(true, { it.left() }) { gitRepository.commit(config, message).also { if (it.isRight()) reloadMergedFiles(resolvedPaths) } }.onLeft { return@withContext it.left() }
    
            _syncState.value = SyncState.Idle
            Unit.right()
        }
        }


    /**
     * Applies an algorithmically-merged journal file: writes [mergedContent] to disk,
     * marks the file as resolved, commits the merge, reloads the page into the DB,
     * and pushes.  Called after the user approves the [JournalMergeReviewScreen].
     */
    suspend fun applyJournalMerge(
        graphId: String,
        filePath: String,
        mergedContent: String,
    ): Either<DomainError.GitError, Unit> =
        singleFlight.run(
            RunSpec(graphId, SyncOrigin.Resolve, "journal-merge"),
            fail = { it.left() },
            unreadable = { reason -> DomainError.GitError.CommitFailed(reason).left() },
        ) {
            withContext(PlatformDispatcher.IO) {
            val config = when (val result = configRepository.getConfig(graphId)) {
                is Either.Left -> return@withContext DomainError.GitError.CommitFailed(
                    result.value.message
                ).left()
                is Either.Right -> result.value
            } ?: return@withContext DomainError.GitError.CommitFailed("No git config for $graphId").left()
    
            markerGuard.refusalIfMarkers(filePath, mergedContent)?.let { return@withContext it.left() }
    
            if (!fileSystem.writeFile(filePath, mergedContent)) {
                return@withContext DomainError.GitError.CommitFailed(
                    "Failed to write merged content to: $filePath"
                ).left()
            }
    
            // filePath here is written to SAF just above via fileSystem.writeFile — markResolved()
            // (AndroidGitRepository, shadow-mirror mode) pulls that SAF content into the shadow tree
            // before staging; same ordering as resolveConflict().
            lfsStaging.refusalFor(config, filePath)?.let { return@withContext it.left() }
            gitRepository.markResolved(config, filePath).onLeft { return@withContext it.left() }
    
            guards.markers(config, GuardScope.Staged)?.let { return@withContext refuse(it) }
            val message = buildCommitMessage(config, isMerge = true)
            boundedUnit(true, { it.left() }) { gitRepository.commit(config, message).also { if (it.isRight()) reloadMergedFiles(listOf(filePath)) } }.onLeft { return@withContext it.left() }
    
            guards.changes(config, GuardScope.NetPush, liftMassChange = false)?.let { return@withContext refuse(it) }
            _syncState.value = SyncState.Pushing
            gitRepository.push(config).onLeft { err ->
                _syncState.value = SyncState.Error(err)
                return@withContext err.left()
            }
    
            refreshLocalStatus(graphId)
            val successAt = Clock.System.now().toEpochMilliseconds()
            lastSync.record(successAt)
            stalenessTracker.merged(successAt)
            _syncState.value = SyncState.Success(
                localCommitsMade = 0,
                remoteCommitsMerged = 1,
                lastSyncAt = successAt,
            )
            Unit.right()
        }
        }


    /**
     * Aborts an in-progress git merge, leaving the repository in its pre-merge state.
     * Call this when the user cancels conflict resolution.
     */
    suspend fun abortActiveMerge(graphId: String, confirm: suspend (AbortPreservation) -> Boolean = { true }): Either<DomainError.GitError, Unit> {
        // The confirm dialog can stay open for minutes, so it runs before the gate is taken; the gated step re-checks.
        val config = when (val result = configRepository.getConfig(graphId)) {
            is Either.Left -> return DomainError.GitError.CommitFailed(result.value.message).left()
            is Either.Right -> result.value
        } ?: return Unit.right()
        val prepared = withContext(PlatformDispatcher.IO) { guardedAbort.prepare(graphId, config) }.getOrElse { return it.left() }
        prepared.preservation?.let { if (!confirm(it)) return DomainError.GitError.CommitFailed("Abort cancelled").left() }
        return singleFlight.run(
            RunSpec(graphId, SyncOrigin.Resolve, "abort-merge"),
            fail = { it.left() },
            unreadable = { reason -> DomainError.GitError.CommitFailed(reason).left() },
        ) {
            withContext(PlatformDispatcher.IO) {
                guardedAbort.execute(config, prepared).also { result ->
                    if (result.isRight()) _syncState.value = SyncState.Idle
                }
            }
        }
    }


    /**
     * Starts a periodic sync loop that calls [fetchOnly] every [intervalMinutes] minutes.
     * Cancels any existing periodic sync before starting.
     */
    fun startPeriodicSync(graphId: String, intervalMinutes: Int, immediateFirstTick: Boolean = false, jitterMinutes: Int = 0) {
        periodicGraphId = graphId
        periodicDriver.start(intervalMinutes, immediateFirstTick, jitterMinutes)
    }

    /** Cancels the periodic sync loop without shutting down the service. */
    fun stopPeriodicSync() = periodicDriver.stop()

    /**
     * Shuts down this service, cancelling all coroutines — including any pending
     * [rateLimitRetry], since it is a child of [scope] and was never a
     * `rememberCoroutineScope()` (Task 3.4.2d).
     */
    fun shutdown() {
        scope.cancel()
    }

    companion object {
        /** Paths reloaded per chunk inside the merge unit; bounds each progress step. */
        internal const val RELOAD_CHUNK_SIZE = 200
        private const val COMMIT_UNIT_TIMEOUT_MS = 300_000L
        private const val ABANDONED_UNIT = "*abandoned-commit-unit*"
        private const val GATE_HELD_WARN_MS = 30_000L

        /**
         * Task 3.4.2a: fallback delay (seconds) for [RateLimitRetry] when a
         * [DomainError.GitError.RateLimited] carries no `retryAfterSeconds` — matches the existing
         * `WasmSectionSyncService.githubFetch` exponential-backoff precedent's cap of 60s.
         */
        const val DEFAULT_RATE_LIMIT_RETRY_SECONDS = RateLimitRetry.DEFAULT_SECONDS
    }
}
