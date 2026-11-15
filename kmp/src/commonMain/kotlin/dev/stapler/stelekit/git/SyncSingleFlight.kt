// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of the read-only key resolution that precedes every gate acquisition (ADR-001 decision 4). */
internal sealed interface GateKeyResolution {
    data class Resolved(val key: String, val config: GitConfig) : GateKeyResolution

    /** No git config for the graph; the operation's own no-config result applies. */
    data object NoConfig : GateKeyResolution

    /** Config unreadable or git dir unresolvable: fail closed, never pick another key. */
    data class Unreadable(val reason: String) : GateKeyResolution
}

/**
 * Who is calling and for which operation; [opName] lands in the owner record. [rerunOnLoss] is true only for a
 * [SyncOrigin.UiPress] sync without the mass-change lift: losing the race then queues exactly one follow-up.
 */
internal data class RunSpec(
    val graphId: String,
    val origin: SyncOrigin,
    val opName: String,
    val rerunOnLoss: Boolean = false,
)

/**
 * Single-flight wrapper for [GitSyncService]'s public mutating methods: resolve the gate key, take the lease, claim
 * the repository record, run the body, and release unconditionally (ADR-001 decisions 3, 4). A second trigger
 * coalesces into `SyncInProgress` and touches no state.
 */
internal class SyncSingleFlight(
    private val gate: RepoOpGate,
    private val repository: GitRepository,
    private val configRepository: GitConfigRepository,
    private val busyCounter: GitSyncBusyCounter,
    private val inFlight: MutableStateFlow<SyncRunInfo?>,
    private val clock: () -> Long,
    private val publishError: (DomainError.GitError) -> Unit,
    /** Runs the lease handed over by [RepoOpGate.releaseOrHandOff] as a [SyncOrigin.FollowUp] sync of the graph. */
    private val launchFollowUp: (graphId: String, FollowUp) -> Unit,
    /** Owns the heartbeat job; never a composable's scope. */
    private val scope: CoroutineScope,
    private val heartbeatIntervalMs: Long = OwnerHeartbeat.DEFAULT_INTERVAL_MS,
) {
    private val logger = Logger("SyncSingleFlight")

    /** Last key this service resolved; lets the synchronous driver ask the gate whether anything holds the repository. */
    @kotlin.concurrent.Volatile private var lastKey: String? = null

    /** True while any operation (this service's, a worker's or a quiesce hold) holds the repository gate. */
    fun isGateHeld(): Boolean = lastKey?.let(gate::isHeld) == true

    /** Reads config and derives the canonical key; retries once and never lets a transient failure pick another key. */
    suspend fun resolveGateKey(graphId: String): GateKeyResolution {
        var last = "unknown"
        repeat(ATTEMPTS) {
            try {
                when (val r = configRepository.getConfig(graphId)) {
                    is Either.Left -> last = r.value.message
                    is Either.Right -> {
                        val config = r.value ?: return GateKeyResolution.NoConfig
                        val key = withContext(PlatformDispatcher.IO) { repository.gateKeyFor(config) }
                        if (key != null) {
                            lastKey = key
                            return GateKeyResolution.Resolved(key, config)
                        }
                        last = "cannot resolve the git directory of ${config.repoRoot.value}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e.message ?: e::class.simpleName ?: "error"
            }
        }
        return GateKeyResolution.Unreadable("Failed to load git config: $last")
    }

    /**
     * Runs [body] as the single holder of the graph's repository key. [fail] wraps a refusal in the method's result
     * type; [unreadable] is the existing config-error path (no repository call). A coalesced call returns before any
     * state write, rate-limit cancel or counter begin.
     */
    suspend fun <T> run(
        spec: RunSpec,
        fail: (DomainError.GitError) -> T,
        unreadable: (String) -> T,
        body: suspend () -> T,
    ): T {
        val (graphId, origin) = spec
        val resolution = resolveGateKey(graphId)
        val key = (resolution as? GateKeyResolution.Resolved)?.key ?: "graph:$graphId"
        if (resolution is GateKeyResolution.Unreadable && inFlight.value != null) {
            return coalesced(key, spec, fail, inFlight.value?.origin)
        }
        val lease = gate.tryAcquire(key, origin) ?: return coalesced(key, spec, fail, gate.heldBy(key))
        return execute(lease, spec, resolution, fail, unreadable, body)
    }

    /**
     * Runs [body] under [followUp]'s already-held lease (a queued press's one rerun). The key is re-resolved because
     * the repository may have changed since the press; a key that no longer matches only releases.
     */
    suspend fun <T> runFollowUp(
        followUp: FollowUp,
        spec: RunSpec,
        fail: (DomainError.GitError) -> T,
        unreadable: (String) -> T,
        body: suspend () -> T,
    ): T {
        logger.info("rerun started key=${followUp.lease.key} graphId=${spec.graphId}")
        val resolution = try {
            resolveGateKey(spec.graphId)
        } catch (e: CancellationException) {
            release(followUp.lease, spec.graphId)
            throw e
        }
        if (resolution is GateKeyResolution.Resolved && resolution.key != followUp.lease.key) {
            release(followUp.lease, spec.graphId)
            return fail(DomainError.GitError.SyncInProgress(null))
        }
        return execute(followUp.lease, spec, resolution, fail, unreadable, body)
    }

    private fun <T> coalesced(key: String, spec: RunSpec, fail: (DomainError.GitError) -> T, heldBy: SyncOrigin?): T {
        val queued = spec.rerunOnLoss && gate.requestRerun(key)
        if (queued) inFlight.update { it?.copy(followUpQueued = true) }
        logger.info("decision=coalesced key=$key origin=${spec.origin} heldBy=$heldBy followUpQueued=$queued")
        return fail(DomainError.GitError.SyncInProgress(heldBy))
    }

    /** Acquire-to-release structure. Nothing that can throw sits between the lease and the `try`. */
    private suspend fun <T> execute(
        lease: GateLease,
        spec: RunSpec,
        resolution: GateKeyResolution,
        fail: (DomainError.GitError) -> T,
        unreadable: (String) -> T,
        body: suspend () -> T,
    ): T {
        val config = (resolution as? GateKeyResolution.Resolved)?.takeIf { it.key == lease.key }?.config
        var claimed = false
        var counted = false
        var heartbeat: Job? = null
        try {
            inFlight.value = SyncRunInfo(lease.origin, clock(), spec.graphId, gate.isRerunRequested(lease.key))
            if (resolution is GateKeyResolution.Unreadable) return unreadable(resolution.reason)
            if (config != null) {
                val refusal = claim(config, OwnedOperationInfo(spec.opName, lease.origin))
                if (refusal != null) return fail(refusal)
                claimed = true
                heartbeat = startHeartbeat(config)
            }
            busyCounter.begin()
            counted = true
            return body()
        } finally {
            try {
                withContext(NonCancellable) {
                    heartbeat?.cancelAndJoin()
                    if (counted) busyCounter.end()
                    if (claimed && config != null) repository.endOwnedOperation(config)
                }
            } finally {
                inFlight.value = null
                release(lease, spec.graphId)
            }
        }
    }

    private fun release(lease: GateLease, graphId: String) {
        gate.releaseOrHandOff(lease)?.let { launchFollowUp(graphId, it) }
    }

    /** The owner record's mtime is its last proof of life; a failed touch only makes a later claimer fail closed. */
    private fun startHeartbeat(config: GitConfig): Job = scope.launch {
        while (isActive) {
            delay(heartbeatIntervalMs)
            try {
                repository.touchOwnedOperation(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("owner heartbeat failed", e)
            }
        }
    }

    /** Null when the run may proceed; otherwise the refusal to return. Deletes nothing on an unknown owner. */
    private suspend fun claim(config: GitConfig, info: OwnedOperationInfo): DomainError.GitError? =
        when (val r = repository.beginOwnedOperation(config, info)) {
            is Either.Left -> r.value.also { if (it !is DomainError.GitError.SyncInProgress) publishError(it) }
            is Either.Right -> when (val verdict = r.value) {
                LockVerdict.NoLock, LockVerdict.ClearedOwn, LockVerdict.ClearedDeadOwner -> null
                LockVerdict.OwnerAlive -> DomainError.GitError.SyncInProgress(null)
                is LockVerdict.OwnerUnknown, LockVerdict.NoRecord ->
                    DomainError.GitError.StaleLockFile("${config.repoRoot.value.trimEnd('/')}/.git/index.lock", verdict)
                        .also(publishError)
            }
        }

    private companion object {
        const val ATTEMPTS = 2
    }
}
