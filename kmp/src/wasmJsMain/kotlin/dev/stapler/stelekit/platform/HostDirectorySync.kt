// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
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

    // ── Epic 3.2 (Task 3.2.2a/c): reconciliation dispatch collaborators ───────────────────────
    /**
     * Forward-declared per Epic 4.1's design (Task 3.2.2c) — Epic 4.1 (not yet implemented) will
     * add the flush/scheduling logic that drains this queue; Epic 3.2 only needs the field to
     * exist so `runHostReconciliation`'s `BrowserOnlyNeedsPush` branch has somewhere to enqueue.
     * `internal` (not `private`) so `HostDirectorySyncReconciliationTest.kt` (wasmJsTest, friend
     * source set) can assert on it directly, per validation.md's acceptance criteria.
     */
    internal val hostWritePending = mutableMapOf<String, DirtyEntry>()

    /**
     * Task 3.2.2a: constructor-injected-in-spirit but exposed as a settable `var` rather than a
     * constructor parameter — `hostDirectorySync` is composed into `PlatformFileSystem` in a
     * field initializer, before `GraphLoader` exists (`GraphLoader` is only ever constructed
     * later, per-active-graph, inside `App.kt`'s composition — see `RepositorySet.createGraphLoader`
     * usage). A constructor param with a real default would therefore permanently stay the no-op
     * default in production. Defaults to a no-op so tests/production code that never sets it
     * still compile and run safely. Set from `App.kt` alongside the other `FileSystem` write-behind
     * callbacks (`setOnFlushPreWrite`/`setOnFlushComplete`/`setOnFlushFailed`) via the matching
     * `FileSystem.setOnHostConflict` no-op-default interface method — mirrors this codebase's
     * established convention for wiring a `GraphLoader` callback into the platform layer without
     * `HostDirectorySync`/`PlatformFileSystem` importing `GraphLoader` directly (architecture-review.md
     * Blocker 1's independence goal). See [PlatformFileSystem]'s `setOnHostConflict` override.
     */
    internal var onHostConflict: (path: String, hostContent: String) -> Unit = { _, _ -> }

    /**
     * Task 3.1.2b: the last [ReconciliationSummary] produced by [runHostReconciliation], read by
     * UI wiring (`FolderSyncSettings`'s `onConnect` callback) after [connectHostDirectory]
     * resolves, so the reconciliation summary screen can show real per-category counts rather
     * than only the `println` observability line. `null` until the first reconciliation runs.
     */
    internal var lastReconciliationSummary: ReconciliationSummary? = null
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

    // ── Epic 3.1 (Story 3.1.1): connectHostDirectory — reconcile, never import ────────────────
    /**
     * Task 3.1.1a: entry point for enabling live sync on an **already-populated** graph — the
     * Critical Finding's remediation. Calls [showDirectoryPicker] then [runHostReconciliation]
     * (Epic 3.2) — **never** `PlatformFileSystem.importUserDirToCache`, which is the unconditional
     * overwrite-only import path reserved for brand-new graphs via `pickDirectoryAsync()`. Only on
     * success is [hostDirHandle]/[hostGraphOpfsPath] set and persisted (reusing [attachFreshHandle]'s
     * [persistHostHandle] step) — a failure anywhere in this sequence (picker cancelled/denied,
     * reconciliation throwing mid-walk) leaves the handle unset and returns
     * [HostAccessState.NotApplicable], so no partial reconciliation is ever treated as complete
     * (design/ux.md Surface 8's error-state contract).
     */
    suspend fun connectHostDirectory(existingOpfsPath: String): HostAccessState {
        return try {
            val dirHandle = showDirectoryPicker()
            runHostReconciliation(dirHandle, existingOpfsPath)
            hostDirHandle = dirHandle
            hostGraphOpfsPath = existingOpfsPath
            val dirName = existingOpfsPath.substringAfterLast("/")
            persistHostHandle(graphIdProvider(), dirName, dirHandle)
            HostAccessState.Granted
        } catch (e: Throwable) {
            println("[SteleKit] connectHostDirectory failed for '$existingOpfsPath': ${e.message}")
            HostAccessState.NotApplicable
        }
    }

    // ── Epic 3.2 (Stories 3.2.1, 3.2.2): reconciliation walk, classification, dispatch ────────
    /**
     * Task 3.2.1a/b, 3.2.2a/b/c: walks [dirHandle] recursively (mirroring
     * `PlatformFileSystem.importUserDirToCache`'s traversal shape — reused by reference, not
     * duplicated, since [HostDirectorySync] doesn't own that function), classifies every path
     * present on either side via [classifyReconciliation]/[classifyReconciliationBytes], and
     * dispatches each [ReconciliationOutcome] to its documented action:
     * - [ReconciliationOutcome.Identical] — no-op.
     * - [ReconciliationOutcome.HostChangedConflict] — invokes [onHostConflict] (text paths only;
     *   see the `.md.stek` branch's doc note for why paranoid-mode conflicts are count-only here).
     * - [ReconciliationOutcome.HostOnlyNew] — imports via [CacheAccess], bytes-aware for `.md.stek`
     *   paths (adversarial-review.md Blocker 4).
     * - [ReconciliationOutcome.BrowserOnlyNeedsPush] — enqueues [hostWritePending] directly
     *   (Epic 4.1's flush loop will drain it once implemented).
     *
     * Paths present in [CacheAccess] but never visited by the host walk (Task 3.2.1b) are
     * classified as [ReconciliationOutcome.BrowserOnlyNeedsPush] against a `null` host side.
     *
     * Returns a [ReconciliationSummary] tallying every classification, also stashed in
     * [lastReconciliationSummary] for UI wiring that runs after [connectHostDirectory] resolves.
     */
    suspend fun runHostReconciliation(dirHandle: JsAny, opfsPath: String): ReconciliationSummary {
        var identicalCount = 0
        var hostChangedConflictCount = 0
        var hostOnlyNewCount = 0
        var browserOnlyNeedsPushCount = 0
        val hostVisitedPaths = mutableSetOf<String>()

        suspend fun walk(handle: JsAny, currentPath: String) {
            for (entry in listOpfsEntries(handle)) {
                val name = getEntryName(entry)
                val path = "$currentPath/$name"
                when {
                    isFileEntry(entry) && path.endsWith(".md.stek") -> {
                        hostVisitedPaths += path
                        val hostBytes = readOpfsFileAsBytes(entry)
                        if (hostBytes == null) {
                            println("[SteleKit] runHostReconciliation: failed to read '$path' from host, skipping")
                        } else {
                            val cacheBytes = cacheAccess.getBytes(path)
                            when (classifyReconciliationBytes(hostBytes, cacheBytes)) {
                                ReconciliationOutcome.Identical -> identicalCount++
                                ReconciliationOutcome.HostChangedConflict -> {
                                    hostChangedConflictCount++
                                    // Deliberately does NOT call onHostConflict here: that callback
                                    // is String-typed (GraphLoader.emitExternalFileChange takes
                                    // plaintext markdown), and decoding paranoid-mode ciphertext to
                                    // a String to satisfy that signature is exactly what
                                    // adversarial-review.md Blocker 4 forbids. Counted in the
                                    // summary; a bytes-aware conflict surface is out of this
                                    // dispatch's scope (Epic 3.1-3.3).
                                }
                                ReconciliationOutcome.HostOnlyNew -> {
                                    hostOnlyNewCount++
                                    cacheAccess.setBytes(path, hostBytes)
                                    cacheAccess.writeOpfsMirrorBytes(path, hostBytes)
                                }
                                ReconciliationOutcome.BrowserOnlyNeedsPush -> {
                                    // Unreachable: hostBytes is non-null in this branch (the walk
                                    // only visits paths that exist on the host), so
                                    // classifyReconciliationBytes can never return this variant
                                    // here. Kept for `when` exhaustiveness (type-driven design —
                                    // a future ReconciliationOutcome variant fails the build here).
                                }
                            }
                        }
                    }
                    isFileEntry(entry) -> {
                        hostVisitedPaths += path
                        val hostContent = readOpfsFile(entry)
                        if (hostContent == null) {
                            println("[SteleKit] runHostReconciliation: failed to read '$path' from host, skipping")
                        } else {
                            val cacheContent = cacheAccess.get(path)
                            when (classifyReconciliation(hostContent, cacheContent)) {
                                ReconciliationOutcome.Identical -> identicalCount++
                                ReconciliationOutcome.HostChangedConflict -> {
                                    hostChangedConflictCount++
                                    onHostConflict(path.removePrefix("$opfsPath/"), hostContent)
                                }
                                ReconciliationOutcome.HostOnlyNew -> {
                                    hostOnlyNewCount++
                                    cacheAccess.set(path, hostContent)
                                    cacheAccess.writeOpfsMirror(path, hostContent)
                                }
                                ReconciliationOutcome.BrowserOnlyNeedsPush -> {
                                    // Unreachable here — see the `.md.stek` branch's identical note.
                                }
                            }
                        }
                    }
                    isDirectoryEntry(entry) -> walk(entry, path)
                }
            }
        }

        walk(dirHandle, opfsPath)

        // Task 3.2.1b: paths known to CacheAccess but never visited by the host walk above.
        for (path in cacheAccess.keysUnder(opfsPath)) {
            if (path in hostVisitedPaths) continue
            val cacheBytes = cacheAccess.getBytes(path)
            val outcome = if (cacheBytes != null) {
                classifyReconciliationBytes(null, cacheBytes)
            } else {
                classifyReconciliation(null, cacheAccess.get(path))
            }
            if (outcome == ReconciliationOutcome.BrowserOnlyNeedsPush) {
                browserOnlyNeedsPushCount++
                val repoRelative = path.removePrefix("$opfsPath/")
                hostWritePending[repoRelative] = DirtyEntry(DirtyOp.WRITE, Clock.System.now().toEpochMilliseconds())
            }
        }

        val summary = ReconciliationSummary(
            identical = identicalCount,
            hostChangedConflict = hostChangedConflictCount,
            hostOnlyNew = hostOnlyNewCount,
            browserOnlyNeedsPush = browserOnlyNeedsPushCount,
        )
        lastReconciliationSummary = summary
        println(
            "[SteleKit] reconciliation: ${summary.identical} identical, ${summary.hostChangedConflict} conflict, " +
                "${summary.hostOnlyNew} host-only, ${summary.browserOnlyNeedsPush} browser-only",
        )
        return summary
    }
}

/**
 * Task 3.1.2b: per-category tally of [ReconciliationOutcome]s produced by one
 * [HostDirectorySync.runHostReconciliation] pass — the return value that exposes classification
 * counts to UI wiring (`FolderSyncReconciliationProgress`'s summary state) rather than only the
 * `println` observability line.
 */
data class ReconciliationSummary(
    val identical: Int,
    val hostChangedConflict: Int,
    val hostOnlyNew: Int,
    val browserOnlyNeedsPush: Int,
)
