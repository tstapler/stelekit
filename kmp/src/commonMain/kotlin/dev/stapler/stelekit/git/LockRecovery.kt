// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.logging.Logger
import kotlin.time.Clock

/** Why a claim could not tell whether the repository is free; every reason fails closed (nothing is deleted). */
enum class UnknownReason {
    TornRecord,
    LivenessUnknown,
    DifferentHost,
    ForeignLockSuspected,
    RecoveryLockUnsupported,
}

/** Outcome of reading the existing owner record against the lock files; see ADR-001 decision 12. */
sealed interface LockVerdict {
    /** No record and no allow-listed lock: nothing to recover. */
    data object NoLock : LockVerdict

    /** The record is this process's own and no run is active for it (the caller proves that): a corpse. Deleting verdict. */
    data object ClearedOwn : LockVerdict

    /** The writer is provably dead. Deleting verdict. */
    data object ClearedDeadOwner : LockVerdict

    /** The writer is, or may well be, running. */
    data object OwnerAlive : LockVerdict

    /** The owner could not be determined; fail closed. */
    data class OwnerUnknown(val reason: UnknownReason) : LockVerdict

    /** A lock file exists with no record at all; delete nothing (a terminal or IDE `git` may own it). */
    data object NoRecord : LockVerdict

    /** Only these verdicts permit deleting lock files (and then only allow-listed ones). */
    val deletes: Boolean get() = this == ClearedOwn || this == ClearedDeadOwner
}

/** The git-dir-relative lock files recovery may ever delete. `objects/` is never listed. */
object LockAllowList {
    private val FIXED = setOf("index.lock", "shallow.lock", "HEAD.lock", "packed-refs.lock", "config.lock")

    fun isAllowListed(relativePath: String): Boolean {
        val path = relativePath.replace('\\', '/').removePrefix("./")
        if ("/../" in "/$path/" || path.startsWith("/")) return false
        return path in FIXED || (path.startsWith("refs/") && path.endsWith(".lock") && path.length > "refs/.lock".length)
    }
}

object LockRecovery {
    /**
     * Pure over the **existing** record (ours does not exist yet): reads, never writes or deletes.
     * Rule order: torn, own instance, different host, boot id, liveness probe.
     */
    fun decide(
        existingRecord: RecordRead?,
        lockFiles: LockFiles,
        liveness: ProcessLiveness,
        self: LockOwnerRecord,
    ): LockVerdict {
        if (existingRecord == null) {
            val anyLock = lockFiles.listAllowListedLocks().any { LockAllowList.isAllowListed(it.path) }
            return if (anyLock) LockVerdict.NoRecord else LockVerdict.NoLock
        }
        val record = when (val decoded = LockOwnerRecord.decode(existingRecord.content)) {
            RecordDecode.Torn -> return LockVerdict.OwnerUnknown(UnknownReason.TornRecord)
            is RecordDecode.Valid -> decoded.record
        }
        return when {
            record.instanceId == self.instanceId -> LockVerdict.ClearedOwn
            record.host != self.host || machinesDiffer(record, self) -> LockVerdict.OwnerUnknown(UnknownReason.DifferentHost)
            bootIdsDiffer(record, self) -> LockVerdict.ClearedDeadOwner
            else -> probe(record, liveness, self)
        }
    }

    /** The lock files a verdict allows deleting: none unless it [LockVerdict.deletes], and never a non-allow-listed path. */
    fun deletableLocks(verdict: LockVerdict, lockFiles: LockFiles): List<LockFileEntry> =
        if (verdict.deletes) lockFiles.listAllowListedLocks().filter { LockAllowList.isAllowListed(it.path) } else emptyList()

    // A record from another boot proves its writer is gone; tick counts are only comparable within one boot.
    private fun bootIdsDiffer(record: LockOwnerRecord, self: LockOwnerRecord): Boolean =
        record.bootId != null && self.bootId != null && record.bootId != self.bootId

    private fun machinesDiffer(record: LockOwnerRecord, self: LockOwnerRecord): Boolean =
        record.machineId != null && self.machineId != null && record.machineId != self.machineId

