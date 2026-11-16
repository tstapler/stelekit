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
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
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

    /** True while the user's latest edits are not on disk, which is when leaving the sheet must ask first. */
    val hasUnsavedWork: Boolean get() = this != Saved
}

/** The sheet a saver owns: where its file lives and the sha of the bytes it loaded (null for a sheet never saved). */
class SheetTarget(val slug: String, val projectUuid: ProjectUuid, val sheet: SheetUuid, val loadedSha: String? = null)

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
    private var baseSha: String? = target.loadedSha
    private var savedVersion = 0L
    private var backoffLevel = 0
    private var deferrals = 0
    private var closed = false

    val state: StateFlow<SaveState> = mutableState

    init {
        scope.launch { runLoop() }
        scope.launch { ports.externalChanges.collect { onExternalChange() } }
    }

    /** Records [doc] as the scene to persist and restarts the debounce. The latest call wins; earlier ones are never written. */
    fun submit(doc: SketchDocumentV2) {
        if (closed) return
        latest.value = Submitted((latest.value?.version ?: 0L) + 1, doc)
        if (mutableState.value !is SaveState.Conflict) mutableState.value = SaveState.Pending
        wake.trySend(false)
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
    suspend fun reload(): Either<DomainError, SketchDocumentV2> = ports.slugLock.withSlug(target.slug) {
        val bytes = persister.readDiskBytes() ?: return@withSlug DomainError.FileSystemError.NotFound(sheetPath).left()
        SheetCodecV2.decode(bytes, limits).fold(
            { DomainError.FileSystemError.ReadFailed(sheetPath, "sheet not readable: ${it::class.simpleName}").left() },
            { doc ->
                baseSha = SheetSaveGuard.sha(bytes)
                latest.value = Submitted((latest.value?.version ?: 0L) + 1, doc)
                savedVersion = latest.value?.version ?: 0L
                ports.diagnostics.resolve(BannerRowId.SaveConflict)
                mutableState.value = SaveState.Saved
                doc.right()
            },
        )
    }

    /** Keeps the in-memory scene: re-bases onto the file now on disk and writes over it. The other version stays in git history. */
    suspend fun keepMine() {
        ports.slugLock.withSlug(target.slug) { baseSha = persister.readDiskBytes()?.let(SheetSaveGuard::sha) }
        ports.diagnostics.resolve(BannerRowId.SaveConflict)
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
            delay(debounceMs())
            if (latest.value?.version == seen) return
        }
    }

    private fun debounceMs(): Long = if (backoffLevel == 0) config.debounceMs else config.backoffMs[backoffLevel - 1]

    private suspend fun saveLatest() = saveGate.withLock {
        val snap = latest.value ?: return@withLock
        if (snap.version == savedVersion || mutableState.value is SaveState.Conflict) return@withLock
        mutableState.value = SaveState.Saving
        try {
            saveSnapshot(snap)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail(failureOf(t))
        }
    }

    private suspend fun saveSnapshot(snap: Submitted) {
        val encoded = encode(snap.doc) ?: return
        if (encoded.sha == baseSha) return saved(snap)
        when (val result = persister.persist(encoded, baseSha)) {
            Persist.Written -> {
                baseSha = encoded.sha
                saved(snap)
            }
            Persist.Deferred -> deferWrite()
            is Persist.Conflict -> conflict(encoded, result.diskSha)
            is Persist.Failed -> fail(result.reason)
        }
    }

    private suspend fun encode(doc: SketchDocumentV2): Encoded? {
        val started = ports.clockMillis()
        val result = withContext(encodeContext) {
            ports.encoder(doc, limits).map { bytes ->
                Encoded(bytes, SheetSaveGuard.sha(bytes), V2ToV1.projectScene(doc.scene).layers.map { it.encode() })
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
        ports.diagnostics.raise(BannerRowId.SaveFailed)
    }

    private fun uncaught(t: Throwable) {
        if (t is CancellationException) return
        fail(failureOf(t))
    }

    /** `OutOfMemoryError` has no common-stdlib type, so it is told apart by name (it only picks the logged token). */
    private fun failureOf(t: Throwable): SaveFailure =
        if (t::class.simpleName == OOM_NAME) SaveFailure.OutOfMemory else SaveFailure.Unexpected

    private suspend fun conflict(encoded: Encoded, diskSha: String?) {
        ports.log.saveConflict(target.sheet.value)
        SheetSaveGuard.writeConflictCopy(ports.store, target.slug, target.sheet, encoded.bytes).fold(
            { fail(SaveFailure.Write) },
            { path ->
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
        const val OOM_NAME = "OutOfMemoryError"
    }
}
