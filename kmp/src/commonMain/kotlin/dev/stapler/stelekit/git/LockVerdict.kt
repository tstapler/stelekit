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
