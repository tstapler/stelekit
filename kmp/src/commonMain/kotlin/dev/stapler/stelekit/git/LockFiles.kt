// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** An allow-listed git lock file and when it was last modified; [path] is relative to the git directory. */
data class LockFileEntry(val path: String, val mtimeMs: Long)

/** Raw owner-record file content plus its mtime (the owner's last heartbeat). Empty [content] is a torn record. */
data class RecordRead(val content: String, val mtimeMs: Long)

/** Held kernel advisory lock on `.git/stelekit-sync.recover`; the kernel releases it if the holder dies. */
interface RecoveryLockHandle {
    fun release()
}

/** The filesystem rejected the recovery lock (network or FUSE mounts). Callers fail closed with `RecoveryLockUnsupported`. */
class RecoveryLockUnsupportedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Port over one repository's `.git` directory for [LockRecovery.decide] and the claim protocol.
 * Blocking java.io/NIO in the one adapter, so callers run it off the main thread.
 */
interface LockFiles {
    /** Allow-listed lock files currently present (see [LockAllowList]), with mtimes. */
    fun listAllowListedLocks(): List<LockFileEntry>

    /** The owner record, or null when the file does not exist. */
    fun readRecord(): RecordRead?

    /** Atomically publishes [content] as the record; false when a record already exists (nothing is overwritten). */
    fun createRecordExclusive(content: String): Boolean

    /** Heartbeat: bump the record's mtime. False when the record is gone. */
    fun touchRecord(): Boolean

    /** Deletes the record file. */
    fun deleteRecord()

    /** Deletes one lock file by its git-dir-relative [path]. */
    fun delete(path: String)

    /** Takes the recovery lock without blocking: null when contended; throws [RecoveryLockUnsupportedException] when unsupported. */
    fun tryLockRecovery(): RecoveryLockHandle?
}