    // Without pid namespaces "this pid is gone" only holds on the machine that wrote it; a shared hostname proves nothing.
    private fun sameProvenMachine(record: LockOwnerRecord, self: LockOwnerRecord): Boolean =
        record.pidNs != null || self.pidNs != null || (record.machineId != null && record.machineId == self.machineId)

    private fun probe(record: LockOwnerRecord, liveness: ProcessLiveness, self: LockOwnerRecord): LockVerdict =
        when (liveness.probe(record.pid, record.processStartTicks, record.pidNs, record.host)) {
            Liveness.Alive -> LockVerdict.OwnerAlive
            Liveness.Dead ->
                if (sameProvenMachine(record, self)) LockVerdict.ClearedDeadOwner else LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown)
            Liveness.Unknown -> LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown)
        }

    private val logger = Logger("LockRecovery")

    /**
     * Claims the repository for [self] (ADR-001 decision 12, steps 0-6). Blocking file I/O through [lockFiles].
     * [key] is the canonical owner-record path. Left: [DomainError.GitError.SyncInProgress] when another run
     * (in this process, or another process holding the recovery lock or a live record) owns it, or
     * [DomainError.GitError.StaleLockFile] when the owner is unknown and nothing was deleted.
     */
    fun claim(
        key: String,
        lockFiles: LockFiles,
        liveness: ProcessLiveness,
        self: LockOwnerRecord,
        registry: ActiveRunRegistry,
        nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Either<DomainError.GitError, ClaimedRun> {
        // A registered run proves any own-instance record is live, so ClearedOwn below can only be a corpse.
        if (registry.isActive(key)) return DomainError.GitError.SyncInProgress(registry.originOf(key)).left()
        val existing = lockFiles.readRecord()
        val verdict = decide(existing, lockFiles, liveness, self)
        return when {
            verdict == LockVerdict.NoLock -> publish(key, lockFiles, self, registry, verdict)
            verdict.deletes -> recoverThenPublish(key, lockFiles, liveness, self, registry, existing, nowMs)
            else -> refusal(key, verdict).left()
        }
    }

    /** Ends a claimed run: removes our record only if it is still ours, then unregisters. Best-effort. */
    fun end(run: ClaimedRun, lockFiles: LockFiles, registry: ActiveRunRegistry) {
        try {
            val current = lockFiles.readRecord()?.let { (LockOwnerRecord.decode(it.content) as? RecordDecode.Valid)?.record }
            if (current != null && current.instanceId == run.record.instanceId && current.startedAtMs == run.record.startedAtMs) {
                lockFiles.deleteRecord()
            }
        } catch (e: Exception) {
            logger.warn("could not remove owner record", e)
        } finally {
            registry.unregister(run.key)
        }
    }

    private fun refusal(key: String, verdict: LockVerdict): DomainError.GitError = when (verdict) {
        LockVerdict.OwnerAlive -> DomainError.GitError.SyncInProgress(null)
        else -> DomainError.GitError.StaleLockFile(key, verdict)
    }

    private fun recoverThenPublish(
        key: String,
        lockFiles: LockFiles,
        liveness: ProcessLiveness,
        self: LockOwnerRecord,
        registry: ActiveRunRegistry,
        decidedOn: RecordRead?,
        nowMs: () -> Long,
    ): Either<DomainError.GitError, ClaimedRun> {
        if (!registry.tryEnterRecovery(key)) return DomainError.GitError.SyncInProgress(null).left()
        try {
            val handle = try {
                lockFiles.tryLockRecovery()
            } catch (_: RecoveryLockUnsupportedException) {
                return refusal(key, LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)).left()
            } ?: return DomainError.GitError.SyncInProgress(null).left()
            try {
                return recoverUnderLock(key, lockFiles, liveness, self, registry, decidedOn, nowMs)
            } finally {
                handle.release()
            }
        } finally {
            registry.exitRecovery(key)
        }
    }

    // Step 4 onwards: only a record byte-identical to the one decided on, re-probed dead under the lock, proceeds.
    private fun recoverUnderLock(
        key: String,
        lockFiles: LockFiles,
        liveness: ProcessLiveness,
        self: LockOwnerRecord,
        registry: ActiveRunRegistry,
        decidedOn: RecordRead?,
        nowMs: () -> Long,
    ): Either<DomainError.GitError, ClaimedRun> {
        val reread = lockFiles.readRecord()
        val verdict = decide(reread, lockFiles, liveness, self)
        if (verdict == LockVerdict.NoLock) return publish(key, lockFiles, self, registry, verdict)
        if (!verdict.deletes) return refusal(key, verdict).left()
        // A sibling claimer registers before it publishes, so a registered run means this record is live, not a corpse.
        if (registry.isActive(key)) return DomainError.GitError.SyncInProgress(registry.originOf(key)).left()
        // Another claimer replaced the record we decided on; it is theirs to act on, not ours.
        if (reread?.content != decidedOn?.content) return DomainError.GitError.SyncInProgress(null).left()
        val locks = deletableLocks(verdict, lockFiles)
        if (!withinForeignLockWindow(reread, locks, nowMs())) {
            return refusal(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected)).left()
        }
        if (!deleteUnchanged(lockFiles, locks)) {
            return refusal(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected)).left()
        }
        lockFiles.deleteRecord()
        return publish(key, lockFiles, self, registry, verdict)
    }

    // Step 5 (ADR-001 decision 12): a lock is the dead owner's only if it falls between its start and its last
    // heartbeat (plus slack) and has been quiet for 5 s; anything else may be a terminal or IDE `git` (fail closed).
    private fun withinForeignLockWindow(record: RecordRead?, locks: List<LockFileEntry>, nowMs: Long): Boolean {
        val decoded = (record?.let { LockOwnerRecord.decode(it.content) } as? RecordDecode.Valid)?.record ?: return false
        return locks.all { lock ->
            lock.mtimeMs >= decoded.startedAtMs - LOCK_START_SLACK_MS &&
                lock.mtimeMs <= record.mtimeMs + HEARTBEAT_SLACK_MS &&
                lock.mtimeMs <= nowMs - LOCK_QUIET_MS
        }
    }

    // Null when removal may proceed, else how long to wait (0: waiting alone will not help). A visible git process always
    // blocks. A dead or own owner's lock must sit inside that owner's window. An unsure owner's lock inside its window is
    // most likely live, so it needs idle record and locks: 5 min where no external git can exist, else 30 min because
    // git holds index.lock unchanged for a whole editor session and "cannot tell" is not "none".
    private fun removalWaitMs(verdict: LockVerdict, record: RecordRead?, locks: List<LockFileEntry>, lockFiles: LockFiles, nowMs: Long): Long? {
        val scan = lockFiles.gitProcessMayBeRunning()
        if (scan == true) return 0L
        if (verdict.deletes) return if (withinForeignLockWindow(record, locks, nowMs)) null else 0L
        // A local scan says nothing about another machine's git, so a foreign owner always takes the long threshold.
        val foreign = verdict == LockVerdict.OwnerUnknown(UnknownReason.DifferentHost)
        val threshold = if (scan == false && !foreign) UNSURE_STALE_MS else UNSURE_BLIND_STALE_MS
        val youngestAge = (locks.map { nowMs - it.mtimeMs } + listOfNotNull(record?.let { nowMs - it.mtimeMs })).minOrNull() ?: return null
        return if (youngestAge >= threshold) null else threshold - youngestAge
    }

    // Re-stat each lock right before deleting it: a different git run may have replaced it since it was examined.
    private fun deleteUnchanged(lockFiles: LockFiles, locks: List<LockFileEntry>): Boolean {
        for (lock in locks) {
            val now = lockFiles.statLock(lock.path) ?: continue
            if (now.mtimeMs != lock.mtimeMs) return false
            lockFiles.delete(lock.path)
        }
        return true
    }

    /** A record or lock idle this long (60 heartbeats; also covers sync lag of a shared folder) is treated as abandoned. */
    private const val UNSURE_STALE_MS = 300_000L
    private const val UNSURE_BLIND_STALE_MS = 1_800_000L

    private const val LOCK_START_SLACK_MS = 2_000L
    private const val HEARTBEAT_SLACK_MS = 10_000L
    private const val LOCK_QUIET_MS = 5_000L

    /**
     * The user-confirmed "remove lock file" path (ADR-001 decision 12a): the caller holds the gate; this takes the
     * recovery lock, re-decides, refuses on a live owner or a registered run, and otherwise deletes only allow-listed
     * locks (and the record when its owner is provably dead or us). Returns the deleted lock paths.
     */
    fun forceRemove(
        key: String,
        lockFiles: LockFiles,
        liveness: ProcessLiveness,
        self: LockOwnerRecord,
        registry: ActiveRunRegistry,
        nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): Either<DomainError.GitError, List<String>> {
        if (registry.isActive(key)) return DomainError.GitError.SyncInProgress(registry.originOf(key)).left()
        if (!registry.tryEnterRecovery(key)) return DomainError.GitError.SyncInProgress(null).left()
        try {
            val handle = try {
                lockFiles.tryLockRecovery()
            } catch (_: RecoveryLockUnsupportedException) {
                return refusal(key, LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)).left()
            } ?: return DomainError.GitError.SyncInProgress(null).left()
            try {
                val record = lockFiles.readRecord()
                val verdict = decide(record, lockFiles, liveness, self)
                if (verdict == LockVerdict.OwnerAlive) return DomainError.GitError.SyncInProgress(null).left()
                if (registry.isActive(key)) return DomainError.GitError.SyncInProgress(registry.originOf(key)).left()
                val locks = lockFiles.listAllowListedLocks().filter { LockAllowList.isAllowListed(it.path) }
                removalWaitMs(verdict, record, locks, lockFiles, nowMs())?.let { wait ->
                    return DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected), wait).left()
                }
                if (!deleteUnchanged(lockFiles, locks)) {
                    return refusal(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected)).left()
                }
                // The user confirmed; the record is cleared only after the staleness proof above, never on a live-looking owner.
                if (verdict.deletes || verdict is LockVerdict.OwnerUnknown) lockFiles.deleteRecord()
                return locks.map { it.path }.right()
            } finally {
                handle.release()
            }
        } finally {
            registry.exitRecovery(key)
        }
    }

    // Step 6: register first (so a record never exists unregistered), then publish exclusively and re-read.
    // A record we cannot write fails closed: running unrecorded would let a second process sync concurrently.
    private fun publish(
        key: String,
        lockFiles: LockFiles,
        self: LockOwnerRecord,
        registry: ActiveRunRegistry,
        verdict: LockVerdict,
    ): Either<DomainError.GitError, ClaimedRun> {
        val origin = SyncOrigin.entries.firstOrNull { it.name == self.origin }
        if (!registry.register(key, origin)) return DomainError.GitError.SyncInProgress(registry.originOf(key)).left()
        var created = false
        var replaced = false
        var claimed = false
        try {
            created = try {
                lockFiles.createRecordExclusive(self.encode())
            } catch (e: Exception) {
                logger.warn("could not write owner record; failing closed", e)
                return refusal(key, LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)).left()
            }
            if (!created) return DomainError.GitError.SyncInProgress(null).left()
            if (!isOurs(lockFiles.readRecord(), self)) {
                replaced = true
                return DomainError.GitError.SyncInProgress(null).left()
            }
            claimed = true
            return ClaimedRun(key, self, verdict).right()
        } finally {
            if (!claimed) abandon(key, lockFiles, registry, deleteRecord = created && !replaced)
        }
    }

    private fun abandon(key: String, lockFiles: LockFiles, registry: ActiveRunRegistry, deleteRecord: Boolean) {
        try {
            if (deleteRecord) lockFiles.deleteRecord()
        } catch (e: Exception) {
            logger.warn("could not remove our unclaimed owner record", e)
        } finally {
            registry.unregister(key)
        }
    }

    private fun isOurs(read: RecordRead?, self: LockOwnerRecord): Boolean {
        val record = (read?.let { LockOwnerRecord.decode(it.content) } as? RecordDecode.Valid)?.record ?: return false
        return record.instanceId == self.instanceId && record.startedAtMs == self.startedAtMs
    }
}

/** Proof that this process claimed the repository; hand it back to [LockRecovery.end]. */
class ClaimedRun internal constructor(val key: String, val record: LockOwnerRecord, val verdict: LockVerdict)
