// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Outcome of the read-only key resolution that precedes every gate acquisition (ADR-001 decision 4). */
internal sealed interface GateKeyResolution {
    data class Resolved(val key: String, val config: GitConfig) : GateKeyResolution

    /** No git config for the graph; the operation's own no-config result applies. */
    data object NoConfig : GateKeyResolution

    /** Config unreadable or git dir unresolvable: fail closed, never pick another key. */
    data class Unreadable(val reason: String) : GateKeyResolution
}

/** Who is calling and for which operation; [opName] lands in the owner record. */
internal data class RunSpec(val graphId: String, val origin: SyncOrigin, val opName: String)

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
) {
    private val logger = Logger("SyncSingleFlight")

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
                        if (key != null) return GateKeyResolution.Resolved(key, config)
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
            return coalesced(key, origin, fail, inFlight.value?.origin)
        }
        val lease = gate.tryAcquire(key, origin) ?: return coalesced(key, origin, fail, gate.heldBy(key))
        return execute(lease, spec, (resolution as? GateKeyResolution.Resolved)?.config, fail) {
            if (resolution is GateKeyResolution.Unreadable) unreadable(resolution.reason) else body()
        }
    }

    private fun <T> coalesced(key: String, origin: SyncOrigin, fail: (DomainError.GitError) -> T, heldBy: SyncOrigin?): T {
        logger.info("decision=coalesced key=$key origin=$origin heldBy=$heldBy")
        return fail(DomainError.GitError.SyncInProgress(heldBy))
    }

    /** Acquire-to-release structure. Nothing that can throw sits between the lease and the `try`. */
    private suspend fun <T> execute(
        lease: GateLease,
        spec: RunSpec,
        config: GitConfig?,
        fail: (DomainError.GitError) -> T,
        body: suspend () -> T,
    ): T {
        var claimed = false
        var counted = false
        try {
            inFlight.value = SyncRunInfo(lease.origin, clock(), spec.graphId)
            if (config != null) {
                val refusal = claim(config, OwnedOperationInfo(spec.opName, lease.origin))
                if (refusal != null) return fail(refusal)
                claimed = true
            }
            busyCounter.begin()
            counted = true
            return body()
        } finally {
            try {
                withContext(NonCancellable) {
                    if (counted) busyCounter.end()
                    if (claimed && config != null) repository.endOwnedOperation(config)
                }
            } finally {
                inFlight.value = null
                gate.releaseOrHandOff(lease)
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
