// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.HostHandleEnvelope
import dev.stapler.stelekit.git.model.gitApiJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.serialization.encodeToString
import kotlin.js.toJsString
import kotlin.time.Clock

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
 * This file currently establishes the shell (Task 1.6.1a/1.6.1b), Epic 1.7's
 * `opfsWriteDeferredFor` seam on [CacheAccess] (Task 1.7.1c), and Epic 2.1's handle retention
 * (`attachFreshHandle`/`persistHostHandle`) — every later phase in
 * `project_plans/web-local-folder-livesync/implementation/plan.md` builds its fields/methods onto
 * this class, never back onto `PlatformFileSystem`.
 */
class HostDirectorySync(
    /**
     * Reads `PlatformFileSystem.graphId`'s *live* value rather than a value captured at
     * construction time. `HostDirectorySync` is composed into `PlatformFileSystem` in a field
     * initializer — before `preload(graphPath)` later mutates `graphId` from `"default"` to the
     * real graph id — so a plain `String` parameter would permanently key IndexedDB persistence
     * under `"default"`. The composition call site passes `graphIdProvider = { graphId }`, a
     * closure over the mutable field, so every read here sees the current value.
     */
    private val graphIdProvider: () -> String,
    private val cacheAccess: CacheAccess,
    private val scope: CoroutineScope,
) {
    // ── Epic 2.1 (Story 2.1.1): retain the freshly picked handle + persist it to IndexedDB ────
    // `internal` rather than `private`: HostDirectorySyncHandleRetentionTest.kt (wasmJsTest, friend
    // source set of wasmJsMain) asserts on these directly per validation.md's acceptance criteria
    // ("hostDirectorySync.hostDirHandle is set to..."); still invisible to any consumer outside
    // this module.
    internal var hostDirHandle: JsAny? = null
    internal var hostGraphOpfsPath: String? = null
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

    /**
     * Task 2.1.1b: called from `PlatformFileSystem.pickDirectoryAsync()` immediately after the
     * picked directory has been imported into OPFS. Retains [dirHandle]/[opfsPath] in memory
     * (closing the gap identified in `research/architecture.md` §0, where the local `dirHandle`
     * previously went out of scope at the end of `pickDirectoryAsync()`), then best-effort
     * persists the handle to IndexedDB so a future session (Epic 2.2) can offer to resume it.
     */
    suspend fun attachFreshHandle(dirHandle: JsAny, opfsPath: String) {
        hostDirHandle = dirHandle
        hostGraphOpfsPath = opfsPath
        val dirName = opfsPath.substringAfterLast("/")
        persistHostHandle(graphIdProvider(), dirName, dirHandle)
    }

    /**
     * Task 2.1.1c: wraps [graphId]/[dirName] in a [HostHandleEnvelope] and stores it in IndexedDB
     * (`stelekit-host-handles`, `idbOpenHandleDb`/`idbPutHandle`) keyed by [graphId]. A failure
     * anywhere in this path (DB open, serialization, put) is logged and swallowed — matching
     * `HostDirectoryInterop.kt`'s failure-tolerant `println("[SteleKit] ...")` convention — and
     * must never fail the directory pick itself, since [attachFreshHandle] has already retained
     * the real handle in memory regardless of whether persistence succeeds. The handle itself
     * (`_handle`) is not part of the persisted envelope — only its `graphId`/`dirName`/
     * `storedAtMillis` metadata is — a `FileSystemDirectoryHandle` is a structured-clone-only
     * opaque value with no meaningful JSON shape (see [HostHandleEnvelope]'s doc comment); it is
     * kept as a parameter so a future session-resume path (Epic 2.2) can extend this method to
     * persist the handle itself alongside its envelope without a signature change.
     */
    private suspend fun persistHostHandle(graphId: String, dirName: String, _handle: JsAny) {
        try {
            val db = idbOpenHandleDb()
            val envelope = HostHandleEnvelope(
                graphId = graphId,
                dirName = dirName,
                storedAtMillis = Clock.System.now().toEpochMilliseconds(),
            )
            val encoded = gitApiJson.encodeToString(envelope)
            idbPutHandle(db, graphId, encoded.toJsString())
        } catch (e: Throwable) {
            println("[SteleKit] persistHostHandle failed for graphId=$graphId: ${e.message}")
        }
    }
}
