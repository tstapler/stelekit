package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import dev.stapler.stelekit.git.model.DirtySetMarker
import dev.stapler.stelekit.git.model.PendingCommit
import dev.stapler.stelekit.git.model.gitApiJson
import dev.stapler.stelekit.sync.WasmSectionSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.time.Clock

actual class PlatformFileSystem actual constructor() : FileSystem {
    private val homeDir = "/stelekit"
    private val cache = mutableMapOf<String, String>()
    private val bytesCache = mutableMapOf<String, ByteArray>()
    private val blobUrlCache = mutableMapOf<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Epic 2.1/2.2: dirty-file tracking + .stele-dirty-set.json checkpoint ──────────────────
    private val dirtySet = mutableMapOf<String, DirtyEntry>()
    private val _dirtyFileCountFlow = MutableStateFlow(0)
    val dirtyFileCountFlow: StateFlow<Int> = _dirtyFileCountFlow.asStateFlow()
    private var graphId: String = "default"
    private var baseSha: String = ""
    private var pendingCommit: PendingCommit = PendingCommit.None

    // Immediate, coalesce-while-busy marker-write scheduler (NOT a fixed-delay debounce — see
    // Task 2.1.2b's redesign note in project_plans/web-git-writeback/implementation/plan.md).
    // At most one .stele-dirty-set.json write is ever in flight at a time; a burst of
    // recordDirty()/scheduleMarkerWrite() calls while one is in flight coalesces into exactly
    // one trailing write of the latest state, launched the instant the in-flight write completes.
    private var markerWriteInFlight = false
    private var markerWriteDirty = false

    // ── Epic 1.7 (Story 1.7.1): per-path in-flight OPFS-write tracking ────────────────────────
    // Populated when writeFile/writeFileBytes launches its OPFS-persisting write, self-cleans on
    // completion (success or failure). HostDirectorySync.scheduleHostWriteThrough (Phase 4) will
    // await a path's entry here before enqueueing it for host write-through, closing the crash
    // window where a host push could otherwise race ahead of the edit actually landing in OPFS.
    private val opfsWriteInFlight = mutableMapOf<String, Deferred<Unit>>()

    /**
     * Task 1.7.1a: accessor for [path]'s currently in-flight OPFS-persisting write, if any.
     * Tested directly (Story 1.7.3) as well as exposed to [hostDirectorySync] via
     * [HostDirectorySync.CacheAccess.opfsWriteDeferredFor].
     */
    fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = opfsWriteInFlight[path]

    // ── Epic 1.6: HostDirectorySync composition (architecture-review.md Blocker 1 remediation) ─
    // Every Phase 2-7 host-directory-sync field/method lives on hostDirectorySync, never here.
    // Exposed non-privately so Main.kt/UI code can call its non-FileSystem-interface entry points
    // (reconnectHostDirectory, requestHostDirectoryAccess, connectHostDirectory, its StateFlows)
    // directly, without PlatformFileSystem needing to re-expose every one as a passthrough.
    val hostDirectorySync: HostDirectorySync = HostDirectorySync(
        graphIdProvider = { graphId },
        cacheAccess = object : HostDirectorySync.CacheAccess {
            override fun get(path: String) = cache[path]
            override fun set(path: String, content: String) {
                cache[path] = content
            }
            override fun remove(path: String) {
                cache.remove(path)
            }
            override fun getBytes(path: String) = bytesCache[path]
            override fun setBytes(path: String, data: ByteArray) {
                bytesCache[path] = data
            }
            override fun removeBytes(path: String) {
                bytesCache.remove(path)
            }
            override fun keysUnder(opfsPath: String) =
                (cache.keys + bytesCache.keys).filter { it.startsWith("$opfsPath/") }.toSet()
            override fun writeOpfsMirror(path: String, content: String) {
                scope.launch { opfsWriteFile(path, content) }
            }
            override fun writeOpfsMirrorBytes(path: String, data: ByteArray) {
                scope.launch { opfsWriteFileBytes(path, data.toJsArrayBuffer()) }
            }
            override fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = opfsWriteInFlight[path]
        },
        scope = scope,
    )

    init {
        // Belt-and-suspenders flush: fire the same scheduler when the tab is hidden/closed, even
        // though the redesign above already bounds the crash-loss window to a single in-flight
        // OPFS write's duration in the common case.
        scope.launch {
            while (true) {
                try {
                    jsVisibilityHiddenPromise().await<JsAny?>()
                } catch (e: Throwable) {
                    break
                }
                scheduleMarkerWrite()
            }
        }

        // Epic 1.7 (Task 1.7.2b): best-effort teardown diagnostic — logs any OPFS writes still in
        // flight at pagehide/beforeunload. This does NOT attempt to force or await completion of
        // any in-flight write and closes no crash window beyond what Story 1.7.1's
        // await-before-enqueue fix already closes; it exists purely so a real-world crash-window
        // occurrence is observable in logs rather than silent. Applies platform-wide, not gated on
        // a host directory being connected.
        scope.launch {
            while (true) {
                try {
                    jsPageHidePromise().await<JsAny?>()
                } catch (e: Throwable) {
                    break
                }
                val inFlightCount = opfsWriteInFlight.size
                if (inFlightCount > 0) {
                    println("[SteleKit] pagehide: $inFlightCount OPFS writes still in flight")
                }
            }
        }
    }

    suspend fun preload(graphPath: String) {
        graphId = graphPath.removePrefix("$homeDir/").substringBefore("/").ifEmpty { graphId }
        try {
            loadOpfsDirectory(graphPath)
        } catch (e: Throwable) {
            println("[SteleKit] OPFS preload failed, starting with empty graph: ${e.message}")
        }
        restoreDirtySetMarker()
    }

    private fun markerPath(): String = "$homeDir/$graphId/.stele-dirty-set.json"

    /** Crash-safe: absent or malformed marker leaves the dirty set empty, never throws. */
    private suspend fun restoreDirtySetMarker() {
        try {
            val raw = opfsReadFileAtPath(markerPath()) ?: return
            val marker = gitApiJson.decodeFromString<DirtySetMarker>(raw)
            dirtySet.clear()
            dirtySet.putAll(marker.dirtyFiles)
            _dirtyFileCountFlow.value = dirtySet.size
            baseSha = marker.baseSha
            pendingCommit = marker.pendingCommit
        } catch (e: Throwable) {
            println("[SteleKit] dirty-set marker restore failed, starting empty: ${e.message}")
        }
    }

    /** Repo-relative path derivation matches [readFileSuspend]'s existing convention. */
    private fun recordDirty(path: String, op: DirtyOp) {
        if (path.startsWith(DOWNLOAD_PREFIX)) return
        val repoRelative = path.removePrefix("/stelekit/").substringAfter("/")
        if (repoRelative.isEmpty()) return
        dirtySet[repoRelative] = DirtyEntry(op, Clock.System.now().toEpochMilliseconds())
        _dirtyFileCountFlow.value = dirtySet.size
        scheduleMarkerWrite()
    }

    fun getDirtySnapshot(): Map<String, DirtyEntry> = dirtySet.toMap()

    /**
     * Epic 4.1: read-only accessor for the last-known-synced base sha (restored from the
     * `.stele-dirty-set.json` marker on [preload], advanced only by a successful [clearDirtySet]).
     * `WasmGitRepository` is the sole consumer — this keeps [baseSha] a single source of truth
     * instead of `WasmGitRepository` privately re-tracking a duplicate copy across calls.
     */
    fun getBaseSha(): String = baseSha

    /**
     * Epic 4.1: read-only accessor for the currently-staged [PendingCommit] (set by
     * [setPendingCommit]/[clearDirtySet]/[resetPendingCommit]). `WasmGitRepository` reads this at
     * `push()` time to hand `WasmGitWriteService.push()` the exact staged commit `commit()` (or an
     * auto-merge rebuild) produced, without duplicating this state privately.
     */
    fun getPendingCommit(): PendingCommit = pendingCommit

    /** Used by `commit()` (Phase 3) to persist the staged GitHub commit before `push()` runs. */
    fun setPendingCommit(commitSha: String, treeSha: String) {
        pendingCommit = PendingCommit.Staged(commitSha, treeSha)
        scheduleMarkerWrite()
    }

    /**
     * Task 3.3.2c: [WasmGitWriteService.abortMerge]'s equivalent of `GitRepository.abortMerge` —
     * resets any staged [PendingCommit] (from a completed `commit()` or an auto-merge tree
     * rebuild) back to [PendingCommit.None] without touching the dirty set, so an abandoned merge
     * attempt's staged commit/tree SHAs are never later pushed. Mirrors [clearDirtySet]'s
     * unconditional reset-to-`None`, but deliberately does NOT clear [dirtySet] or [baseSha] —
     * only a successful push does that.
     */
    fun resetPendingCommit() {
        pendingCommit = PendingCommit.None
        scheduleMarkerWrite()
    }

    /**
     * Clears the dirty set after a successful push, resets [pendingCommit] to [PendingCommit.None]
     * unconditionally (there is never a reason to pass a replacement staged value — see Task
     * 2.1.2d), and writes the marker immediately: the crash-safety-critical "clear last" write.
     */
    fun clearDirtySet(newBaseSha: String) {
        dirtySet.clear()
        _dirtyFileCountFlow.value = 0
        baseSha = newBaseSha
        pendingCommit = PendingCommit.None
        scheduleMarkerWrite()
    }

    private fun scheduleMarkerWrite() {
        if (markerWriteInFlight) {
            markerWriteDirty = true
            return
        }
        markerWriteInFlight = true
        scope.launch {
            writeMarkerNow()
            while (markerWriteDirty) {
                markerWriteDirty = false
                writeMarkerNow()
            }
            markerWriteInFlight = false
        }
    }

    private suspend fun writeMarkerNow() {
        val marker = DirtySetMarker(
            graphId = graphId,
            baseSha = baseSha,
            pendingCommit = pendingCommit,
            checkpointedAtMillis = Clock.System.now().toEpochMilliseconds(),
            dirtyFiles = dirtySet.toMap(),
        )
        val encoded = try {
            gitApiJson.encodeToString(marker)
        } catch (e: Throwable) {
            println("[SteleKit] dirty-set marker encode failed: ${e.message}")
            return
        }
        opfsWriteFile(markerPath(), encoded)
    }

    private suspend fun loadOpfsDirectory(graphPath: String) {
        val root = getOpfsRoot()
        val parts = graphPath.removePrefix("/").split("/")
        var dir: JsAny = root
        for (part in parts) {
            dir = try {
                getDirectoryHandle(dir, part, false)
            } catch (e: Throwable) {
                return
            }
        }
        loadFilesRecursive(dir, graphPath)
    }

    private suspend fun loadFilesRecursive(dirHandle: JsAny, currentPath: String) {
        val entries = listOpfsEntries(dirHandle)
        for (entry in entries) {
            val name = getEntryName(entry)
            val path = "$currentPath/$name"
            if (isFileEntry(entry)) {
                if (isImageFile(name)) {
                    val url = readOpfsFileAsObjectUrl(entry)
                    if (url != null) blobUrlCache[path] = url
                } else {
                    val content = readOpfsFile(entry)
                    if (content != null) cache[path] = content
                }
            } else if (isDirectoryEntry(entry)) {
                loadFilesRecursive(entry, path)
            }
        }
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "heic")
    }

    actual override fun getDefaultGraphPath(): String = homeDir
    actual override fun expandTilde(path: String): String =
        if (path.startsWith("~")) path.replaceFirst("~", homeDir) else path

    actual override fun readFile(path: String): String? = cache[path]

    /**
     * BLOCKER fix (web-git-writeback architecture review): uniform byte-level content accessor
     * for git write-back's content-read call sites. [readFile] only ever consults the plain-text
     * [cache] map, so a paranoid-mode (encrypted) dirty path — stored exclusively in [bytesCache]
     * by [writeFileBytes] — was never visible to it, poisoning the entire commit batch with a
     * spurious "No cached content for dirty path" failure. [bytesCache] is checked first (a
     * paranoid-mode file is never also present in [cache]); a plain-text [cache] hit is UTF-8
     * encoded on the way out. Deliberately does NOT round-trip through `readFile(): String?` —
     * encrypted bytes are not valid UTF-8 in general, and a lossy String conversion would corrupt
     * them.
     */
    fun getContentBytes(path: String): ByteArray? = bytesCache[path] ?: cache[path]?.encodeToByteArray()

    override suspend fun readFileSuspend(path: String): String? {
        cache[path]?.let { return it }
        val owner = githubOwner.ifEmpty { return null }
        val repo = githubRepo.ifEmpty { return null }
        val branch = githubBranch
        val token = githubToken
        // Convert absolute OPFS path back to a repo-relative path.
        // OPFS paths look like /stelekit/<graphId>/<repo-relative-path>
        val repoRelative = path.removePrefix("/stelekit/").substringAfter("/")
        val rawUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$repoRelative"
        val content = WasmSectionSyncService.githubFetch(rawUrl, token) ?: return null
        cache[path] = content
        scope.launch { opfsWriteFile(path, content) }
        return content
    }
    actual override fun fileExists(path: String): Boolean = cache.containsKey(path) || blobUrlCache.containsKey(path)
    actual override fun listFiles(path: String): List<String> =
        cache.keys
            .filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains('/') }
            .map { it.removePrefix("$path/") }
    actual override fun listDirectories(path: String): List<String> =
        cache.keys
            .filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }
            .filter { it.isNotEmpty() && cache.keys.any { k -> k.startsWith("$path/$it/") } }
            .distinct()

    // ponytail: single-threaded JS — pick and write always sequential, no race
    private var pendingDownloadMimeType: String = "application/octet-stream"

    actual override fun writeFile(path: String, content: String): Boolean {
        if (path.startsWith(DOWNLOAD_PREFIX)) {
            triggerBrowserTextDownload(path.removePrefix(DOWNLOAD_PREFIX), content, pendingDownloadMimeType)
            return true
        }
        cache[path] = content
        recordDirty(path, DirtyOp.WRITE)
        opfsWriteInFlight[path] = scope.async {
            try {
                opfsWriteFile(path, content)
            } finally {
                opfsWriteInFlight.remove(path)
            }
        }
        // Epic 4.3 (Task 4.3.1a): the fourth independent effect (web-local-folder-livesync) —
        // one-line delegation; all coalescing/freshness-check/actual-write logic lives on
        // HostDirectorySync. No-op when no host directory is connected for this graph.
        if (hostDirectorySync.hostDirHandle != null) {
            hostDirectorySync.scheduleHostWriteThrough(path, HostWritePayload.Text(content))
        }
        return true
    }

    /**
     * Task 3.3.1b: merge-time write for [WasmGitWriteService]'s GitHub non-overlapping auto-merge
     * (Story 3.3.1) — writes remote content fetched during a merge rebuild into the in-memory
     * cache and schedules the matching OPFS write, exactly like [writeFile], but deliberately does
     * NOT call [recordDirty]: auto-merged remote content is not a local edit, so re-marking it
     * dirty here would corrupt the "only push what changed locally" invariant (it would cause the
     * next commit to needlessly re-push content that already matches the remote).
     *
     * Task 4.3.1d (web-local-folder-livesync): for the identical reason, this deliberately never
     * calls `hostDirectorySync.scheduleHostWriteThrough` either — merged-in remote git content was
     * not written by the user in *this* browser tab, so pushing it back out to the host directory
     * would falsely attribute an external (GitHub-origin) change to a local edit. The host folder
     * should only ever receive content this tab's own [writeFile]/[writeFileBytes]/[deleteFile]
     * produced.
     */
    fun applyRemoteContent(path: String, content: String): Boolean {
        if (path.startsWith(DOWNLOAD_PREFIX)) return false
        cache[path] = content
        scope.launch { opfsWriteFile(path, content) }
        return true
    }

    /**
     * Real byte-level OPFS I/O for paranoid-mode encrypted writes — previously missing entirely
     * (this class inherited [FileSystem.writeFileBytes]'s throwing default, so paranoid-mode
     * writes crashed on web). Reuses [opfsWriteFileBytes] (already used by
     * `WasmMediaAttachmentService` for attachment uploads) — no OPFS-write logic duplicated here.
     */
    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        if (path.startsWith(DOWNLOAD_PREFIX)) return false
        bytesCache[path] = data
        recordDirty(path, DirtyOp.WRITE)
        opfsWriteInFlight[path] = scope.async {
            try {
                opfsWriteFileBytes(path, data.toJsArrayBuffer())
            } finally {
                opfsWriteInFlight.remove(path)
            }
        }
        // Epic 4.3 (Task 4.3.1b): same one-line delegation as writeFile, for paranoid-mode bytes.
        if (hostDirectorySync.hostDirHandle != null) {
            hostDirectorySync.scheduleHostWriteThrough(path, HostWritePayload.Bytes(data))
        }
        return true
    }

    override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? {
        pendingDownloadMimeType = mimeType
        return "$DOWNLOAD_PREFIX$suggestedName"
    }

    actual override fun directoryExists(path: String): Boolean =
        path == homeDir || cache.keys.any { it.startsWith("$path/") }
    actual override fun createDirectory(path: String): Boolean = true
    actual override fun deleteFile(path: String): Boolean {
        cache.remove(path)
        bytesCache.remove(path)
        recordDirty(path, DirtyOp.DELETE)
        scope.launch { opfsDeleteFile(path) }
        // Epic 4.3 (Task 4.3.1c): same one-line delegation, dispatches to flushHostWrite's
        // HostWritePayload.Delete branch (dirRemoveEntry against hostDirHandle).
        if (hostDirectorySync.hostDirHandle != null) {
            hostDirectorySync.scheduleHostWriteThrough(path, HostWritePayload.Delete)
        }
        return true
    }
    /**
     * Epic 7.1 (Task 7.1.1a): `HostRenameOp` — the seventh [FileSystem]-interface delegation touch
     * point, previously falling through to the interface default (`false`), a documented
     * pre-existing gap this phase closes (`research/architecture.md` §1). `cache`-mirroring stays
     * unconditional (applies regardless of whether host sync is active, matching every other
     * `cache` field on this class), then [HostDirectorySync.renameHostFile] is fired off
     * (`scope.launch`, matching this class's established sync-signature/async-side-effect pattern
     * for [writeFile]/[writeFileBytes]/[deleteFile]) only when a host directory is connected.
     * Returns `false` (nothing to rename) when [from] isn't present in [cache] — matches the
     * [FileSystem] interface default's contract for a non-existent source.
     */
    override fun renameFile(from: String, to: String): Boolean {
        val content = cache[from] ?: return false
        cache[to] = content
        cache.remove(from)
        if (hostDirectorySync.hostDirHandle != null) {
            scope.launch { hostDirectorySync.renameHostFile(from, to, content) }
        }
        return true
    }

    actual override fun pickDirectory(): String? = null
    override val supportsNativeDirectoryPicker: Boolean get() = showDirectoryPickerSupported()
    actual override suspend fun pickDirectoryAsync(): String? {
        if (!showDirectoryPickerSupported()) return null
        return try {
            val dirHandle = showDirectoryPicker()
            val name = getEntryName(dirHandle)
            val opfsPath = "$homeDir/$name"
            println("[SteleKit] pickDirectory: importing '$name' → '$opfsPath'")
            importUserDirToCache(dirHandle, opfsPath)
            hostDirectorySync.attachFreshHandle(dirHandle, opfsPath)
            val count = cache.keys.count { it.startsWith("$opfsPath/") }
            println("[SteleKit] pickDirectory: $count files imported to cache")
            opfsPath
        } catch (e: Throwable) {
            println("[SteleKit] showDirectoryPicker: ${e.message}")
            null
        }
    }

    private suspend fun importUserDirToCache(dirHandle: JsAny, currentPath: String) {
        val entries = listOpfsEntries(dirHandle)
        println("[SteleKit] importUserDirToCache: ${entries.size} entries in '$currentPath'")
        for (entry in entries) {
            val name = getEntryName(entry)
            val path = "$currentPath/$name"
            when {
                isFileEntry(entry) && isImageFile(name) -> {
                    readOpfsFileAsObjectUrl(entry)?.let { blobUrlCache[path] = it }
                }
                isFileEntry(entry) -> {
                    val content = readOpfsFile(entry)
                    if (content != null) {
                        cache[path] = content
                        scope.launch { opfsWriteFile(path, content) }
                    } else {
                        println("[SteleKit] importUserDirToCache: failed to read '$path'")
                    }
                }
                isDirectoryEntry(entry) -> importUserDirToCache(entry, path)
            }
        }
    }
    override suspend fun pickFileAsync(): String? = null

    /**
     * Epic 3.2 (Task 3.2.2a/d): delegates to [HostDirectorySync.onHostConflict]. Wired from
     * `App.kt` alongside the other write-behind flush callbacks (not `Main.kt` — `GraphLoader` is
     * only ever constructed later, per-active-graph, inside `App.kt`'s composition; see
     * [HostDirectorySync.onHostConflict]'s doc comment for the full rationale).
     */
    override fun setOnHostConflict(callback: ((path: String, hostContent: String) -> Unit)?) {
        hostDirectorySync.onHostConflict = callback ?: { _, _ -> }
    }

    /**
     * Epic 4.4 (Task 4.4.1b): delegates to [HostDirectorySync.onHostWriteFailed]. Wired from
     * `App.kt` alongside [setOnHostConflict], for the same reason — `GraphLoader` only exists
     * later, per-active-graph, inside `App.kt`'s composition.
     */
    override fun setOnHostWriteFailed(callback: ((dev.stapler.stelekit.error.DomainError.FileSystemError.WriteFailed) -> Unit)?) {
        hostDirectorySync.onHostWriteFailed = callback ?: {}
    }

    /**
     * Task 2.2.2b: one-line delegate to [HostDirectorySync.hostAccessStateFlow]'s current value —
     * satisfies the [FileSystem] interface's [graphPath]-scoped query for commonMain callers that
     * don't want to downcast to [PlatformFileSystem] to reach [hostDirectorySync] directly. [graphPath]
     * is intentionally unused: this graph's [HostDirectorySync] instance already tracks exactly one
     * graph's host-directory connection at a time (see `graphIdProvider`'s doc comment).
     */
    override suspend fun hostDirectoryAccessState(graphPath: String): HostAccessState =
        hostDirectorySync.hostAccessStateFlow.value

    /**
     * Epic 5.1 (Task 5.2.1a): one-line delegate to [HostDirectorySync.hostModTimes], populated by
     * [HostDirectorySync.pollHostDirectoryOnce]/`runHostReconciliation`. [HostDirectorySync.hostModTimes]
     * itself already returns nothing meaningful (an empty/miss map) when no host directory is
     * connected, so no separate `hostDirHandle == null` check is needed here — matches this
     * method's pre-Phase-5 `null` regression behavior exactly in that case.
     */
    actual override fun getLastModifiedTime(path: String): Long? = hostDirectorySync.hostModTimes[path]

    /**
     * Epic 5.1 (Task 5.2.1b): delegates to [HostDirectorySync.listFilesWithModTimes] — a single
     * map-iteration pass instead of N synchronous [getLastModifiedTime] calls. Falls through to
     * the [FileSystem] interface's default implementation (`listFiles` + per-file
     * [getLastModifiedTime]) whenever the delegate returns empty — covers both "no host directory
     * connected" and "connected, but this directory happens to have no known entries yet."
     */
    override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
        val delegated = hostDirectorySync.listFilesWithModTimes(path)
        return delegated.ifEmpty {
            listFiles(path).map { name -> name to (getLastModifiedTime("$path/$name") ?: 0L) }
        }
    }

    override fun registerBlobUrl(path: String, url: String) { blobUrlCache[path] = url }
    override fun resolveAssetUri(graphRoot: String, relativePath: String): String? =
        blobUrlCache["${graphRoot.trimEnd('/')}/$relativePath"]

    companion object {
        private const val DOWNLOAD_PREFIX = "/_wasm_dl_/"
        var githubOwner: String = ""
        var githubRepo: String = ""
        var githubBranch: String = "main"
        var githubToken: String? = null
    }
}

private fun triggerBrowserTextDownload(filename: String, content: String, mimeType: String): Unit =
    js("""(function() {
        var blob = new Blob([content], { type: mimeType });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click(); document.body.removeChild(a);
        setTimeout(function() { URL.revokeObjectURL(url); }, 1000);
    })()""")
