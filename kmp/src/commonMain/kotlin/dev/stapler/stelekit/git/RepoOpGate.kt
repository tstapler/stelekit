// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Proof that [key] is held. Hand it back to [RepoOpGate.releaseOrHandOff] in a `finally`; a second release is a no-op.
 * [origin] is who holds it, reported to a coalesced caller as `SyncInProgress.heldBy`.
 */
class GateLease internal constructor(val key: String, val origin: SyncOrigin)

/** The lease handed straight to a follow-up run when a rerun was requested during the release; the gate never became free. */
class FollowUp internal constructor(val lease: GateLease)

/** Turns a path into its canonical form (resolving symlinks, `..`, trailing slashes); throws when it cannot. */
fun interface PathCanonicalizer {
    fun canonicalize(path: String): String
}

/**
 * Process-wide per-repository mutual exclusion, keyed by [keyFor] (ADR-001 decisions 2-5, 7, 8). One instance per
 * process, a required constructor parameter of every participant; there is no global default.
 *
 * Every transition is one compare-and-set on an immutable map, so the rerun bit is checked and cleared in the same
 * step that frees or hands over the lease: a press can neither be lost nor yield two runs.
 */
class RepoOpGate {
    private class Held(val lease: GateLease, val rerun: Boolean)

    private val state = MutableStateFlow<Map<String, Held>>(emptyMap())

    /** The lease for [key], or null when another operation holds it (never waits). */
    fun tryAcquire(key: String, origin: SyncOrigin): GateLease? {
        while (true) {
            val current = state.value
            if (key in current) return null
            val lease = GateLease(key, origin)
            if (state.compareAndSet(current, current + (key to Held(lease, rerun = false)))) return lease
        }
    }

    fun isHeld(key: String): Boolean = key in state.value

    /** True while a coalesced press has queued the one follow-up for [key]. */
    fun isRerunRequested(key: String): Boolean = state.value[key]?.rerun == true

    /** Origin of the current holder, or null when free. */
    fun heldBy(key: String): SyncOrigin? = state.value[key]?.lease?.origin

    /** Suspends until [key] is free; says nothing about it staying free (advisory, for retries). */
    suspend fun awaitFree(key: String) {
        state.first { key !in it }
    }

    /** Waits for [key] and takes it. Quiesce only: every other entry point coalesces through [tryAcquire]. */
    suspend fun acquire(key: String, origin: SyncOrigin): GateLease {
        while (true) {
            tryAcquire(key, origin)?.let { return it }
            awaitFree(key)
        }
    }

    /**
     * Records that a [SyncOrigin.UiPress] lost the race, so exactly one follow-up runs after release.
     * Returns false (and records nothing) when the key is free or a [SyncOrigin.Quiesce] hold owns it:
     * a follow-up fired at the end of a storage move would run against relocated storage.
     */
    fun requestRerun(key: String): Boolean {
        while (true) {
            val current = state.value
            val held = current[key] ?: return false
            if (held.lease.origin == SyncOrigin.Quiesce) return false
            if (held.rerun) return true
            if (state.compareAndSet(current, current + (key to Held(held.lease, rerun = true)))) return true
        }
    }

    /**
     * Frees [lease], or, when a rerun was requested, keeps the key held and returns a [FollowUp] lease
     * (origin [SyncOrigin.FollowUp]) for the caller to run. Deliberately not `suspend`: it must run in a
     * `finally` after cancellation. Releasing a lease that is not the current holder does nothing.
     */
    fun releaseOrHandOff(lease: GateLease): FollowUp? {
        while (true) {
            val current = state.value
            val held = current[lease.key]
            if (held == null || held.lease !== lease) return null
            if (!held.rerun) {
                if (state.compareAndSet(current, current - lease.key)) return null
            } else {
                val next = GateLease(lease.key, SyncOrigin.FollowUp)
                if (state.compareAndSet(current, current + (lease.key to Held(next, rerun = false)))) return FollowUp(next)
            }
        }
    }

    companion object {
        private const val KEY_HEX_CHARS = 16

        /** Hash of an already-canonical path. Distinct paths never share a key by construction (`/a-b` vs `/a/b`). */
        fun keyForCanonicalPath(canonicalPath: String): String {
            val trimmed = if (canonicalPath.length > 1) canonicalPath.trimEnd('/', '\\') else canonicalPath
            return ContentHasher.sha256(trimmed).take(KEY_HEX_CHARS)
        }

        /**
         * The one key for a resolved `.git` directory. Any canonicalisation failure is null (`Unreadable`):
         * the caller fails closed and never falls back to a different key (pre-mortem P1-1).
         */
        fun keyForOrNull(gitDir: String, canonicalizer: PathCanonicalizer): String? =
            try {
                keyForCanonicalPath(canonicalizer.canonicalize(gitDir))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        fun keyFor(gitDir: String, canonicalizer: PathCanonicalizer): String =
            keyForOrNull(gitDir, canonicalizer) ?: error("cannot canonicalise git dir: $gitDir")
    }
}
