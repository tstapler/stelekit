// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.ImmediateSketchDurability
import dev.stapler.stelekit.projectbridge.NoPageLifecycle
import dev.stapler.stelekit.projectbridge.PageLifecycle
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SketchDurability
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.merge.MergeReport
import dev.stapler.stelekit.sketch.v2.merge.SceneMerge
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.search.SketchTextExtractor
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/** Why a save did not complete; the work is always kept in memory. [token] is the id the `sketch.persist.failed` line carries. */
enum class SaveFailure(val token: String) {
    Encode("encode"),
    OutOfMemory("oom"),
    Write("write"),
    Unavailable("unavailable"),
    Unexpected("unexpected"),

    /** The sidecar on disk is ahead of this device's last reconcile; a retry waits for the reconcile to catch up. */
    Behind("behind"),

    /** The browser's storage is full (`StorageQuotaExceeded`): a retry cannot succeed until space is freed, so the banner offers Save copy. */
    Quota("quota"),
}

sealed interface SaveState {
    /** The sheet file holds what the editor holds. */
    data object Saved : SaveState

    /** Edits are waiting for the debounce, or for a deferred write (git sync busy). */
    data object Pending : SaveState
    data object Saving : SaveState
    data class Failed(val reason: SaveFailure) : SaveState

    /** The file changed under the editor. [copyPath] is the saved copy of the in-memory scene, null when there were no unsaved edits. */
    data class Conflict(val copyPath: String?) : SaveState

    /** Another tab holds the writer lease: read-only, the debounce is stopped and nothing is written (never a [Failed]). */
    data object LeaseConflict : SaveState

    /** True while the user's latest edits are not on disk, which is when leaving the sheet must ask first. */
    val hasUnsavedWork: Boolean get() = this != Saved
}

private const val OOM_NAME = "OutOfMemoryError"

/** `OutOfMemoryError` has no common-stdlib type, so it is told apart by name (it only picks the logged token). */
private fun failureOf(t: Throwable): SaveFailure =
    if (t::class.simpleName == OOM_NAME) SaveFailure.OutOfMemory else SaveFailure.Unexpected

/** A conflict or a lost writer lease stops the debounce: no further write is attempted from this state. */
private val SaveState.blocksSaving: Boolean get() = this is SaveState.Conflict || this == SaveState.LeaseConflict

/** The sheet a saver owns: where its file lives and the sha of the bytes it loaded (null for a sheet never saved). */
class SheetTarget(
    val slug: String,
    val projectUuid: ProjectUuid,
    val sheet: SheetUuid,
    val loadedSha: String? = null,
    /** The document decoded from the bytes of [loadedSha]: the base of an element-level merge. Null means a changed file is never merged. */
    val loadedDoc: SketchDocumentV2? = null,
)

/**
 * A changed file was merged with this device's scene and the result written (ADR-008). [ours] is the scene that was saved
 * and [merged] what is on disk now; the editor takes `SceneMerge.merge(ours, itsCurrentScene, merged)` and calls
 * [SheetSaver.acknowledgeMerge]. Until then every submitted scene is rebased the same way, so the editor can never write
 * over the merge.
 */
class SheetMerge(val ours: SketchDocumentV2, val merged: SketchDocumentV2, val report: MergeReport) {
    /** The version of the scene the editor submitted after taking this merge; saves of older versions are rebased onto it, newer ones are the editor's own. */
    internal val adoptedAt = MutableStateFlow<Long?>(null)
}

/** Receives one measurement per encode: element count, milliseconds and bytes (`sketch.encode_ms_5000`, `sketch.save_bytes_5000`). */
fun interface SaveSamples {
    fun encoded(elements: Int, millis: Long, bytes: Int)

    companion object {
        val None = SaveSamples { _, _, _ -> }
    }
}

/** Everything a saver talks to. [externalChanges] emits when the post-sync coordinator pulled this project's files. */
class SheetSaverPorts(
    val repos: SketchRepositories,
    val store: ProjectSidecarStore,
    val slugLock: SidecarSlugLock,
    val diagnostics: DiagnosticsPort,
    val log: SketchLog = SketchLog(),
    val writeActor: DatabaseWriteActor? = null,
    val samples: SaveSamples = SaveSamples.None,
    val externalChanges: Flow<Unit> = emptyFlow(),
    val encoder: (SketchDocumentV2, SceneLimits) -> Either<SheetError, ByteArray> = { doc, limits -> SheetCodecV2.encode(doc, limits) },
    val clockMillis: () -> Long = MonotonicClock::millis,
    val durability: SketchDurability = ImmediateSketchDurability,
    val lifecycle: PageLifecycle = NoPageLifecycle,
    /** True merges a file that changed under the saver element by element (E14); false keeps the conflict copy of every such change. */
    val mergeOnConflict: Boolean = false,
)

