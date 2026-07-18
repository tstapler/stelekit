// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import dev.stapler.stelekit.git.model.HostHandleEnvelope
import dev.stapler.stelekit.git.model.gitApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.js.toJsString
import kotlin.time.Clock

/** Mirrors [PlatformFileSystem]'s `homeDir` constant — duplicated (not shared) deliberately, per
 * this file's class doc comment: [HostDirectorySync] must construct/operate standalone, without
 * a reference back to [PlatformFileSystem] (architecture-review.md Blocker 1's independence goal;
 * see `HostDirectorySyncConstructionTest.kt`). */
private const val HOME_DIR = "/stelekit"

// ── Epic 5.1 (Task 5.1.2b): HostDirectoryPoller backoff multipliers ───────────────────────────
/** Widens [HostDirectorySync.effectivePollIntervalMs] while [HostDirectorySync.isTabHidden]. */
private const val HIDDEN_POLL_BACKOFF_MULTIPLIER = 6L

/** Widens [HostDirectorySync.effectivePollIntervalMs] while [HostDirectorySync.observerConfirmedActive]. */
private const val OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER = 6L

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

    // ── Epic 2.2 (Task 2.2.1b): current HostAccessState, observed by commonMain UI ────────────
    /**
     * Mirrors [HostAccessState.NotApplicable]'s "no host directory" default until
     * [reconnectHostDirectory]/[requestHostDirectoryAccess]/[connectHostDirectory] resolves
     * otherwise. `FolderSyncStatusBadge` (Epic 2.3) collects [hostAccessStateFlow] via `App.kt`'s
     * nullable `StateFlow` parameter — mirrors [PlatformFileSystem]'s `dirtyFileCountFlow` pattern.
     */
    private val _hostAccessStateFlow = MutableStateFlow<HostAccessState>(HostAccessState.NotApplicable)
    val hostAccessStateFlow: StateFlow<HostAccessState> = _hostAccessStateFlow.asStateFlow()

    /**
     * Epic 4.1/4.2: live count of [hostWritePending], updated by [updatePendingCount] on every
     * enqueue/dequeue (both [scheduleHostWriteThrough]'s coalescing scheduler and
     * [runHostReconciliation]'s `BrowserOnlyNeedsPush` dispatch). Was a permanently-`0` stub
     * (Task 2.3.1c) until this queue became real — `App.kt`/`Main.kt`'s wiring is unchanged, only
     * this field's backing implementation.
     */
    private val _hostWritePendingCountFlow = MutableStateFlow(0)
    val hostWritePendingCountFlow: StateFlow<Int> = _hostWritePendingCountFlow.asStateFlow()

    private fun updatePendingCount() {
        _hostWritePendingCountFlow.value = hostWritePending.size
    }

    // ── Epic 4.1 (Task 4.1.1a): per-path write-through coalescing state ───────────────────────
    /** Paths whose flush cycle currently owns [scheduleHostWriteThrough]'s coalescing loop. */
    private val hostWriteInFlight = mutableSetOf<String>()

    /**
     * Paths that received a new [scheduleHostWriteThrough] call while already in
     * [hostWriteInFlight] — a set (not a scalar), since multiple paths can be independently
     * mid-flush concurrently, unlike [PlatformFileSystem]'s single marker-write scheduler.
     * `internal` so [HostDirectorySyncWriteThroughTest] can assert coalescing state directly.
     */
    internal val hostWriteDirtyDuringFlush = mutableSetOf<String>()

    /**
     * The most recently scheduled [HostWritePayload] for a repo-relative path — [flushHostWrite]
     * reads this fresh immediately after its own first suspension point (the proactive permission
     * check), so a coalesced update that lands while a flush attempt is already suspended there is
     * folded into that *same* in-flight write rather than requiring a redundant follow-up one; see
     * [scheduleHostWriteThrough]'s doc comment for the full "exactly one write of the latest
     * content" rationale (Story 4.1.1's coalescing acceptance criterion).
     */
    private val hostWriteLatestPayload = mutableMapOf<String, HostWritePayload>()

    // ── Epic 4.2 (Task 4.2.1a): freshness-check baseline ───────────────────────────────────────
    /**
     * Last-known host content hash per absolute OPFS path (same key convention as
     * [hostModTimes]/[hostFileSizes]), consulted by [flushHostWrite]'s pre-write freshness check
     * for [HostWritePayload.Text] payloads. An absent entry means "no baseline yet" — the check
     * always proceeds (never blocks) rather than treating absence as a conflict. `internal` so
     * tests can seed a baseline directly.
     */
    internal val hostContentHashes: MutableMap<String, Int> = mutableMapOf()

    // ── Epic 4.4 (Task 4.4.1a/b): write-through failure surfacing ─────────────────────────────
    /**
     * `true` while a write-through flush is failing for a reason that is *not* permission loss
     * and *not* a stale/moved handle (`NotFoundError`) — i.e. a genuinely transient failure (quota,
     * brief I/O blip) observed while a permission re-query still confirms `"granted"`. This is the
     * signal [Task 4.4.1c's `SyncDegraded`][dev.stapler.stelekit.ui.components.folderSyncBadgeContent]
     * distinguishes from ordinary in-flight syncing. Reset to `false` on the next successful
     * [flushHostWrite].
     */
    private val _hostWriteStuckFlow = MutableStateFlow(false)
    val hostWriteStuckFlow: StateFlow<Boolean> = _hostWriteStuckFlow.asStateFlow()

    /**
     * Task 4.4.1b: settable callback (mirrors [onHostConflict]'s settable-`var` pattern), invoked
     * once per failed [flushHostWrite] attempt regardless of how the failure was classified. Set
     * from `App.kt` to a small forwarding method on `GraphLoader` that reuses its existing
     * `writeErrors` channel — no new error surface. Defaults to a no-op so production code that
     * never wires a graph (or tests) still compiles and runs safely.
     */
    internal var onHostWriteFailed: (error: DomainError.FileSystemError.WriteFailed) -> Unit = {}

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

    // ── Epic 5.1 (Story 3.4.1 + 5.1.1/5.1.2): mtime/size reconciliation/poller baseline ────────
    /**
     * Originally forward-declared by Story 3.4.1 so [runHostReconciliation]'s mtime/size
     * pre-filter had somewhere to read/write a baseline; Epic 5.1's [pollHostDirectoryOnce] now
     * shares this same map, so reconciliation and the poller never drift against two independent
     * baselines. Keyed by absolute OPFS path (matching `hostVisitedPaths`/`cacheAccess.keysUnder`'s
     * path shape). Empty until the first reconciliation/poll populates an entry for a given path —
     * an absent entry is always treated as "no baseline, must read content," never as "unchanged."
     * [PlatformFileSystem.getLastModifiedTime] delegates directly to this map (Task 5.2.1a).
     */
    internal val hostModTimes: MutableMap<String, Long> = mutableMapOf()

    /** Sibling baseline to [hostModTimes] — see that field's doc comment. */
    internal val hostFileSizes: MutableMap<String, Long> = mutableMapOf()

    // ── Epic 5.1 (Story 5.1.2)/5.2 (Story 5.2.2): HostDirectoryPoller + observer state ─────────
    /** The currently-running timer loop, if any — see [startHostDirectoryPolling]/[stopHostDirectoryPolling]. */
    private var hostPollJob: Job? = null

    /**
     * Kept current by the dedicated tracking loop in [init]. `internal` (not `private`) — mirrors
     * this class's established "internal for direct test assertion/injection" convention (see
     * [hostWriteDirtyDuringFlush]/[hostContentHashes]) so [HostDirectoryPollerBenchmarkTest]'s
     * Story 5.5.2 virtual-time tests can force the hidden-tab case directly rather than driving a
     * real `document.visibilityState` transition. Read by [effectivePollIntervalMs].
     */
    internal var isTabHidden = false

    /**
     * Set `true` the instant [startHostChangeObserver]'s `FileSystemObserver` construction +
     * `observe()` (Task 5.2.2a) complete without throwing; left `true` for the life of the
     * connection (ADR-002's "fast path" framing — never re-demoted to primary on a quiet period).
     * Stays `false` when `fileSystemObserverSupported()` is `false` or construction/`observe()`
     * throws. `internal` for the same Story 5.5.2 testability reason as [isTabHidden]. Read by
     * [effectivePollIntervalMs].
     */
    internal var observerConfirmedActive = false

    /**
     * Retained so a future feature-detect-gated teardown has something to disconnect — not
     * required this phase (Task 5.2.2a's doc note), but avoids a dangling reference leak.
     */
    private var hostChangeObserver: JsAny? = null

    /**
     * The **base** poll interval (Task 5.1.2a) — used as-is only when the tab is visible *and*
     * `FileSystemObserver` is not confirmed active; the timer loop never reads this directly,
     * only [effectivePollIntervalMs]. Default confirmed by Epic 5.5's large-graph benchmark — see
     * `HostDirectoryPollerBenchmarkTest`'s class doc comment for the measured numbers this default
     * is based on.
     */
    private var hostPollIntervalMs: Long = 10_000L

    /**
     * Task 5.1.2b: the actual delay [startHostDirectoryPolling]'s timer loop sleeps for on its
     * next tick — `hostPollIntervalMs * backoffMultiplier`, where `backoffMultiplier` is `maxOf`
     * (never the product) of [HIDDEN_POLL_BACKOFF_MULTIPLIER] (applied when [isTabHidden]) and
     * [OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER] (applied when [observerConfirmedActive]) — the
     * two backoff reasons do not compound (Story 5.1.2's third acceptance criterion). Recomputed
     * fresh on every call, never cached, so a visibility/observer-health change takes effect
     * starting the very next tick, not retroactively. `internal` so
     * [HostDirectoryPollerBenchmarkTest] can assert the computed value directly.
     */
    internal fun effectivePollIntervalMs(): Long {
        val multiplier = maxOf(
            if (isTabHidden) HIDDEN_POLL_BACKOFF_MULTIPLIER else 1L,
            if (observerConfirmedActive) OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER else 1L,
        )
        return hostPollIntervalMs * multiplier
    }

    /**
     * Task 5.1.2a: starts the timer loop that keeps [hostModTimes]/[hostFileSizes]/cache current
     * independent of any caller (the async source satisfying `FileRegistry`/`GraphFileWatcher`'s
     * synchronous contract — see [pollHostDirectoryOnce]'s doc comment). Called from
     * [connectHostDirectory]/[reconnectHostDirectory]'s success paths. Cancels any
     * previously-running loop first, so calling this twice on the same instance never runs two
     * overlapping loops. `internal` so tests can start it directly against a pre-seeded
     * [hostDirHandle]/[hostGraphOpfsPath] (Story 5.5.2's virtual-time benchmarks).
     */
    internal fun startHostDirectoryPolling() {
        hostPollJob?.cancel()
        hostPollJob = scope.launch {
            while (isActive) {
                delay(effectivePollIntervalMs())
                val handle = hostDirHandle ?: continue
                val opfsPath = hostGraphOpfsPath ?: continue
                try {
                    // Epic 6.2 (Task 6.2.1b): leader-for-one-tick — a `null` result means another
                    // tab already holds this graph's poll lock for this tick; that is a silent
                    // skip (OPFS is cross-tab-shared, so this tab's own next tick, or its next
                    // `cache` read, sees the winner's result), never an error or user-visible event.
                    val acquired = WebLock.tryWithLock(FolderSyncLockNaming.pollLockNameFor(graphIdProvider())) {
                        pollHostDirectoryOnce(handle, opfsPath)
                    }
                    if (acquired == null) {
                        println("[SteleKit] HostDirectoryPoller tick skipped: poll lock held by another tab")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("[SteleKit] HostDirectoryPoller tick failed: ${e.message}")
                }
            }
        }
    }

    /** Stops the timer loop started by [startHostDirectoryPolling] — called when [hostDirHandle] is disconnected. */
    internal fun stopHostDirectoryPolling() {
        hostPollJob?.cancel()
        hostPollJob = null
    }

    /**
     * Task 5.2.2a: constructs a `FileSystemObserver` (per ADR-002) and starts observing [handle]
     * recursively when the browser supports it, so external changes are detected roughly one
     * event-loop tick after they happen instead of waiting for the next timer tick. Sets
     * [observerConfirmedActive] `true` only when both construction and `observe()` complete
     * without throwing; `false` when unsupported or either step fails. [observeHandle] itself
     * already catches and logs its own failures rather than throwing (`HostDirectoryInterop.kt`),
     * so in practice only `newFileSystemObserver`'s construction step can drive the `false` branch
     * here — a limitation of that existing interop function's signature, not a gap introduced by
     * this method.
     */
    private suspend fun startHostChangeObserver(handle: JsAny) {
        if (!fileSystemObserverSupported()) {
            observerConfirmedActive = false
            return
        }
        try {
            val observer = newFileSystemObserver { records -> scope.launch { handleObserverRecords(records) } }
            observeHandle(observer, handle, recursive = true)
            hostChangeObserver = observer
            observerConfirmedActive = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[SteleKit] FileSystemObserver setup failed: ${e.message}")
            observerConfirmedActive = false
        }
    }

    /**
     * Task 5.2.2b: dispatch entry point for [startHostChangeObserver]'s `FileSystemObserver`
     * callback. Iterates [records] (a plain JS array) via [jsRecordsLength]/[jsRecordsGet], reading
     * each record's [changeRecordType]/[changeRecordRelativePath] for observability — but for
     * every record type this batch contains (`"appeared"`/`"modified"`/`"disappeared"`/`"moved"`
     * *and* `"errored"`), the actual remediation is the same single full-tree
     * [pollHostDirectoryOnce] call below, run once per callback invocation rather than once per
     * record. A targeted single-file variant scoped to `relativePathComponents` would be a tighter
     * v1, but the full walk is an acceptable simplification here (per the plan) — its own
     * mtime/size pre-filter (Task 5.1.1b) already makes a redundant full-tree walk cheap for every
     * path except the one(s) that actually changed; see `HostDirectoryPollerBenchmarkTest` for the
     * measured per-tick cost this relies on. Never lets an exception escape uncaught — a broken
     * observer callback must not silently stop future change delivery.
     */
    private suspend fun handleObserverRecords(records: JsAny) {
        val handle = hostDirHandle ?: return
        val opfsPath = hostGraphOpfsPath ?: return
        try {
            val count = jsRecordsLength(records)
            for (i in 0 until count) {
                val record = jsRecordsGet(records, i)
                val type = changeRecordType(record)
                val relativePath = changeRecordRelativePath(record).joinToString("/")
                println("[SteleKit] FileSystemObserver record: type=$type path=$relativePath")
            }
            pollHostDirectoryOnce(handle, opfsPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("[SteleKit] handleObserverRecords failed: ${e.message}")
        }
    }

    init {
        // Task 5.1.2b: keeps isTabHidden current — a state-tracking sibling to
        // PlatformFileSystem.kt's existing one-shot hidden-flush loop and this class's own
        // Story 5.3.1 visible-poll loop below; all three independently await the same two interop
        // promises for different purposes (this codebase's "narrow, single-purpose loop per
        // concern" idiom, see Pattern Decisions "Poll cadence policy"). In environments with no
        // `document` (some test runners), the awaited promises simply never resolve, so this loop
        // harmlessly never advances past its first suspension point.
        scope.launch {
            while (isActive) {
                jsVisibilityHiddenPromise().await<JsAny?>()
                isTabHidden = true
                jsVisibilityVisiblePromise().await<JsAny?>()
                isTabHidden = false
            }
        }

        // Epic 5.3 (Task 5.3.1a): visibility-regain immediate recheck — independent of the timer
        // loop's own steady-state cadence (Story 5.1.2's effectivePollIntervalMs/isTabHidden
        // backoff) and independent of PlatformFileSystem's unrelated hidden-flush loop (git
        // dirty-marker flush on tab hide, PlatformFileSystem.kt:99-108).
        scope.launch {
            while (isActive) {
                jsVisibilityVisiblePromise().await<JsAny?>()
                val handle = hostDirHandle ?: continue
                val opfsPath = hostGraphOpfsPath ?: continue
                try {
                    // Epic 6.2 (Task 6.2.1b): same leader-for-one-tick lock as the timer loop above
                    // — a `null` result (another tab already owns this tick) is a silent skip.
                    val acquired = WebLock.tryWithLock(FolderSyncLockNaming.pollLockNameFor(graphIdProvider())) {
                        pollHostDirectoryOnce(handle, opfsPath)
                    }
                    if (acquired == null) {
                        println("[SteleKit] visibility-regain poll skipped: poll lock held by another tab")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("[SteleKit] visibility-regain poll failed: ${e.message}")
                }
            }
        }
    }

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
     * (`_handle`) was not, until Epic 2.2, part of the persisted envelope — only its `graphId`/
     * `dirName`/`storedAtMillis` metadata was — a `FileSystemDirectoryHandle` is a
     * structured-clone-only opaque value with no meaningful JSON shape (see [HostHandleEnvelope]'s
     * doc comment). Epic 2.2 now also persists `_handle` itself, **alongside** (not replacing) the
     * JSON envelope — under a distinct key ([handleObjectKey]), so [HostDirectorySyncHandleRetentionTest]'s
     * already-landed contract (`idbGetHandle(db, graphId)` decodes as a [HostHandleEnvelope] JSON
     * string) is untouched. `FileSystemDirectoryHandle` is structured-clone-safe for real handles
     * (browsers implement this specially, unlike a plain function-bearing JS object); see
     * [lookupPersistedHandle]'s doc comment for why that matters for testability.
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
            idbPutHandle(db, handleObjectKey(graphId), _handle)
        } catch (e: Throwable) {
            println("[SteleKit] persistHostHandle failed for graphId=$graphId: ${e.message}")
        }
    }

    // ── Epic 2.2 (Story 2.2.1/2.2.2): shared IndexedDB handle lookup ───────────────────────────

    /** Distinct IndexedDB key (same `handles` object store) for [graphId]'s real persisted handle
     * object — see [persistHostHandle]'s doc comment for why this is a separate key rather than
     * overwriting the envelope stored at the plain [graphId] key. */
    private fun handleObjectKey(graphId: String): String = "$graphId::handle"

    /**
     * Real (production) implementation of [lookupPersistedHandle] — reads the envelope + real
     * handle object [persistHostHandle] wrote, both keyed off [graphId]. Returns `null` if either
     * half is missing (nothing was ever persisted, or a previous version of this app only wrote
     * the envelope half) or the envelope fails to decode — "nothing to silently resume," never a
     * thrown exception.
     */
    private suspend fun defaultLookupPersistedHandle(graphId: String): Pair<JsAny, String>? = try {
        val db = idbOpenHandleDb()
        val envelopeRaw = idbGetHandle(db, graphId)
        val handle = idbGetHandle(db, handleObjectKey(graphId))
        if (envelopeRaw == null || handle == null) {
            null
        } else {
            val envelope = gitApiJson.decodeFromString<HostHandleEnvelope>(jsAnyToUtf8String(envelopeRaw))
            handle to "$HOME_DIR/${envelope.dirName}"
        }
    } catch (e: Throwable) {
        println("[SteleKit] lookupPersistedHandle failed for graphId=$graphId: ${e.message}")
        null
    }

    /**
     * Task 2.2.1a/2.2.2a (testability seam): overridable in tests, mirroring [onHostConflict]'s
     * settable-`var` pattern. Defaults to [defaultLookupPersistedHandle] (real IndexedDB). Real
     * `FileSystemDirectoryHandle` instances are structured-clone-safe (browsers implement this
     * specially for `FileSystemHandle`), but the lightweight fake handle objects this project's
     * tests use elsewhere (e.g. `HostDirectoryInteropTest.kt`'s `fakeHandleWithPermissionResult`,
     * which carries `queryPermission`/`requestPermission` function-valued own properties) fail
     * IndexedDB's structured clone algorithm outright — so `HostDirectorySyncSessionResumeTest.kt`
     * overrides this field directly with a fake in-memory lookup instead of routing such a handle
     * through a real IndexedDB round trip.
     */
    internal var lookupPersistedHandle: suspend (graphId: String) -> Pair<JsAny, String>? =
        { graphId -> defaultLookupPersistedHandle(graphId) }

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
     *
     * Epic 2.2/2.4: also mirrors the outcome into [hostAccessStateFlow] (so `FolderSyncStatusBadge`
     * reflects a manual connect the same way it reflects [reconnectHostDirectory]/
     * [requestHostDirectoryAccess]) and, on success only, fires [requestStoragePersistence] as a
     * best-effort, fire-and-forget call — logged, never awaited inline, never blocking this
     * function's return.
     */
    suspend fun connectHostDirectory(existingOpfsPath: String): HostAccessState {
        val result = try {
            val dirHandle = showDirectoryPicker()
            runHostReconciliation(dirHandle, existingOpfsPath)
            hostDirHandle = dirHandle
            hostGraphOpfsPath = existingOpfsPath
            val dirName = existingOpfsPath.substringAfterLast("/")
            persistHostHandle(graphIdProvider(), dirName, dirHandle)
            scope.launch {
                val granted = requestStoragePersistence()
                println("[SteleKit] storage.persist(): granted=$granted")
            }
            // Epic 5.1/5.2: start the poller + (browser-permitting) the FileSystemObserver fast
            // path now that the handle is retained — mirrors reconnectHostDirectory's wiring below.
            startHostDirectoryPolling()
            startHostChangeObserver(dirHandle)
            HostAccessState.Granted
        } catch (e: Throwable) {
            println("[SteleKit] connectHostDirectory failed for '$existingOpfsPath': ${e.message}")
            HostAccessState.NotApplicable
        }
        _hostAccessStateFlow.value = result
        return result
    }

    // ── Epic 2.2 (Story 2.2.1): reconnectHostDirectory — silent resume, always reconciling ────
    /**
     * Task 2.2.1a: session-resume entry point, called once from `Main.kt`'s startup sequence right
     * after `PlatformFileSystem.preload`. Looks up [graphId]'s persisted handle via
     * [lookupPersistedHandle]; if nothing was ever persisted, resolves to
     * [HostAccessState.NotApplicable] without touching the browser's permission APIs at all
     * (matches today's no-host-directory behavior exactly). Otherwise queries (never *requests* —
     * this runs with no user gesture) the browser's current permission for the handle:
     * - `"granted"`: sets [hostDirHandle]/[hostGraphOpfsPath], **launches**
     *   [runHostReconciliation] non-blocking (`scope.launch` — Story 2.2.1's Blocker 3/pre-mortem
     *   P1 #1 remediation, see [runHostReconciliation]'s own doc comment), fires
     *   [requestStoragePersistence] fire-and-forget (Epic 2.4), and resolves to
     *   [HostAccessState.Granted] immediately, **without** waiting on either launched coroutine —
     *   zero added startup latency, zero UI interruption.
     * - `"prompt"` / `"denied"`: resolves to [HostAccessState.PromptNeeded]/[HostAccessState.Denied]
     *   without setting [hostDirHandle] and without calling [runHostReconciliation] — there is no
     *   handle attached yet, so nothing to reconcile against.
     *
     * Every branch mirrors its result into [hostAccessStateFlow] before returning.
     */
    suspend fun reconnectHostDirectory(graphId: String): HostAccessState {
        val found = lookupPersistedHandle(graphId)
        if (found == null) {
            _hostAccessStateFlow.value = HostAccessState.NotApplicable
            return HostAccessState.NotApplicable
        }
        val (handle, opfsPath) = found
        val result = when (queryHandlePermission(handle)) {
            "granted" -> {
                hostDirHandle = handle
                hostGraphOpfsPath = opfsPath
                scope.launch { runHostReconciliation(handle, opfsPath) }
                scope.launch {
                    val granted = requestStoragePersistence()
                    println("[SteleKit] storage.persist(): granted=$granted")
                }
                // Epic 5.1/5.2: same wiring as connectHostDirectory — launched non-blocking
                // (startHostChangeObserver is suspend) so session-resume startup latency/UI
                // interruption stays zero, matching this branch's existing "never wait on a
                // launched coroutine" doc comment above.
                startHostDirectoryPolling()
                scope.launch { startHostChangeObserver(handle) }
                HostAccessState.Granted
            }
            "prompt" -> HostAccessState.PromptNeeded
            else -> HostAccessState.Denied
        }
        _hostAccessStateFlow.value = result
        return result
    }

    // ── Epic 2.2 (Story 2.2.2): requestHostDirectoryAccess — one-click resume path ────────────
    /**
     * Task 2.2.2a: one-click resume, called from a real UI click handler only —
     * `requestPermission()` requires transient user activation (`research/pitfalls.md` §1.4); a
     * call made outside a click/tap event handler's synchronous call stack silently no-ops or
     * rejects depending on the browser. Re-fetches [graphId]'s persisted handle via
     * [lookupPersistedHandle] (independent of whatever [reconnectHostDirectory] cached earlier this
     * session), then calls [requestHandlePermission] (the *requesting*, prompt-showing variant —
     * distinct from [reconnectHostDirectory]'s silent `queryHandlePermission`):
     * - `"granted"`: sets [hostDirHandle]/[hostGraphOpfsPath], resolves to [HostAccessState.Granted].
     * - anything else (`"denied"`, or a thrown/caught interop failure, which
     *   [requestHandlePermission] itself already normalizes to `"denied"`): resolves to
     *   [HostAccessState.Denied] — **no retry loop**; the user must click again to re-attempt.
     *
     * Does not itself launch [runHostReconciliation] or [requestStoragePersistence] — this task's
     * acceptance criteria (Story 2.2.2) scope this method to the permission handshake only; Epic
     * 2.4's `storage.persist()` wiring is scoped to [reconnectHostDirectory]/[connectHostDirectory]
     * (Task 2.4.1a) alone.
     */
    suspend fun requestHostDirectoryAccess(graphId: String): HostAccessState {
        val found = lookupPersistedHandle(graphId)
        if (found == null) {
            _hostAccessStateFlow.value = HostAccessState.NotApplicable
            return HostAccessState.NotApplicable
        }
        val (handle, opfsPath) = found
        val result = when (requestHandlePermission(handle)) {
            "granted" -> {
                hostDirHandle = handle
                hostGraphOpfsPath = opfsPath
                HostAccessState.Granted
            }
            else -> HostAccessState.Denied
        }
        _hostAccessStateFlow.value = result
        return result
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
     * **Story 3.4.1 (mtime/size pre-filter)**: before reading a visited file's content, the walk
     * first compares `fileLastModified`/`fileSize` (from the file's already-cheap `getFile()`
     * metadata — [getOpfsFile]) against [hostModTimes]/[hostFileSizes]'s baseline for that path.
     * A match short-circuits straight to [ReconciliationOutcome.Identical] — no `.text()`/
     * `.arrayBuffer()` content read, no [classifyReconciliation]/[classifyReconciliationBytes]
     * call. No baseline entry (first-ever reconciliation for a path) is always treated as "must
     * read," never as "unchanged." [hostModTimes]/[hostFileSizes] are updated for every visited
     * path regardless of which branch ran, so the baseline stays current for the next pass (and
     * for the future poller, Epic 5.1, which shares these same fields).
     *
     * **Story 3.4.2 (calling convention)**: this function is a plain `suspend fun` with no
     * assumption that its caller awaits it synchronously. It **must be launched via
     * `scope.launch`** (non-blocking) when called from `reconnectHostDirectory`'s silent-resume
     * path — session-resume must never block app startup on a full reconciliation walk.
     * [connectHostDirectory]'s one-time opt-in flow remains awaited/blocking, since its progress
     * UI (Surface 8) is designed for exactly that wait.
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

        // Task 3.4.1a: a path's cheap metadata matches a known-good baseline iff both mtime and
        // size are present and unchanged — mirrors FileRegistry.detectChanges's mtime-first idiom.
        fun matchesBaseline(path: String, mtime: Long, size: Long): Boolean {
            val knownMtime = hostModTimes[path]
            val knownSize = hostFileSizes[path]
            return knownMtime != null && knownSize != null && knownMtime == mtime && knownSize == size
        }

        suspend fun walk(handle: JsAny, currentPath: String) {
            for (entry in listOpfsEntries(handle)) {
                val name = getEntryName(entry)
                val path = "$currentPath/$name"
                when {
                    isFileEntry(entry) && path.endsWith(".md.stek") -> {
                        hostVisitedPaths += path
                        val file = getOpfsFile(entry)
                        val mtime = fileLastModified(file)
                        val size = fileSize(file)
                        if (matchesBaseline(path, mtime, size)) {
                            identicalCount++
                        } else {
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
                        hostModTimes[path] = mtime
                        hostFileSizes[path] = size
                    }
                    isFileEntry(entry) -> {
                        hostVisitedPaths += path
                        val file = getOpfsFile(entry)
                        val mtime = fileLastModified(file)
                        val size = fileSize(file)
                        if (matchesBaseline(path, mtime, size)) {
                            identicalCount++
                        } else {
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
                        hostModTimes[path] = mtime
                        hostFileSizes[path] = size
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
                updatePendingCount()
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

    // ── Epic 5.1 (Story 5.1.1/5.1.2): HostDirectoryPoller — the async source satisfying ────────
    // FileRegistry/GraphFileWatcher's existing synchronous getLastModifiedTime/listFilesWithModTimes
    // contract. This is the single load-bearing architectural decision of Phase 5
    // (research/architecture.md §1.1/§4).
    /**
     * Walks [dirHandle] recursively (mirroring [runHostReconciliation]'s traversal/pre-filter
     * shape, Story 3.4.1) and refreshes [hostModTimes]/[hostFileSizes] — and, for changed files,
     * [cacheAccess]'s content — for every visited path. Unlike [runHostReconciliation], this
     * function does not classify against [cacheAccess] or dispatch
     * [ReconciliationOutcome]/[onHostConflict]/[hostWritePending]; its only job is keeping the
     * synchronous view [PlatformFileSystem.getLastModifiedTime]/`listFilesWithModTimes` (via
     * [listFilesWithModTimes] below) and `readFile` (via [cacheAccess]) already consume current,
     * so `FileRegistry.detectChanges`'s existing, unmodified polling logic sees real data.
     *
     * Shared by three independent callers: the timer loop ([startHostDirectoryPolling]), the
     * `FileSystemObserver` fast path ([handleObserverRecords]), and the visibility-regain
     * immediate recheck (Epic 5.3, this class's [init] block) — none of them assume the others
     * ran, so a redundant back-to-back call is always safe (idempotent given an unchanged host
     * tree, per the mtime/size pre-filter below).
     *
     * **Pre-filter (mirrors Story 3.4.1)**: per visited file, cheap `File.lastModified`/`size`
     * metadata (already-fetched via [getOpfsFile] — no extra call) is compared against
     * [hostModTimes]/[hostFileSizes]'s baseline for that path. A match short-circuits with no
     * content read at all. A mismatch reads content and branches on `path.endsWith(".md.stek")`
     * exactly as [runHostReconciliation]'s walk does (Task 5.1.1b, adversarial-review.md Blocker
     * 4): bytes via `arrayBuffer()` + [cacheAccess.setBytes] for paranoid-mode paths, text via
     * `.text()` + [cacheAccess.set] otherwise — encrypted content is never decoded as UTF-8.
     * [hostModTimes]/[hostFileSizes] are always refreshed for every visited path regardless of
     * which branch ran, so the next tick's pre-filter stays accurate.
     *
     * **Own-write suppression (Task 5.1.1c)**: a path currently in [hostWriteInFlight] (Epic
     * 4.1 — a concurrent [flushHostWrite] already owns that path) is skipped entirely this tick —
     * neither its baseline nor its cache entry is touched — so the poller never races a
     * concurrent host write and misclassifies the app's own in-progress write as an external
     * change.
     */
    suspend fun pollHostDirectoryOnce(dirHandle: JsAny, opfsPath: String) {
        suspend fun visit(entry: JsAny, path: String) {
            val repoRelative = path.removePrefix("$opfsPath/")
            if (repoRelative in hostWriteInFlight) return

            val file = getOpfsFile(entry)
            val mtime = fileLastModified(file)
            val size = fileSize(file)
            val unchanged = hostModTimes[path] == mtime && hostFileSizes[path] == size
            if (!unchanged) {
                if (path.endsWith(".md.stek")) {
                    val bytes = readOpfsFileAsBytes(entry)
                    if (bytes != null) cacheAccess.setBytes(path, bytes)
                } else {
                    val content = readOpfsFile(entry)
                    if (content != null) cacheAccess.set(path, content)
                }
            }
            hostModTimes[path] = mtime
            hostFileSizes[path] = size
        }

        suspend fun walk(handle: JsAny, currentPath: String) {
            for (entry in listOpfsEntries(handle)) {
                val name = getEntryName(entry)
                val path = "$currentPath/$name"
                when {
                    isFileEntry(entry) -> visit(entry, path)
                    isDirectoryEntry(entry) -> walk(entry, path)
                }
            }
        }

        walk(dirHandle, opfsPath)
    }

    /**
     * Task 5.2.1b: single-pass, non-per-file-call implementation backing
     * [PlatformFileSystem.listFilesWithModTimes] — a plain [hostModTimes] map iteration rather
     * than N synchronous [PlatformFileSystem.getLastModifiedTime] calls (mirrors why JVM already
     * overrides this same [FileSystem] method, `FileSystem.kt:28-29`'s KDoc). Returns direct
     * (non-nested) children of [path] only, name paired with mod time. Returns `emptyList()` when
     * no host directory is connected ([hostDirHandle] `null`) — [PlatformFileSystem]'s override
     * falls through to the interface default in that case.
     */
    internal fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
        if (hostDirHandle == null) return emptyList()
        return hostModTimes.entries
            .filter { it.key.startsWith("$path/") && !it.key.removePrefix("$path/").contains('/') }
            .map { it.key.removePrefix("$path/") to it.value }
    }

    // ── Epic 4.1-4.4: write-through queue, coalescing flush scheduler, failure surfacing ──────

    /** Repo-relative key derivation matching [runHostReconciliation]'s existing convention. */
    private fun repoRelativePath(path: String): String {
        val opfsPath = hostGraphOpfsPath
        return if (opfsPath != null && path.startsWith("$opfsPath/")) path.removePrefix("$opfsPath/") else path
    }

    private fun dirtyOpFor(payload: HostWritePayload): DirtyOp =
        if (payload is HostWritePayload.Delete) DirtyOp.DELETE else DirtyOp.WRITE

    /**
     * Task 4.2.3a: factored out of both [flushHostWrite]'s proactive pre-write check and its
     * reactive post-failure re-query (Task 4.4.1a) so the two call sites cannot drift out of sync.
     */
    private fun mapPermissionResultToAccessState(result: String): HostAccessState = when (result) {
        "prompt" -> HostAccessState.PromptNeeded
        else -> HostAccessState.Denied
    }

    /**
     * Task 4.1.1b (Story 4.1.1) / Epic 1.7 (Task 1.7.1b): coalescing write-through scheduler,
     * mirroring [PlatformFileSystem]'s existing `scheduleMarkerWrite` "at most one flush in
     * flight, trailing writes coalesce" idiom, generalized to per-path via [hostWriteInFlight]/
     * [hostWriteDirtyDuringFlush] instead of a single pair of scalars.
     *
     * [path] is the *absolute* OPFS path — the same parameter `writeFile`/`writeFileBytes`/
     * `deleteFile` already receive and the same key convention [CacheAccess.opfsWriteDeferredFor]
     * uses — re-keyed internally to repo-relative for [hostWritePending]/[hostWriteInFlight],
     * matching [runHostReconciliation]'s convention.
     *
     * Not a `suspend fun`: `writeFile`/`writeFileBytes`/`deleteFile` are synchronous
     * `FileSystem`-interface methods (Task 4.3.1a/b/c's "one-line delegation" call sites), so all
     * async work here — the Epic 1.7 await, the permission check, the actual write — runs inside
     * an internally-launched coroutine.
     *
     * **Epic 1.7 scope expansion**: awaits [path]'s in-flight OPFS-persisting write (if any) via
     * [CacheAccess.opfsWriteDeferredFor] *before* this edit is added to [hostWritePending] —
     * closes the crash window where a host push could otherwise race ahead of the edit actually
     * landing in OPFS. [hostWritePending] is deliberately populated only after this await
     * resolves (see `HostDirectorySyncReconciliationTest.kt`'s
     * `scheduleHostWriteThrough_should_NotContainPathUntilOpfsWriteDeferredResolves_...` mechanism
     * regression test).
     *
     * **Coalescing**: [hostWriteLatestPayload] always holds the most recent payload for a path. A
     * call arriving while a flush cycle already owns that path ([hostWriteInFlight]) only updates
     * the payload and marks [hostWriteDirtyDuringFlush] — it never launches a second concurrent
     * flush cycle. [flushHostWrite] itself re-reads the latest payload (and consumes the dirty
     * marker) immediately after its own first suspension point (the proactive permission check),
     * so a coalesced update delivered while that very attempt was suspended there is folded into
     * the *same* write instead of requiring a redundant follow-up one — this is what makes Story
     * 4.1.1's "exactly one write of the latest content" guarantee hold even for a burst that
     * lands before the first flush's actual host write begins.
     */
    fun scheduleHostWriteThrough(path: String, payload: HostWritePayload) {
        val repoRelative = repoRelativePath(path)
        val opfsWriteDeferred = cacheAccess.opfsWriteDeferredFor(path)
        scope.launch {
            // Epic 1.7: never let a host push race ahead of the edit actually landing in OPFS.
            opfsWriteDeferred?.await()

            hostWriteLatestPayload[repoRelative] = payload
            hostWritePending[repoRelative] = DirtyEntry(dirtyOpFor(payload), Clock.System.now().toEpochMilliseconds())
            updatePendingCount()

            if (repoRelative in hostWriteInFlight) {
                hostWriteDirtyDuringFlush += repoRelative
                return@launch
            }
            hostWriteInFlight += repoRelative
            try {
                do {
                    flushHostWrite(repoRelative)
                } while (repoRelative in hostWriteDirtyDuringFlush)
            } finally {
                hostWriteInFlight -= repoRelative
            }
        }
    }

    /**
     * Task 4.2.1a/b/c, 4.2.2a, 4.2.3a, 4.4.1a: performs one host-directory write attempt for
     * [repoRelative] against [hostWriteLatestPayload]'s value, read fresh right after this
     * function's own first suspension point — see [scheduleHostWriteThrough]'s doc comment.
     *
     * Epic 6.1 (Task 6.1.1a): the write critical section — freshness check through the successful
     * dequeue/hash-update — runs inside [WebLock.withLock] keyed by
     * [FolderSyncLockNaming.writeLockNameFor], so two tabs' `flushHostWrite` calls for the same
     * path never interleave their `createWritable()`/`write()`/`close()` sequence
     * (`research/pitfalls.md` §1.5's `'siloed'`-mode last-write-wins race). The proactive
     * permission pre-check (Task 4.2.3a) deliberately stays *outside* the lock — it touches no
     * shared write state, and per `GitWriteLock`'s documented scope discipline
     * (`GitWriteLock.kt:47-55`) a lock should cover only the actual critical section, never be
     * widened to include independent preflight checks. Uses [graphIdProvider] (the live value),
     * never a captured/stale `graphId`, matching every other lock-name derivation in this class.
     */
    private suspend fun flushHostWrite(repoRelative: String) {
        val handle = hostDirHandle ?: return

        // Task 4.2.3a: proactive permission check — *before* any write attempt is made, not only
        // reactively after one has already failed (research/pitfalls.md §1.1).
        val access = queryHandlePermission(handle)
        if (access != "granted") {
            _hostAccessStateFlow.value = mapPermissionResultToAccessState(access)
            onHostWriteFailed(
                DomainError.FileSystemError.WriteFailed(repoRelative, "Host directory permission is '$access'"),
            )
            return
        }

        WebLock.withLock(FolderSyncLockNaming.writeLockNameFor(graphIdProvider(), repoRelative)) {
            val payload = hostWriteLatestPayload[repoRelative] ?: return@withLock
            hostWriteDirtyDuringFlush -= repoRelative

            val opfsPath = hostGraphOpfsPath
            val fullPath = if (opfsPath != null) "$opfsPath/$repoRelative" else repoRelative

            try {
                when (payload) {
                    is HostWritePayload.Text -> {
                        val (dir, fileName) = resolveHostEntry(handle, repoRelative, create = true)
                        val fileHandle = getFileHandle(dir, fileName, true)
                        // Task 4.2.1a: pre-write freshness check — text payloads only. Re-checked
                        // fresh under the lock, so a tab that just lost the race for this path
                        // sees the winner's just-written content here, not a stale pre-lock read.
                        val currentHostContent = readOpfsFile(fileHandle)
                        val knownHash = hostContentHashes[fullPath]
                        if (currentHostContent != null && knownHash != null && currentHostContent.hashCode() != knownHash) {
                            onHostConflict(repoRelative, currentHostContent)
                            return@withLock
                        }
                        val writable: JsAny = fileHandleCreateWritable(fileHandle).await()
                        writableWrite(writable, payload.content).await<JsAny>()
                        writableClose(writable).await<JsAny>()
                        hostContentHashes[fullPath] = payload.content.hashCode()
                    }
                    is HostWritePayload.Bytes -> {
                        // Task 4.2.2a: paranoid-mode — no hash guard. Bytes never round-trip through
                        // the String-typed onHostConflict (adversarial-review.md Blocker 4's rationale
                        // — see runHostReconciliation's `.md.stek` branch), and FileRegistry.kt's
                        // documented "modtime change alone is sufficient signal" rule for encrypted
                        // files means no live bytes-aware conflict signal exists yet either — this
                        // branch deliberately skips a freshness check entirely rather than perform a
                        // read whose mismatch has nowhere safe to be routed.
                        val (dir, fileName) = resolveHostEntry(handle, repoRelative, create = true)
                        val fileHandle = getFileHandle(dir, fileName, true)
                        val writable: JsAny = fileHandleCreateWritable(fileHandle).await()
                        writableWriteBuffer(writable, payload.data.toJsArrayBuffer()).await<JsAny>()
                        writableClose(writable).await<JsAny>()
                    }
                    is HostWritePayload.Delete -> {
                        val (dir, fileName) = resolveHostEntry(handle, repoRelative, create = false)
                        dirRemoveEntry(dir, fileName).await<JsAny>()
                        hostContentHashes.remove(fullPath)
                    }
                }

                // Task 4.2.1c: dequeue + bookkeeping on success.
                hostWritePending.remove(repoRelative)
                updatePendingCount()
                hostModTimes[fullPath] = Clock.System.now().toEpochMilliseconds()
                _hostWriteStuckFlow.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleFlushFailure(repoRelative, handle, e)
            }
        }
    }

    /**
     * Task 3.2.1a's traversal shape, rooted at [rootHandle] (the host directory handle) rather
     * than `getOpfsRoot()` — resolves [repoRelative]'s parent directory (creating intermediate
     * directories when [create] is true, mirroring [opfsWriteFile]'s own segment walk) and returns
     * it alongside the final path segment (the file/entry name).
     */
    private suspend fun resolveHostEntry(rootHandle: JsAny, repoRelative: String, create: Boolean): Pair<JsAny, String> {
        val parts = repoRelative.split("/")
        var dir: JsAny = rootHandle
        for (part in parts.dropLast(1)) {
            dir = getDirectoryHandle(dir, part, create)
        }
        return dir to parts.last()
    }

    /**
     * Task 4.4.1a: classifies a thrown [flushHostWrite] failure. `NotFoundError`-shaped messages
     * (the stored handle no longer resolves — directory moved/deleted outside the browser)
     * transition to [HostAccessState.Disconnected]. Every other failure (`NotAllowedError`-shaped,
     * or defensively any other error — permission revocation is not guaranteed to surface a
     * distinctly-named error per research/pitfalls.md §1.1) re-queries [queryHandlePermission]: a
     * `"prompt"`/`"denied"` result means the grant really is gone, mapped via
     * [mapPermissionResultToAccessState]; a re-query that still returns `"granted"` means this was
     * a genuinely transient failure (quota, brief I/O blip) — [hostAccessStateFlow] is left
     * untouched and [_hostWriteStuckFlow] is set instead (Task 4.4.1c's `SyncDegraded` signal). In
     * every branch, [repoRelative] stays queued in [hostWritePending] (never dequeued here) and
     * [onHostWriteFailed] fires exactly once. Never lets the exception escape uncaught.
     */
    private suspend fun handleFlushFailure(repoRelative: String, handle: JsAny, e: Throwable) {
        val message = e.message ?: "unknown"
        if (message.contains("NotFoundError", ignoreCase = true)) {
            _hostAccessStateFlow.value = HostAccessState.Disconnected(message)
            // Epic 5.1/5.2: the stored handle no longer resolves — stop polling/treating the
            // observer as active for it. hostDirHandle itself is deliberately left set (matches
            // this method's existing doc comment: no field-clearing "disconnect" flow exists yet),
            // but effectivePollIntervalMs()/pollHostDirectoryOnce should not keep firing against a
            // handle that's known to no longer resolve.
            stopHostDirectoryPolling()
            observerConfirmedActive = false
        } else {
            val requery = queryHandlePermission(handle)
            if (requery == "granted") {
                _hostWriteStuckFlow.value = true
            } else {
                _hostAccessStateFlow.value = mapPermissionResultToAccessState(requery)
            }
        }
        onHostWriteFailed(DomainError.FileSystemError.WriteFailed(repoRelative, message))
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
