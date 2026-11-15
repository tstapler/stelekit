// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Who this process is, as written into the owner record; the same values for every repository in the process. */
data class ProcessIdentity(
    val instanceId: InstanceId,
    val pid: Long,
    val startTicks: Long?,
    val pidNs: String?,
    val host: String,
    val bootId: String?,
)

/**
 * The shared claim plumbing behind `GitRepository.beginOwnedOperation` and friends on JVM and Android: resolves the
 * canonical record key, builds the [LockFiles] for a resolved git directory and runs [LockRecovery]. It remembers
 * the [ClaimedRun] so [end] can hand it back. Blocking file I/O: call from an IO dispatcher.
 */
class OwnedOperationSupport(
    private val liveness: ProcessLiveness,
    private val identity: ProcessIdentity,
    private val registry: ActiveRunRegistry = SHARED_REGISTRY,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val lockFilesFor: (File) -> LockFiles = ::JavaIoLockFiles,
) {
    private val logger = Logger("OwnedOperationSupport")
    private val runs = ConcurrentHashMap<String, ClaimedRun>()

    fun begin(gitDir: File, info: OwnedOperationInfo): Either<DomainError.GitError, LockVerdict> {
        val key = keyFor(gitDir)
        val files = lockFilesFor(gitDir)
        val self = selfRecord(info.op, info.origin.name)
        return try {
            LockRecovery.claim(key, files, liveness, self, registry, nowMs).fold(
                { error -> refusalToVerdict(error, key, files) },
                { run ->
                    runs[key] = run
                    run.verdict.right()
                },
            )
        } catch (e: IOException) {
            logger.warn("claim failed on I/O; failing closed", e)
            LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported).right()
        }
    }

    fun end(gitDir: File) {
        val key = keyFor(gitDir)
        val run = runs.remove(key) ?: return
        LockRecovery.end(run, lockFilesFor(gitDir), registry)
    }

    /** Heartbeat; a no-op unless this support holds a run for [gitDir]. */
    fun touch(gitDir: File) {
        if (!runs.containsKey(keyFor(gitDir))) return
        try {
            lockFilesFor(gitDir).touchRecord()
        } catch (e: IOException) {
            logger.warn("owner heartbeat failed", e)
        }
    }

    fun forceRemove(gitDir: File): Either<DomainError.GitError, Unit> {
        val key = keyFor(gitDir)
        return try {
            LockRecovery.forceRemove(key, lockFilesFor(gitDir), liveness, selfRecord("force-remove", SyncOrigin.UiPress.name), registry)
                .map { }
        } catch (e: IOException) {
            logger.warn("force remove failed on I/O", e)
            DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)).left()
        }
    }

    /** Read-only verdict for a caller that must never delete (the legacy `removeStaleLockFile`). */
    fun peekVerdict(gitDir: File): LockVerdict {
        val files = lockFilesFor(gitDir)
        return try {
            LockRecovery.decide(files.readRecord(), files, liveness, selfRecord("peek", SyncOrigin.UiPress.name))
        } catch (e: IOException) {
            logger.warn("could not read owner record; failing closed", e)
            LockVerdict.OwnerUnknown(UnknownReason.TornRecord)
        }
    }

    /**
     * Retry-loop hook: inside an owned run it does nothing (a lock there is this run's own); outside one it claims
     * and immediately ends, so only a provably dead owner's locks are ever cleared. Never throws.
     */
    fun recoverBeforeRetry(gitDir: File) {
        if (registry.isActive(keyFor(gitDir))) return
        try {
            val verdict = begin(gitDir, OwnedOperationInfo("retry-recover", SyncOrigin.Repair)).getOrNull()
            if (verdict == LockVerdict.NoLock || verdict == LockVerdict.ClearedOwn || verdict == LockVerdict.ClearedDeadOwner) end(gitDir)
        } catch (e: Exception) {
            logger.warn("retry lock recovery failed", e)
        }
    }

    // OwnerAlive surfaces from the claim as SyncInProgress(null); tell it apart from a contended recovery lock.
    private fun refusalToVerdict(
        error: DomainError.GitError,
        key: String,
        files: LockFiles,
    ): Either<DomainError.GitError, LockVerdict> = when {
        error is DomainError.GitError.StaleLockFile && error.verdict != null -> error.verdict.right()
        error is DomainError.GitError.SyncInProgress && error.heldBy == null && !registry.isActive(key) &&
            LockRecovery.decide(files.readRecord(), files, liveness, selfRecord("peek", SyncOrigin.UiPress.name)) == LockVerdict.OwnerAlive ->
            LockVerdict.OwnerAlive.right()
        else -> error.left()
    }

    private fun selfRecord(op: String, origin: String) = LockOwnerRecord(
        instanceId = identity.instanceId,
        pid = identity.pid,
        processStartTicks = identity.startTicks,
        pidNs = identity.pidNs,
        host = identity.host,
        bootId = identity.bootId,
        op = op,
        origin = origin,
        startedAtMs = nowMs(),
    )

    companion object {
        /** One registry per process: every claimer in this JVM must see the same active runs. */
        val SHARED_REGISTRY = ActiveRunRegistry()

        fun keyFor(gitDir: File): String {
            val record = File(gitDir, JavaIoLockFiles.RECORD_NAME)
            return try {
                record.canonicalPath
            } catch (_: IOException) {
                record.absolutePath
            }
        }
    }
}