/** Timing knobs. The back-off steps apply after an encode slower than [encodeBudgetMs] and reset on a fast one. */
class SheetSaverConfig(
    val debounceMs: Long = DEBOUNCE_MS,
    val backoffMs: List<Long> = BACKOFF_STEPS_MS,
    val encodeBudgetMs: Long = ENCODE_BUDGET_MS,
    val deferredRetryMs: Long = DEFERRED_RETRY_MS,
) {
    companion object {
        const val DEBOUNCE_MS = 500L
        const val ENCODE_BUDGET_MS = 250L
        const val DEFERRED_RETRY_MS = 2_000L
        val BACKOFF_STEPS_MS = listOf(1_000L, 2_000L)
    }
}

internal object MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun millis(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * The single writer of one open v2 sheet (Story 1.4.4). Edits arrive through [submit]; one loop debounces them, encodes
 * the latest document off the caller's thread inside a `Throwable` guard, and writes it under the project's
 * [SidecarSlugLock]: the sheet file, the model (layer mirror, `fileSha256`) and the database sheet row as one operation.
 * Writes never overlap, an unchanged encoding writes nothing, and a file that changed underneath the saver
 * (`baseSha` mismatch, e.g. a git pull) is never overwritten: the in-memory scene goes to a conflict copy and the user chooses
 * [reload] or [keepMine]. A failed save keeps the work in memory and shows a `SaveFailed` entry whose action is [retry].
 *
 * Owns its scope with a [CoroutineExceptionHandler], so an `OutOfMemoryError` anywhere in the loop becomes a failed save
 * instead of reaching the platform's uncaught-exception handler (which kills an Android process). Call [close] when the
 * editor leaves; a closed saver ignores [submit].
 */
class SheetSaver(
    private val target: SheetTarget,
    private val ports: SheetSaverPorts,
    private val config: SheetSaverConfig = SheetSaverConfig(),
    private val limits: SceneLimits = SceneLimits.Default,
    context: CoroutineContext = Dispatchers.Default,
    private val encodeContext: CoroutineContext = Dispatchers.Default,
) {
    private class Submitted(val version: Long, val doc: SketchDocumentV2)

    private val scope = CoroutineScope(context + SupervisorJob() + CoroutineExceptionHandler { _, t -> uncaught(t) })
    private val latest = MutableStateFlow<Submitted?>(null)
    private val wake = Channel<Boolean>(Channel.CONFLATED)
    private val saveGate = Mutex()
    private val mutableState = MutableStateFlow<SaveState>(SaveState.Saved)
    private val sheetPath = SheetPaths.pathFor(target.sheet, SheetPaths.SCHEMA_V2)
    private val persister = SheetPersister(target, ports)
    private val copies = ConflictCopyKeeper(target, ports, limits)
    private var baseSha: String? = target.loadedSha

    /** The document of the bytes `baseSha` names, kept as bytes once this saver wrote or adopted a file so the decode happens only when a merge needs it. */
    private var baseDoc: SketchDocumentV2? = target.loadedDoc
    private var baseBytes: ByteArray? = null
    private val pendingMerge = MutableStateFlow<SheetMerge?>(null)
    private var savedVersion = 0L
    private var backoffLevel = 0
    private var deferrals = 0
    private var closed = false

    /** True after a write was accepted but never confirmed persisted: the next save rewrites even an unchanged encoding. */
    private var needsRewrite = false

    val state: StateFlow<SaveState> = mutableState

    /** The latest merge of another device's changes that the editor has not taken yet; null when there is none. */
    val merges: StateFlow<SheetMerge?> = pendingMerge

    init {
        scope.launch { runLoop() }
        scope.launch {
            // One failed event must not end the collection: later pulls still need their Reload prompt.
            ports.externalChanges.collect {
                try {
                    onExternalChange()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    ports.log.persistFailed(failureOf(t).token)
                }
            }
        }
        scope.launch { ports.lifecycle.hidden.collect { saveLatest() } }
    }

    /** Records [doc] as the scene to persist and restarts the debounce. The latest call wins; earlier ones are never written. */
    fun submit(doc: SketchDocumentV2) {
        if (closed) return
        latest.update { Submitted((it?.version ?: 0L) + 1, doc) }
        if (!mutableState.value.blocksSaving) mutableState.value = SaveState.Pending
        wake.trySend(false)
    }

    /**
     * The editor took [merge] into its scene and has submitted that scene, so later submissions are its own again. It waits for
     * a save in flight, which still rebases onto the merge, so no stale scene is written once the merge is acknowledged.
     * A newer merge that arrived meanwhile is kept.
     */
    suspend fun acknowledgeMerge(merge: SheetMerge) = saveGate.withLock {
        pendingMerge.compareAndSet(merge, null)
    }

    /**
     * The editor took [merge] and submits its resulting scene [doc] as one step: scenes submitted before it are still rebased onto the
     * merge, scenes submitted after it (the user's next edits) are the editor's own even before [acknowledgeMerge] lands.
     */
    fun submitAdopted(merge: SheetMerge, doc: SketchDocumentV2) {
        if (closed) return
        val next = latest.updateAndGet { Submitted((it?.version ?: 0L) + 1, doc) }
        merge.adoptedAt.value = next?.version
        if (!mutableState.value.blocksSaving) mutableState.value = SaveState.Pending
        wake.trySend(false)
    }

    /** The editor could not combine [merge] with its scene: the file holds the merge and the editor does not, so the user chooses (Use theirs or Keep mine). */
    suspend fun mergeNotApplied(merge: SheetMerge) = saveGate.withLock {
        if (!pendingMerge.compareAndSet(merge, null)) return@withLock
        ports.log.saveConflict(target.sheet.value)
        mutableState.value = SaveState.Conflict(null)
        ports.diagnostics.raise(BannerRowId.SaveConflict)
    }

    /** [doc] with the changes of [merge] folded in, so a scene the editor drew before it took the merge cannot write over it. Null when that is refused. */
    private fun rebased(merge: SheetMerge, doc: SketchDocumentV2): SketchDocumentV2? =
        SceneMerge.merge(merge.ours, doc, merge.merged, limits).getOrNull()?.doc

    /** The scene cannot be combined with the merged file: it is never written over it, goes to a conflict copy, and the user chooses. */
    private suspend fun keepUnmerged(snap: Submitted) {
        // The merge stays pending: if the copy cannot be written, a retry must be refused again, never written over the merged file.
        val encoded = encode(snap.doc) ?: return
        conflict(snap.version, encoded, baseSha)
    }

    /** Saves the latest document now, skipping the debounce, and returns where that left the sheet. */
    suspend fun flush(): SaveState {
        if (!closed) saveLatest()
        return mutableState.value
    }

    /** Retries after a failed save (the Retry action of the `SaveFailed` banner). */
    fun retry() {
        if (closed || mutableState.value !is SaveState.Failed) return
        ports.diagnostics.resolve(BannerRowId.SaveFailed)
        deferrals = 0
        mutableState.value = SaveState.Pending
        wake.trySend(true)
    }

    /**
     * Adopts the file on disk (the other device's version) and returns its scene for the editor to show. The in-memory
     * scene is discarded only after it was kept as a conflict copy, which [SaveState.Conflict] with a path guarantees.
     */
    suspend fun reload(): Either<DomainError, SketchDocumentV2> = saveGate.withLock { ports.slugLock.withSlug(target.slug) {
        val unsaved = latest.value?.takeIf { it.version != savedVersion }
        if (unsaved != null) copies.keep(unsaved.version, unsaved.doc).onLeft { return@withSlug it.left() }
        val bytes = persister.readDiskBytes() ?: return@withSlug DomainError.FileSystemError.NotFound(sheetPath).left()
        SheetCodecV2.decode(bytes, limits).fold(
            { DomainError.FileSystemError.ReadFailed(sheetPath, "sheet not readable: ${it::class.simpleName}").left() },
            { doc ->
                baseSha = SheetSaveGuard.sha(bytes)
                baseDoc = doc
                baseBytes = null
                pendingMerge.value = null
                latest.update { Submitted((it?.version ?: 0L) + 1, doc) }
                savedVersion = latest.value?.version ?: 0L
                ports.diagnostics.resolve(BannerRowId.SaveConflict)
                mutableState.value = SaveState.Saved
                ports.store.pruneConflictCopies(target.slug, target.sheet.value, emptySet())
                doc.right()
            },
        )
    } }

    /**
     * Keeps the in-memory scene: re-bases onto the file now on disk and writes over it. The other version stays in git history.
     * [current] is the scene the editor holds; it is resubmitted so a conflict raised with no local edits still has something to
     * write. With no scene known at all there is nothing of "mine" to write, so the file is left as is and the base is not re-based.
     */
    suspend fun keepMine(current: SketchDocumentV2? = null) = saveGate.withLock {
        // [current] was read before this waited on the gate; an edit submitted meanwhile is newer, so [latest] wins.
        val mine = latest.value?.doc ?: current
        if (mine == null) {
            ports.diagnostics.resolve(BannerRowId.SaveConflict)
            mutableState.value = SaveState.Saved
            return@withLock
        }
        ports.slugLock.withSlug(target.slug) {
            val disk = persister.readDiskBytes()
            baseSha = disk?.let(SheetSaveGuard::sha)
            baseBytes = disk
            baseDoc = null
        }
        pendingMerge.value = null
        ports.diagnostics.resolve(BannerRowId.SaveConflict)
        ports.store.pruneConflictCopies(target.slug, target.sheet.value, emptySet())
        latest.update { Submitted((it?.version ?: 0L) + 1, mine) }
        mutableState.value = SaveState.Pending
        wake.trySend(true)
    }

    fun close() {
        closed = true
        wake.close()
        scope.cancel()
    }

    private suspend fun runLoop() {
        for (immediate in wake) {
            if (!immediate) awaitQuiet()
            saveLatest()
        }
    }

    /** Waits until no [submit] arrived for one debounce interval. */
    private suspend fun awaitQuiet() {
        while (true) {
            val seen = latest.value?.version
            delay(debounceMs)
            if (latest.value?.version == seen) return
        }
    }

    private val debounceMs: Long get() = if (backoffLevel == 0) config.debounceMs else config.backoffMs[backoffLevel - 1]

    private suspend fun saveLatest() = saveGate.withLock {
        val snap = latest.value ?: return@withLock
        if (snap.version == savedVersion) return@withLock
        if (mutableState.value is SaveState.Conflict) return@withLock keepWhileConflicted(snap)
        if (mutableState.value.blocksSaving) return@withLock
        mutableState.value = SaveState.Saving
        try {
            saveSnapshot(snap)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail(failureOf(t))
        }
    }

    /** Edits made after a conflict are never written over the other version; they go to a conflict copy so none is lost. */
    private suspend fun keepWhileConflicted(snap: Submitted) {
        // A throw here (encode, OOM) must not end the save loop or the hidden-page collector; the conflict prompt stays up.
        try {
            copies.keep(snap.version, snap.doc).fold(
                { ports.log.persistFailed("conflict-copy") },
                { path -> if (path != null && mutableState.value is SaveState.Conflict) mutableState.value = SaveState.Conflict(path) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ports.log.persistFailed(failureOf(t).token)
        }
    }

    private suspend fun saveSnapshot(submitted: Submitted) {
        val pending = pendingMerge.value
        val stale = pending != null && (pending.adoptedAt.value?.let { submitted.version < it } ?: true)
        val snap = if (pending == null || !stale) submitted else Submitted(submitted.version, rebased(pending, submitted.doc) ?: return keepUnmerged(submitted))
        val encoded = encode(snap.doc) ?: return
        if (encoded.sha == baseSha && !needsRewrite) return saved(snap)
        when (val result = persister.persist(encoded, baseSha, merge = mergeAttempt(snap.doc))) {
            Persist.Written -> {
                adoptBase(encoded)
                confirmPersisted(snap)
            }
            is Persist.Merged -> {
                adoptBase(result.encoded)
                if (!result.report.isEmpty) pendingMerge.value = SheetMerge(snap.doc, result.doc, result.report)
                confirmPersisted(snap)
            }
            Persist.Deferred -> deferWrite()
            is Persist.Conflict -> conflict(snap.version, encoded, result.diskSha)
            is Persist.Failed -> fail(result.reason)
            Persist.LeaseLost -> {
                // The store refused for lack of the writer lease: read-only, the debounce stops, the scene stays in memory.
                mutableState.value = SaveState.LeaseConflict
                ports.diagnostics.raise(BannerRowId.LeaseConflict, "Edited in another tab.")
            }
        }
    }

    private fun adoptBase(written: Encoded) {
        baseSha = written.sha
        baseBytes = written.bytes
        baseDoc = null
    }

    /** What lets the persister merge a changed file; null when merging is off or this saver has no base to merge against. */
    private fun mergeAttempt(ours: SketchDocumentV2): MergeAttempt? {
        // A second merge before the editor took the first would rebase onto a base the editor does not hold yet: that case keeps the copy.
        if (!ports.mergeOnConflict || pendingMerge.value != null) return null
        val base = { baseDoc ?: baseBytes?.let { SheetCodecV2.decode(it, limits).getOrNull() } }
        return MergeAttempt(base, ours) { doc -> encodeNow(doc) }
    }

    private fun encodeNow(doc: SketchDocumentV2): Encoded? = ports.encoder(doc, limits).map { bytes ->
        Encoded(bytes, SheetSaveGuard.sha(bytes), V2ToV1.projectScene(doc.scene).layers.map { it.encode() }, SketchTextExtractor.extract(doc))
    }.getOrNull()

    private suspend fun encode(doc: SketchDocumentV2): Encoded? {
        val started = ports.clockMillis()
        val result = withContext(encodeContext) {
            ports.encoder(doc, limits).map { bytes ->
                Encoded(bytes, SheetSaveGuard.sha(bytes), V2ToV1.projectScene(doc.scene).layers.map { it.encode() }, SketchTextExtractor.extract(doc))
            }
        }
        val millis = ports.clockMillis() - started
        val encoded = result.getOrNull() ?: run {
            fail(SaveFailure.Encode)
            return null
        }
        backoffLevel = if (millis > config.encodeBudgetMs) minOf(backoffLevel + 1, config.backoffMs.size) else 0
        ports.samples.encoded(doc.scene.elementsInOrder.size, millis, encoded.bytes.size)
        return encoded
    }

    /** Reports Saved only after the platform says the files reached storage; until then the state stays Saving. */
    private suspend fun confirmPersisted(snap: Submitted) {
        ports.durability.awaitPersisted(target.slug, sheetPath).fold(
            { error ->
                needsRewrite = true
                fail(if (error is DomainError.FileSystemError.StorageQuotaExceeded) SaveFailure.Quota else SaveFailure.Write)
            },
            {
                needsRewrite = false
                saved(snap)
            },
        )
    }

    private fun saved(snap: Submitted) {
        savedVersion = snap.version
        deferrals = 0
        ports.diagnostics.resolve(BannerRowId.SaveFailed)
        mutableState.value = if (latest.value?.version == snap.version) SaveState.Saved else SaveState.Pending
    }

    private fun deferWrite() {
        deferrals++
        if (deferrals > REPLAN_ATTEMPTS) return fail(SaveFailure.Behind)
        mutableState.value = SaveState.Pending
        scope.launch {
            delay(config.deferredRetryMs)
            wake.trySend(true)
        }
    }

    private fun fail(reason: SaveFailure) {
        ports.log.persistFailed(reason.token)
        mutableState.value = SaveState.Failed(reason)
        ports.diagnostics.raise(BannerRowId.SaveFailed, if (reason == SaveFailure.Quota) QUOTA_DETAIL else "")
    }

    private fun uncaught(t: Throwable) {
        if (t is CancellationException) return
        fail(failureOf(t))
    }

    private suspend fun conflict(version: Long, encoded: Encoded, diskSha: String?) {
        ports.log.saveConflict(target.sheet.value)
        SheetSaveGuard.writeConflictCopy(ports.store, target.slug, target.sheet, encoded.bytes).fold(
            { fail(SaveFailure.Write) },
            { path ->
                copies.noteKept(version)
                mutableState.value = SaveState.Conflict(path)
                ports.diagnostics.raise(BannerRowId.SaveConflict)
            },
        )
        if (diskSha == null) ports.log.persistFailed("file-missing")
    }

    /** The post-sync coordinator pulled this project: when the open sheet's file changed, prompt Reload without waiting for a save. */
    private suspend fun onExternalChange() {
        val diskSha = ports.slugLock.withSlug(target.slug) { persister.readDiskBytes()?.let(SheetSaveGuard::sha) }
        if (diskSha == null || diskSha == baseSha) return
        val unsaved = latest.value?.version?.let { it != savedVersion } == true
        if (unsaved) {
            wake.trySend(true)
            return
        }
        ports.log.saveConflict(target.sheet.value)
        mutableState.value = SaveState.Conflict(null)
        ports.diagnostics.raise(BannerRowId.SaveConflict)
    }

    private companion object {
        const val REPLAN_ATTEMPTS = 3
        const val QUOTA_DETAIL = "quota"
    }
}
