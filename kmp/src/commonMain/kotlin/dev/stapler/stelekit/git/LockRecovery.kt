// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

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
            record.host != self.host -> LockVerdict.OwnerUnknown(UnknownReason.DifferentHost)
            bootIdsDiffer(record, self) -> LockVerdict.ClearedDeadOwner
            else -> probe(record, liveness)
        }
    }

    /** The lock files a verdict allows deleting: none unless it [LockVerdict.deletes], and never a non-allow-listed path. */
    fun deletableLocks(verdict: LockVerdict, lockFiles: LockFiles): List<LockFileEntry> =
        if (verdict.deletes) lockFiles.listAllowListedLocks().filter { LockAllowList.isAllowListed(it.path) } else emptyList()

    // A record from another boot proves its writer is gone; tick counts are only comparable within one boot.
    private fun bootIdsDiffer(record: LockOwnerRecord, self: LockOwnerRecord): Boolean =
        record.bootId != null && self.bootId != null && record.bootId != self.bootId

    private fun probe(record: LockOwnerRecord, liveness: ProcessLiveness): LockVerdict =
        when (liveness.probe(record.pid, record.processStartTicks, record.pidNs, record.host)) {
            Liveness.Alive -> LockVerdict.OwnerAlive
            Liveness.Dead -> LockVerdict.ClearedDeadOwner
            Liveness.Unknown -> LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown)
        }
}
