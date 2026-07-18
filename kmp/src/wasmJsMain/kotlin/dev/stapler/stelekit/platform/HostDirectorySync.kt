// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/**
 * Epic 1.6 (architecture-review.md Blocker 1 remediation): standalone collaborator that owns all
 * Phase 2-7 host-directory-sync state and behavior — handle lifecycle, reconciliation, the
 * write-through queue, the poller, `FileSystemObserver` glue, both lock types, and the rename
 * protocol. [PlatformFileSystem] composes exactly one instance (`hostDirectorySync`) and delegates
 * only the seven `FileSystem`-interface touch points (`writeFile`/`writeFileBytes`/`deleteFile`/
 * `renameFile`/`getLastModifiedTime`/`listFilesWithModTimes`/`hostDirectoryAccessState`) to it —
 * no Phase 2-7 field or method is ever added to `PlatformFileSystem` itself. Mirrors this
 * codebase's existing `FileRegistry`/`GraphFileWatcher` split from `GraphLoader` on JVM/Android.
 *
 * This file currently establishes only the shell (Task 1.6.1a/1.6.1b) plus Epic 1.7's
 * `opfsWriteDeferredFor` seam on [CacheAccess] (Task 1.7.1c) — every later phase in
 * `project_plans/web-local-folder-livesync/implementation/plan.md` builds its fields/methods onto
 * this class, never back onto `PlatformFileSystem`.
 */
class HostDirectorySync(
    private val graphId: String,
    private val cacheAccess: CacheAccess,
    private val scope: CoroutineScope,
) {
    /**
     * Small constructor-injected interface [HostDirectorySync] uses to read/write
     * `PlatformFileSystem`'s `cache`/`bytesCache` without owning either map — keeps `cache`/
     * `bytesCache` themselves on `PlatformFileSystem` (they also back non-host-synced reads/writes)
     * while giving this class the narrow access it needs (architecture-review.md Blocker 1
     * remediation). The `*Bytes`/`writeOpfsMirrorBytes` methods (adversarial-review.md Blocker 4
     * remediation) mirror the text methods against `bytesCache`, so reconciliation (Epic 3.2) and
     * the poller (Epic 5.1) can read/write `.md.stek` paranoid-mode content without ever routing
     * encrypted bytes through the `String`-typed methods.
     */
    interface CacheAccess {
        fun get(path: String): String?
        fun set(path: String, content: String)
        fun remove(path: String)
        fun getBytes(path: String): ByteArray?
        fun setBytes(path: String, data: ByteArray)
        fun removeBytes(path: String)

        /** The subset of cache keys (text or bytes) under a given OPFS path prefix. */
        fun keysUnder(opfsPath: String): Set<String>
        fun writeOpfsMirror(path: String, content: String)
        fun writeOpfsMirrorBytes(path: String, data: ByteArray)

        /**
         * Epic 1.7 (Task 1.7.1c): accessor for [path]'s currently in-flight OPFS-persisting write,
         * if any — `null` once the write has settled (or if [path] was never freshly written this
         * session). Phase 4's `scheduleHostWriteThrough` will `.await()` this before enqueueing
         * [path] into the host write-through queue, closing the crash window where a host push
         * could otherwise race ahead of the edit actually landing in OPFS.
         */
        fun opfsWriteDeferredFor(path: String): Deferred<Unit>?
    }
}
