// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.stapler.stelekit.capture.AppendOutcome
import dev.stapler.stelekit.capture.CaptureResult
import dev.stapler.stelekit.capture.CaptureTarget
import dev.stapler.stelekit.capture.EnqueueOutcome
import dev.stapler.stelekit.capture.InboxSlot
import dev.stapler.stelekit.capture.JournalAppender
import dev.stapler.stelekit.capture.ShareContent
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.capture.OffGraphCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.domain.CaptureEnrichmentCoordinator
import dev.stapler.stelekit.domain.ImportService
import dev.stapler.stelekit.domain.ScanOutcome
import dev.stapler.stelekit.domain.ScanResult
import dev.stapler.stelekit.domain.TopicSuggestion
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val logger = Logger("CaptureViewModel")

    // Dedicated scope (not raw viewModelScope) so a CoroutineExceptionHandler can guard the
    // enrichment coroutines against an uncaught Throwable killing the process — see
    // project_plans/stelekit-capture-auto-enrich/implementation/plan.md Story 2.1.2. This alone
    // does NOT keep collectLatest alive after a per-iteration failure; the per-iteration
    // try/catch in init{} below is the actual mitigation for that.
    private val scope = CoroutineScope(
        viewModelScope.coroutineContext + CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                logger.error(
                    "Uncaught Throwable in capture-enrichment coroutine — " +
                        "${e::class.simpleName}: ${e.message}",
                )
            }
        },
    )

    private val _captureText = MutableStateFlow("")
    val captureText: StateFlow<String> = _captureText.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    sealed class SaveState {
        data object Idle : SaveState()
        data object Saving : SaveState()
        data object Saved : SaveState()

        /** Durably queued in the share inbox for [graphName]; delivered when that graph is ready. */
        data class Queued(val graphName: String) : SaveState()
        data class Error(val throwable: Throwable?) : SaveState()
    }

    /** Outcome of a Back auto-save (Task 4.2.1g/h): drives the in-sheet toast with Undo and Change. */
    sealed interface BackSaveState {
        data object None : BackSaveState
        data object Saving : BackSaveState
        data class Done(val message: String, val record: RecentCapture?, val queued: Boolean) : BackSaveState
    }

    private val steleApp: SteleKitApplication? get() = getApplication<Application>() as? SteleKitApplication

    // ---- Destination (Story 4.2.1) ----------------------------------------------------------

    private var currentCaptureId = UuidGenerator.generateV7()

    /** Idempotency key for this share: the journal block uuid, so a replayed save is `AlreadyPresent`. */
    val captureId: String get() = currentCaptureId

    @Volatile
    private var trustedImagePath: String? = null
    private var overrideGraphId: GraphId? = null
    private var explicitChoice: GraphId? = null
    private val destinationTrigger = MutableStateFlow(0)

    private val _destination = MutableStateFlow<CaptureDestination>(CaptureDestination.Legacy)
    val destination: StateFlow<CaptureDestination> = _destination.asStateFlow()

    private val _graphChoices = MutableStateFlow<List<GraphChoice>>(emptyList())
    val graphChoices: StateFlow<List<GraphChoice>> = _graphChoices.asStateFlow()

    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen.asStateFlow()

    private val _resultNote = MutableStateFlow<String?>(null)

    /** "Added to Work graph", "Already added", "Saved. It will be added to the first graph you create." */
    val resultNote: StateFlow<String?> = _resultNote.asStateFlow()

    private val _backSave = MutableStateFlow<BackSaveState>(BackSaveState.None)
    val backSave: StateFlow<BackSaveState> = _backSave.asStateFlow()

    /** False once the activity is gone, so a late Back-save result falls back to a system Toast. */
    @Volatile
    var hostAttached: Boolean = true

    fun setMenuOpen(open: Boolean) { _menuOpen.value = open }

    /** Applies a Direct Share override and/or a restored capture id (process death), then re-resolves. */
    fun beginShare(overrideGraphId: String?, restoredCaptureId: String? = null) {
        this.overrideGraphId = overrideGraphId?.let(::GraphId)
        restoredCaptureId?.let { currentCaptureId = it }
        destinationTrigger.value++
    }

    /** A new share while the overlay is already open: after a finished save the overlay starts fresh. */
    fun onNewShare(text: String, overrideGraphId: String?, imagePath: String? = null) {
        val earlier = _captureText.value.trim()
        val untouched = _saveState.value == SaveState.Idle && _backSave.value == BackSaveState.None
        if (untouched && earlier.isNotEmpty() && earlier == text.trim()) {
            beginShare(overrideGraphId)
            return
        }
        if (untouched && earlier.isNotEmpty()) queueEarlierShare(earlier)
        if (!untouched || earlier.isNotEmpty()) resetForNewCapture()
        attachImage(imagePath)
        initializeText(text)
        beginShare(overrideGraphId)
    }

    /** Queues the text of a share that a newer one is replacing; on failure it is put back so nothing is dropped. */
    private fun queueEarlierShare(earlier: String) {
        val app = steleApp ?: return
        val slot = when (val dest = _destination.value) {
            is CaptureDestination.Ready -> InboxSlot.Graph(dest.graph.id)
            is CaptureDestination.Unavailable -> dest.graphId?.let { InboxSlot.Graph(it) } ?: InboxSlot.Unassigned
            else -> InboxSlot.Unassigned
        }
        val earlierId = currentCaptureId
        val image = trustedImagePath
        app.appScope.launch {
            val queued = enqueue(app, slot, earlier, earlierId, image).isRight()
            if (queued) {
                toastOnMain("Your earlier note was queued.")
            } else {
                _captureText.update { now -> if (now.isBlank()) earlier else "$earlier\n\n$now" }
                toastOnMain("Couldn't queue your earlier note; it is kept below.")
            }
        }
    }

    /** Trusts [path] as this share's image only if it lies inside the app-private share-image directory. */
    fun attachImage(path: String?) {
        trustedImagePath = path?.takeIf { ShareIntake.isPrivateImage(getApplication(), it) }
    }

    val attachedImagePath: String? get() = trustedImagePath

    private fun resetForNewCapture() {
        currentCaptureId = UuidGenerator.generateV7()
        explicitChoice = null
        savedContext = null
        _saveState.value = SaveState.Idle
        _resultNote.value = null
        _backSave.value = BackSaveState.None
        _captureText.value = ""
    }

    fun retryDestination() { destinationTrigger.value++ }

    // Test seams (same-module tests drive the UI without a real GraphManager).
    internal fun setDestinationForTest(destination: CaptureDestination, choices: List<GraphChoice> = emptyList()) {
        _destination.value = destination
        _graphChoices.value = choices
    }

    internal fun setSavedForTest(note: String) {
        _resultNote.value = note
        _saveState.value = SaveState.Saved
    }

    internal fun setSaveErrorForTest() { _saveState.value = SaveState.Error(IllegalStateException("test")) }

    internal fun setBackSaveForTest(state: BackSaveState) { _backSave.value = state }

    val overrideGraphIdValue: String? get() = overrideGraphId?.value

    /** True once this share has been saved or queued (the `handled` flag kept in savedInstanceState). */
    val isHandled: Boolean get() = _saveState.value != SaveState.Idle || _backSave.value is BackSaveState.Done

    /** Process-death restore of an already-handled share: a second Save must not add it again. */
    fun restoreHandled() {
        _resultNote.value = "Already added"
        _saveState.value = SaveState.Saved
    }

    /** Toast Change: after the undo, back to the editable sheet with the destination menu open. */
    fun reopenAfterUndo(text: String) {
        resetForNewCapture()
        _captureText.value = text
        _menuOpen.value = true
        destinationTrigger.value++
    }

    /** Menu pick: changes where this share goes and records `capture_last_graph_id` (nothing else). */
    fun selectGraph(id: GraphId) {
        explicitChoice = id
        steleApp?.captureTargetSettings()?.recordLastUsed(id)
        _menuOpen.value = false
        destinationTrigger.value++
    }

    /** "Save to <fallback>" from the unavailable state. */
    fun saveToFallback() {
        val fallback = (_destination.value as? CaptureDestination.Unavailable)?.fallback ?: return
        explicitChoice = fallback.id
        steleApp?.captureTargetSettings()?.recordLastUsed(fallback.id)
        destinationTrigger.value++
        scope.launch {
            _destination.first { it is CaptureDestination.Ready }
            save()
        }
    }

    private suspend fun observeDestination() {
        val app = steleApp ?: return
        val gm = app.graphManager ?: run { _destination.value = CaptureDestination.NoGraphs; return }
        combine(
            gm.graphRegistry,
            gm.activeRepositorySet.map { it != null }.distinctUntilChanged(),
            destinationTrigger,
        ) { registry, open, _ -> registry to open }.collectLatest { (registry, open) ->
            _graphChoices.value = registry.graphs.map { GraphChoice(it.id, it.displayName) }
            resolveDestination(app, registry, open)
        }
    }

    private suspend fun resolveDestination(app: SteleKitApplication, registry: GraphRegistry, open: Boolean) {
        val reasons = probeReasons(app, registry)
        val inputs = DestinationInputs(
            registry, app.captureTargetSettings(), explicitChoice, overrideGraphId, activeOpen = open,
        )
        val first = CaptureDestinations.resolve(inputs) { reasons[it.id] }
        _destination.value = first
        if (first == CaptureDestination.Checking) {
            delay(CaptureDestinations.CHECKING_CAP_MS)
            _destination.value = CaptureDestinations.resolve(inputs.copy(checkingExpired = true)) { reasons[it.id] }
        }
    }

    /** Probes every non-active graph; if that takes over the cap, they all read as "took too long". */
    private suspend fun probeReasons(app: SteleKitApplication, registry: GraphRegistry): Map<GraphId, String?> {
        val others = registry.graphs.filter { it.id != registry.activeGraphId }
        return withTimeoutOrNull(CaptureDestinations.CHECKING_CAP_MS) {
            others.associate { it.id to app.offGraphUnavailableReason(it) }
        } ?: others.associate { it.id to "it took too long to check." }
    }

    /** Settles Checking/Unavailable once more for a Back press: waits at most the cap, returns the result. */
    private suspend fun settledDestination(): CaptureDestination =
        withTimeoutOrNull(CaptureDestinations.CHECKING_CAP_MS + 500) {
            _destination.first { it != CaptureDestination.Checking }
        } ?: _destination.value

    /**
     * Ties a scan result to the exact [captureText] it was computed for, so save-time
     * staleness checks (AC #4) are structural rather than a separate boolean flag.
     */
    sealed interface ScanState {
        data object NotReady : ScanState
        data class Ready(
            val text: String,
            val result: ScanResult,
            // Confirm-first bucket from the auto-apply precision floor — short single-word
            // matches withheld from `result.linkedText`, rendered as "confirm existing link"
            // chips (Epic 3.1) instead of silently linked.
            val confirmFirstNames: List<String> = emptyList(),
        ) : ScanState
    }
    private val _scanState = MutableStateFlow<ScanState>(ScanState.NotReady)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /**
     * Snapshot of what a successful [save] just wrote, so a post-save chip accept (Epic 4.2)
     * reuses the same graph/repositories instead of re-resolving "the active graph" (ADR-002's
     * scope boundary). `internal` (not `private`) so tests can access it directly — see
     * `CaptureActivityTest`'s KDoc. Note: [writer]/[writeActor]/[pageRepository]/
     * [blockRepository] lack `equals`/`hashCode`, so equality here is reference-only on those.
     */
    internal data class SavedCaptureContext(
        val block: Block,
        val page: Page,
        val blocks: List<Block>,
        val graphPath: String,
        val graphId: GraphId,
        val writer: GraphWriter,
        val writeActor: DatabaseWriteActor?,
        val pageRepository: PageRepository,
        val blockRepository: BlockRepository,
    )
    @Volatile
    private var savedContext: SavedCaptureContext? = null

    /**
     * Test-only accessor for [savedContext] — `internal` visibility avoids reflection in tests
     * (mirrors `FountainDecoder.mixedPartsCountForTest`'s `*ForTest` pattern).
     */
    internal var savedContextForTest: SavedCaptureContext?
        get() = savedContext
        set(value) { savedContext = value }

    /**
     * Serializes the post-save read-modify-write cycle in [acceptSuggestionPostSave]/
     * [acceptExistingLinkPostSave] against each other. Without this, two chip accepts tapped
     * within the same post-save "Done" window both capture the same stale [savedContext]
     * snapshot, build their updated block from the same pre-accept `content`, and whichever
     * write finishes last silently discards the other's link (lost update) even though the UI
     * already shows both chips as accepted. Never acquired by [save]/[performSave] — those must
     * stay lock-free (a prior repair pass fixed a save()-blocking regression; re-locking save()
     * here would reintroduce it).
     */
    private val postSaveWriteMutex = Mutex()

    /**
     * Tracks the in-flight LLM enrichment call so a new debounced scan (Fix 5, MAJOR) cancels
     * any enrichment still running for a superseded text — `scope.launch { coordinator.enhance
     * (...) }` is not a structural child of the `collectLatest` body it's launched from, so
     * collectLatest superseding its current iteration does NOT cancel an already-launched
     * enrichment call on its own.
     */
    private var enrichJob: Job? = null

    /**
     * One-shot event stream for a failed chip accept (pre-save stub-page write, post-save
     * graph-identity mismatch, `ClosedSendChannelException`, block-write failure, or markdown-
     * flush failure) — a [SharedFlow], not a [StateFlow], so the same message is never re-fired
     * on recomposition/config change (Story 4.1.3).
     */
    private val _chipFailure = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val chipFailure: SharedFlow<String> = _chipFailure.asSharedFlow()

    init {
        scope.launch { observeDestination() }
        scope.launch {
            captureText
                .debounce(300)
                .collectLatest { text ->
                    try {
                        if (text.isBlank()) {
                            _scanState.value = ScanState.NotReady
                            return@collectLatest
                        }
                        val coordinator = getApplication<SteleKitApplication>().graphManager
                            ?.getOrCreateEnrichmentCoordinator() ?: run {
                            _scanState.value = ScanState.NotReady
                            return@collectLatest
                        }
                        val (_, freeText) = splitImagePrefix(text)
                        if (freeText.isBlank()) {
                            // Bare image share, no caption — never scan the raw file-path prefix.
                            _scanState.value = ScanState.NotReady
                            return@collectLatest
                        }
                        when (val outcome = coordinator.scan(freeText)) {
                            is ScanOutcome.Success -> {
                                _scanState.value = ScanState.Ready(text, outcome.result, outcome.confirmFirstNames)
                                if (coordinator.supportsEnrichment) {
                                    val textHash = text.hashCode()
                                    val localSuggestions = outcome.result.topicSuggestions
                                    // Fix 5: cancel any enrichment call still running for a
                                    // superseded text — collectLatest's own cancellation doesn't
                                    // reach this launch since it's not a structural child.
                                    enrichJob?.cancel()
                                    enrichJob = scope.launch {
                                        val enriched = coordinator.enhance(freeText, localSuggestions)
                                        // Discard-if-stale-by-hash (mirrors ImportViewModel.kt:244,250).
                                        if (_captureText.value.hashCode() != textHash) return@launch
                                        val current = _scanState.value
                                        if (current is ScanState.Ready && current.text == text) {
                                            _scanState.value = current.copy(
                                                result = current.result.copy(
                                                    topicSuggestions = mergeBySource(
                                                        current.result.topicSuggestions,
                                                        enriched,
                                                        current.confirmFirstNames,
                                                    ),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            ScanOutcome.MatcherNotReady -> _scanState.value = ScanState.NotReady
                            ScanOutcome.TimedOut -> {
                                logger.debug("Scan budget exceeded for ${text.length} chars")
                                _scanState.value = ScanState.NotReady
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Degrade this scan attempt only — the collector must stay alive for
                        // the next text change (PF-1). A CoroutineExceptionHandler on `scope`
                        // alone would not do this: it only fires after collectLatest has died.
                        logger.warn(
                            "Scan attempt failed — degrading to NotReady: ${e::class.simpleName}: ${e.message}",
                        )
                        _scanState.value = ScanState.NotReady
                    }
                }
        }
    }

    fun updateText(text: String) {
        _captureText.value = text
    }

    /** Sets the initial text only if the field is still empty (idempotent for singleTop re-launch). */
    fun initializeText(text: String) {
        if (_captureText.value.isEmpty() && text.isNotEmpty()) {
            _captureText.value = text
        }
    }

    /**
     * Splits a composite `captureText` of the form `"[image: <path>]\n<freeText>"` (the shape
     * `CaptureActivity` produces) into the image prefix (including its trailing `\n`, or `null`
     * if absent) and the remaining free text to scan/save. Anchored `(?:\n|$)` so a bare-image
     * share with no caption — where `.trim()` upstream strips the trailing `\n` — still matches
     * on end-of-string.
     */
    private fun splitImagePrefix(text: String): Pair<String?, String> {
        val match = IMAGE_PREFIX_REGEX.find(text) ?: return null to text
        return match.value to text.removePrefix(match.value)
    }

    /**
     * Non-destructively merges LLM-enriched suggestions onto the local set. Compares terms
     * normalized (trimmed, lowercased) so e.g. local "Kotlin" suppresses an AI-sourced
     * "kotlin " duplicate. Never clears/replaces the local suggestions.
     *
     * Also excludes any enriched suggestion whose normalized term matches [excludedTerms] (the
     * current `confirmFirstNames` — Fix 4, CRITICAL): the local scan already filters against
     * `confirmFirstNames` via `ImportService.scan`/`TopicExtractor.extract`'s `existingNames`
     * param, but the async LLM enrichment path bypassed that check entirely. An enriched term
     * colliding with a pending confirm-first chip's term would otherwise put two
     * `CaptureChipItem`s with the identical `term` into `CaptureActivity`'s
     * `LazyRow(items(pendingChips, key = { it.term }))`, crashing on the duplicate key.
     */
    private fun mergeBySource(
        local: List<TopicSuggestion>,
        enriched: List<TopicSuggestion>,
        excludedTerms: List<String>,
    ): List<TopicSuggestion> {
        val localTermsNormalized = local.map { it.term.trim().lowercase() }.toSet()
        val excludedTermsNormalized = excludedTerms.map { it.trim().lowercase() }.toSet()
        return local + enriched.filter {
            val normalized = it.term.trim().lowercase()
            normalized !in localTermsNormalized && normalized !in excludedTermsNormalized
        }
    }

    /** Text to persist: the linked preview when its scan is current, else the raw trimmed text. */
    private fun textToSave(): String {
        val current = _scanState.value
        // Plain atomic StateFlow read, no lock (Story 2.3.1b): save() must stay lock-free.
        return if (current is ScanState.Ready && current.text == _captureText.value) {
            val (imagePrefix, _) = splitImagePrefix(current.text)
            ((imagePrefix ?: "") + current.result.linkedText).trim()
        } else {
            _captureText.value.trim()
        }
    }

    /** A target that isn't the open graph never gets link suggestions, so it saves the raw text. */
    private fun textFor(dest: CaptureDestination): String =
        if (dest is CaptureDestination.Ready && !dest.isActive) _captureText.value.trim() else textToSave()

    fun save() {
        val dest = _destination.value
        val text = textFor(dest)
        if (text.isEmpty()) return

        val steleApp = getApplication<SteleKitApplication>()
        val graphManager = steleApp.graphManager ?: run {
            _saveState.value = SaveState.Error(IllegalStateException("No graph configured"))
            return
        }

        // Set before the launch so a caller polling for "not Saving" never sees a stale Idle.
        _saveState.value = SaveState.Saving
        // Application scope: the write must outlive this ViewModel if the overlay is closed mid-save.
        steleApp.appScope.launch {
            try {
                applyResult(persist(steleApp, graphManager, dest, text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _saveState.value = SaveState.Error(e)
            }
        }
    }

    private sealed interface ShareResult {
        data class Added(val graph: GraphChoice?, val context: SavedCaptureContext?, val text: String) : ShareResult
        data class Already(val graph: GraphChoice?) : ShareResult
        data class Queued(val graph: GraphChoice, val reason: String) : ShareResult
        data class Failed(val cause: Throwable) : ShareResult
    }

    private fun applyResult(result: ShareResult) {
        when (result) {
            is ShareResult.Added -> {
                recordLastUsedUnlessOverride(result.graph)
                result.context?.let { savedContext = it }
                _resultNote.value = result.graph?.let { "Added to ${it.label}" }
                _saveState.value = SaveState.Saved
            }
            is ShareResult.Already -> {
                _resultNote.value = "Already added"
                _saveState.value = SaveState.Saved
            }
            is ShareResult.Queued -> {
                _resultNote.value = "Queued for ${result.graph.label}"
                _saveState.value = SaveState.Queued(result.graph.label)
            }
            is ShareResult.Failed -> _saveState.value = SaveState.Error(result.cause)
        }
    }

    /** `capture_last_graph_id` follows successful saves; a Direct Share override alone leaves it alone (S14). */
    private fun recordLastUsedUnlessOverride(graph: GraphChoice?) {
        if (graph == null || (overrideGraphId != null && explicitChoice == null)) return
        steleApp?.captureTargetSettings()?.recordLastUsed(graph.id)
    }

    private suspend fun persist(
        app: SteleKitApplication,
        graphManager: GraphManager,
        dest: CaptureDestination,
        text: String,
    ): ShareResult {
        val ready = dest as? CaptureDestination.Ready
        if (dest is CaptureDestination.Unavailable) return ShareResult.Failed(IllegalStateException(dest.reason))
        if (ready != null && !ready.isActive) return persistOffGraph(app, ready, text)
        return persistToOpenGraph(app, graphManager, ready?.graph, text)
    }

    private suspend fun persistToOpenGraph(
        app: SteleKitApplication,
        graphManager: GraphManager,
        graph: GraphChoice?,
        text: String,
    ): ShareResult {
        val content = loadContent(text, trustedImagePath).getOrElse { return ShareResult.Failed(IllegalStateException(it)) }
        if (content.image != null) {
            val id = graph?.id ?: graphManager.getActiveGraphId()
                ?: return ShareResult.Failed(IllegalStateException("No active graph"))
            return persistContent(app, id, graph, content, text)
        }
        // Bug 1/8 mitigations (graph-switch race, markdown flush) live in CaptureWriter.
        // Autosave is started on the writer, which is then kept for post-save chip writes.
        val outcome = JournalAppender(graphManager, app.fileSystem)
            .append(CaptureTarget.ActiveGraph, text, currentCaptureId) { repoSet ->
                GraphWriter(app.fileSystem, writeActor = repoSet.writeActor).also { it.startAutoSave(viewModelScope) }
            }
        return when (outcome) {
            is AppendOutcome.Appended -> ShareResult.Added(graph, outcome.toContext(), text)
            AppendOutcome.AlreadyPresent -> ShareResult.Already(graph)
            is AppendOutcome.Failed -> ShareResult.Failed(
                IllegalStateException(
                    if (outcome.cause == CaptureResult.NoActiveGraph) "No active graph — open SteleKit to set up your graph"
                    else outcome.error,
                ),
            )
            else -> ShareResult.Failed(IllegalStateException("Unexpected append outcome: $outcome"))
        }
    }

    private suspend fun persistOffGraph(app: SteleKitApplication, ready: CaptureDestination.Ready, text: String): ShareResult {
        val content = loadContent(text, trustedImagePath).getOrElse { return ShareResult.Failed(IllegalStateException(it)) }
        return persistContent(app, ready.graph.id, ready.graph, content, text)
    }

    /** Appends [content] (image included) through the share pipeline's router, for any target graph. */
    private suspend fun persistContent(
        app: SteleKitApplication,
        graphId: GraphId,
        graph: GraphChoice?,
        content: ShareContent,
        text: String,
    ): ShareResult {
        val services = app.shareServices()
            ?: return ShareResult.Failed(IllegalStateException("Saving to a graph that isn't open isn't available here"))
        val label = graph ?: GraphChoice(graphId, "Active")
        return when (val outcome = services.appender.appendContent(CaptureTarget.NamedGraph(graphId), content, currentCaptureId)) {
            is AppendOutcome.AppendedOffGraph -> ShareResult.Added(graph, null, text)
            is AppendOutcome.Appended -> ShareResult.Added(graph, outcome.toContext(), text)
            AppendOutcome.AlreadyPresent -> ShareResult.Already(graph)
            is AppendOutcome.Queued -> ShareResult.Queued(label, outcome.reason)
            is AppendOutcome.Deferred -> ShareResult.Failed(IllegalStateException("Couldn't save: ${outcome.reason}"))
            is AppendOutcome.Failed -> ShareResult.Failed(IllegalStateException(outcome.error))
        }
    }

    private fun AppendOutcome.Appended.toContext() = SavedCaptureContext(
        block = checkNotNull(saved.block),
        page = saved.page,
        blocks = saved.blocks,
        graphPath = graphPath,
        graphId = graphId,
        writer = writer,
        writeActor = repoSet.writeActor,
        pageRepository = repoSet.pageRepository,
        blockRepository = repoSet.blockRepository,
    )

    /**
     * Splits the `[image: path]` prefix into a real image payload so the inbox/off-graph route keeps it. Only the
     * path this share's intake copied ([trusted], see [attachImage]) is ever read: the marker in plain text is
     * just text. A trusted image that has since vanished throws rather than saving the literal marker.
     */
    private fun shareContentFor(text: String, trusted: String?): ShareContent {
        val prefix = IMAGE_PREFIX_REGEX.find(text) ?: return ShareContent(text)
        val path = IMAGE_PATH_REGEX.find(prefix.value)?.groupValues?.get(1) ?: return ShareContent(text)
        if (trusted == null || path != trusted) return ShareContent(text)
        val bytes = try { File(path).readBytes() } catch (_: java.io.IOException) { throw ImageUnavailableException() }
        return ShareContent(text.removePrefix(prefix.value), bytes, "image/jpeg")
    }

    private class ImageUnavailableException : Exception()

    /** Left is a user-facing message. */
    private suspend fun loadContent(text: String, trusted: String?): Either<String, ShareContent> =
        withContext(Dispatchers.IO) {
            try {
                shareContentFor(text, trusted).right()
            } catch (_: ImageUnavailableException) {
                IMAGE_UNAVAILABLE.left()
            }
        }

    // ---- Unavailable / no-graph / Back paths (Tasks 4.2.1d, 4.2.1g, 4.2.1h) ------------------

    /** "Queue for later" from the unavailable state: the text goes to that graph's inbox slot. */
    fun queueForLater() {
        val dest = _destination.value as? CaptureDestination.Unavailable ?: return
        val graphId = dest.graphId ?: return
        val app = steleApp ?: return
        val text = _captureText.value.trim()
        if (text.isEmpty()) return
        _saveState.value = SaveState.Saving
        app.appScope.launch {
            val choice = GraphChoice(graphId, dest.graphName.removeSuffix(" graph"))
            applyResult(
                enqueue(app, InboxSlot.Graph(graphId), text)
                    .fold({ ShareResult.Failed(IllegalStateException(it.message)) }, { ShareResult.Queued(choice, "queued by user") }),
            )
        }
    }

    private suspend fun enqueue(
        app: SteleKitApplication,
        slot: InboxSlot,
        text: String,
        captureId: String = currentCaptureId,
        trustedImage: String? = trustedImagePath,
    ): Either<DomainError, EnqueueOutcome> {
        val inbox = app.shareServices()?.inbox
            ?: return DomainError.FileSystemError.WriteFailed("share-inbox", "share inbox unavailable").left()
        val content = loadContent(text, trustedImage).getOrElse {
            return DomainError.FileSystemError.ReadFailed("share-image", it).left()
        }
        return inbox.enqueue(slot, content, captureId)
    }

    /** Close on the "no graphs configured" placeholder: keeps the text in the unassigned slot (ADR-004). */
    fun closeWithoutGraph() {
        val text = _captureText.value.trim()
        val app = steleApp
        if (text.isEmpty() || app == null) return
        app.appScope.launch {
            _resultNote.value = enqueue(app, InboxSlot.Unassigned, text).fold(
                { NO_GRAPH_SAVE_FAILED },
                { if (it == EnqueueOutcome.AlreadyQueued) "Already added" else UNASSIGNED_SAVED },
            )
        }
    }

    /**
     * Back with text: append to the shown destination (same [captureId], idempotent) and report through
     * [backSave]. A failed or unwritable destination queues the text instead; it is never dropped.
     */
    fun backSave() {
        val app = steleApp ?: return
        if (_captureText.value.isBlank() || _backSave.value != BackSaveState.None) return
        _backSave.value = BackSaveState.Saving
        app.appScope.launch {
            val done = try {
                runBackSave(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Back auto-save failed: ${e::class.simpleName}")
                null
            }
            if (done == null) {
                _backSave.value = BackSaveState.None
                _saveState.value = SaveState.Error(IllegalStateException("Couldn't save or queue this note. Copy the text before closing."))
                if (!hostAttached) toastOnMain("Couldn't save this note. Reopen SteleKit capture to try again.")
            } else {
                _backSave.value = done
                if (!hostAttached) toastOnMain(done.message)
            }
        }
    }

    private suspend fun runBackSave(app: SteleKitApplication): BackSaveState.Done? {
        val graphManager = app.graphManager ?: return queueUnassigned(app)
        return when (val dest = settledDestination()) {
            is CaptureDestination.Ready -> {
                val text = textFor(dest)
                when (val result = persist(app, graphManager, dest, text)) {
                    is ShareResult.Added -> {
                        recordLastUsedUnlessOverride(dest.graph)
                        result.context?.let { savedContext = it }
                        val record = recordRecent(app, dest.graph, text)
                        BackSaveState.Done("Saved to ${dest.graph.label}", record, queued = false)
                    }
                    is ShareResult.Already -> BackSaveState.Done("Already added to ${dest.graph.label}", null, queued = false)
                    is ShareResult.Queued -> queuedDone(dest.graph.label)
                    is ShareResult.Failed -> queueAfterFailure(app, dest.graph.id, dest.graph.label, text)
                }
            }
            is CaptureDestination.Unavailable ->
                dest.graphId?.let { queueAfterFailure(app, it, dest.graphName, _captureText.value.trim()) } ?: queueUnassigned(app)
            CaptureDestination.NoGraphs -> enqueue(app, InboxSlot.Unassigned, _captureText.value.trim())
                .fold({ null }, { BackSaveState.Done(UNASSIGNED_SAVED, null, queued = true) })
            CaptureDestination.Legacy, CaptureDestination.Checking -> queueUnassigned(app)
        }
    }

    /** No resolvable graph: park the text in the unassigned slot rather than lose it (ADR-004). */
    private suspend fun queueUnassigned(app: SteleKitApplication): BackSaveState.Done? =
        enqueue(app, InboxSlot.Unassigned, _captureText.value.trim())
            .fold({ null }, { BackSaveState.Done(QUEUED_NO_GRAPH, null, queued = true) })

    private suspend fun queueAfterFailure(app: SteleKitApplication, id: GraphId, label: String, text: String): BackSaveState.Done? =
        enqueue(app, InboxSlot.Graph(id), text).fold({ null }, { queuedDone(label) })

    private fun queuedDone(label: String) =
        BackSaveState.Done("Couldn't save to $label. Queued for $label.", null, queued = true)

    private fun recordRecent(app: SteleKitApplication, graph: GraphChoice, text: String): RecentCapture {
        val record = RecentCapture(
            captureId = currentCaptureId,
            graphId = graph.id.value,
            graphName = graph.label,
            atMs = Clock.System.now().toEpochMilliseconds(),
            text = text,
            journalPage = OffGraphCapture.todayJournalPageName(),
        )
        app.recentCaptures.add(record)
        return record
    }

    /** Toast Undo: removes the just-added block, then reports success on the main thread. */
    fun undoBackSave(onResult: (Boolean) -> Unit) {
        val app = steleApp ?: return
        val record = (_backSave.value as? BackSaveState.Done)?.record ?: return
        app.appScope.launch {
            val ok = app.undoCapture(record)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    private fun toastOnMain(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(getApplication<Application>(), message, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Epic 4.1: pre-save chip accept/dismiss --------------------------------------------

    /**
     * Folds `[[term]]` into the pending [_scanState] synchronously (no suspension point between
     * the tap and this fold — Story 4.1.1/4.1.4), then creates the stub page (pre-save) or
     * performs the second write (post-save) asynchronously in its own [scope.launch].
     */
    fun acceptSuggestion(term: String) {
        // Synchronous, immediate — no suspension point between the tap and this fold. Matches
        // ImportViewModel.onSuggestionAccepted()'s precedent exactly: the state update that
        // determines what save() will persist must never wait on I/O.
        markAccepted(term)
        // Only used to pick pre-save vs. post-save branch at tap time — the post-save branch
        // re-reads the live savedContext itself, under postSaveWriteMutex (Fix 2: a captured
        // snapshot here would let two concurrent post-save accepts race on stale content).
        val isPostSave = savedContext != null
        scope.launch {
            if (!isPostSave) {
                createStubPage(term) // pre-save: async, unguarded, no mutex with save()
            } else {
                acceptSuggestionPostSave(term) // post-save, Epic 4.2
            }
        }
    }

    private suspend fun createStubPage(term: String) {
        val steleApp = getApplication<SteleKitApplication>()
        val graphManager = steleApp.graphManager ?: return
        val repoSet = graphManager.getActiveRepositorySet() ?: return
        val graphPath = graphManager.getActiveGraphInfo()?.path ?: return
        val writer = GraphWriter(steleApp.fileSystem, writeActor = repoSet.writeActor)
        // Deliberately not reverting markAccepted()'s fold on failure — see Story 4.1.1's AC: the
        // link stays; only the stub page failed to materialize.
        ensureStubPage(repoSet.pageRepository, writer, graphPath, term)
    }

    /**
     * Shared by the pre-save ([createStubPage]) and post-save ([acceptSuggestionPostSave]) accept
     * paths: creates a stub [Page] for [term] if one doesn't already exist. Returns `true` if a
     * page exists (pre-existing or newly created), `false` if creation failed — already logged
     * and reported via [_chipFailure]; the caller decides what to do next.
     */
    private suspend fun ensureStubPage(
        pageRepository: PageRepository,
        writer: GraphWriter,
        graphPath: String,
        term: String,
    ): Boolean {
        val existing = pageRepository.getPageByName(term).first().getOrNull()
        if (existing != null) return true // already exists — markAccepted() already folded the link
        val stubPage = Page(
            uuid = PageUuid(UuidGenerator.generateV7()),
            name = term,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        return writer.savePage(stubPage, emptyList(), graphPath).fold(
            { error ->
                logger.error("Stub page save failed for '$term': $error")
                _chipFailure.tryEmit("Couldn't create page for \"$term\"")
                false
            },
            { true },
        )
    }

    /**
     * Folds `[[term]]` into the pending [_scanState], synchronously, no suspension point.
     * Also clears [term] from `confirmFirstNames` (a confirm-first chip accept has no
     * [TopicSuggestion] entry to mark — `- term` on a list that doesn't contain it is a no-op,
     * so this is safe to call unconditionally for either chip kind).
     */
    private fun markAccepted(term: String) {
        _scanState.update { state ->
            if (state !is ScanState.Ready) return@update state
            val updatedSuggestions = state.result.topicSuggestions.map {
                if (it.term == term) it.copy(accepted = true) else it
            }
            state.copy(
                result = state.result.copy(
                    linkedText = ImportService.insertWikiLinks(state.result.linkedText, listOf(term)),
                    topicSuggestions = updatedSuggestions,
                ),
                confirmFirstNames = state.confirmFirstNames - term,
            )
        }
    }

    /**
     * Confirm-first chip accept (Story 4.1.4, pre-mortem.md P1 #2): folds `[[term]]` the same
     * way [acceptSuggestion] does, but never creates a stub page — the page already exists,
     * which is exactly why the coordinator put it in `confirmFirstNames` rather than the
     * new-page `topicSuggestions` bucket.
     */
    fun acceptExistingLink(term: String) {
        markAccepted(term) // synchronous fold — same helper acceptSuggestion() uses
        if (savedContext == null) return // pre-save: the fold above was the whole job
        scope.launch { acceptExistingLinkPostSave(term) } // Epic 4.2
    }

    /** Confirm-first sibling of [dismissSuggestion] — no coroutine/write involved. */
    fun dismissExistingLinkSuggestion(term: String) {
        _scanState.update { state ->
            if (state !is ScanState.Ready) return@update state
            state.copy(confirmFirstNames = state.confirmFirstNames - term)
        }
    }

    /** Dismisses a suggestion chip — synchronous, no coroutine/write involved. */
    fun dismissSuggestion(term: String) {
        _scanState.update { state ->
            if (state !is ScanState.Ready) return@update state
            state.copy(
                result = state.result.copy(
                    topicSuggestions = state.result.topicSuggestions.map {
                        if (it.term == term) it.copy(dismissed = true) else it
                    },
                ),
            )
        }
    }

    // ---- Epic 4.2: post-save write-back ------------------------------------------------------

    /**
     * Shared graph-identity guard (Blocker #1 fix): compares [ctx]'s captured [GraphId] against
     * the currently-active graph, short-circuiting before any repository/writer call on a
     * mismatch — [ctx]'s graph may no longer be the active one by the time a post-save chip is
     * tapped (ADR-002's scope boundary).
     */
    private fun graphStillActive(ctx: SavedCaptureContext, term: String): Boolean {
        val graphManager = getApplication<SteleKitApplication>().graphManager
        if (graphManager?.getActiveGraphId() == ctx.graphId) return true
        logger.error("Suggestion '$term' not applied — active graph changed since save")
        _chipFailure.tryEmit("Couldn't link \"$term\" — the graph changed")
        return false
    }

    /**
     * Post-save new-page chip accept: re-checks stub-page existence through [savedContext]'s
     * captured [PageRepository] (never a freshly-fetched "active" repository set — FM-5),
     * creates the stub if needed, then performs the shared second write.
     *
     * The whole read-modify-write cycle — reading [savedContext], the stub check/creation, and
     * the second write — runs under [postSaveWriteMutex] (Fix 2, BLOCKER): [savedContext] is
     * read fresh *inside* the lock, never trusted from a snapshot captured before the lock was
     * acquired, so a second chip accept tapped while this one is still in flight serializes
     * behind it and builds its updated block from the *result* of this write, not a stale copy —
     * otherwise whichever write finished last would silently discard the other's link.
     */
    private suspend fun acceptSuggestionPostSave(term: String) = postSaveWriteMutex.withLock {
        val ctx = savedContext ?: return@withLock
        if (!graphStillActive(ctx, term)) return@withLock
        if (!ensureStubPage(ctx.pageRepository, ctx.writer, ctx.graphPath, term)) return@withLock
        writeLinkedBlockPostSave(ctx, term)
    }

    /**
     * Post-save confirm-first chip accept: skips the stub-existence-check/creation entirely —
     * the coordinator's `scan()` already confirmed the page exists before ever putting [term]
     * in `confirmFirstNames`, so re-verifying or re-creating it here would be redundant, not
     * defensive. Same [postSaveWriteMutex]-guarded fresh-read-of-[savedContext] discipline as
     * [acceptSuggestionPostSave] — see its doc for why.
     */
    private suspend fun acceptExistingLinkPostSave(term: String) = postSaveWriteMutex.withLock {
        val ctx = savedContext ?: return@withLock
        if (!graphStillActive(ctx, term)) return@withLock
        writeLinkedBlockPostSave(ctx, term)
    }

    /**
     * Shared second-write machinery for both post-save accept paths: rewrites the already-saved
     * block's content with `[[term]]` inserted, persists it through [ctx]'s captured
     * `writeActor`/`blockRepository` (never a freshly-fetched "active" repo set), flushes the
     * markdown file via [ctx]'s captured [GraphWriter], and updates [savedContext] so a second
     * chip accept in the same window still works against the latest block/blocks snapshot.
     *
     * Callers only ([acceptSuggestionPostSave]/[acceptExistingLinkPostSave]) — always invoked
     * with [ctx] read fresh under [postSaveWriteMutex], never a stale tap-time snapshot.
     */
    private suspend fun writeLinkedBlockPostSave(ctx: SavedCaptureContext, term: String) {
        val updatedBlock = ctx.block.copy(
            content = ImportService.insertWikiLinks(ctx.block.content, listOf(term)),
            updatedAt = Clock.System.now(),
        )
        val writeResult = try {
            if (ctx.writeActor != null) {
                ctx.writeActor.saveBlock(updatedBlock)
            } else {
                @OptIn(DirectRepositoryWrite::class)
                ctx.blockRepository.saveBlock(updatedBlock)
            }
        } catch (e: ClosedSendChannelException) {
            logger.error("Suggestion '$term' not applied — graph changed during post-save write")
            _chipFailure.tryEmit("Couldn't link \"$term\" — the graph changed")
            return
        }
        writeResult.onLeft {
            logger.error("Post-save block write failed for '$term': $it")
            _chipFailure.tryEmit("Couldn't link \"$term\"")
            return
        }

        val updatedBlocks = ctx.blocks.map { if (it.uuid == updatedBlock.uuid) updatedBlock else it }
        ctx.writer.savePage(ctx.page, updatedBlocks, ctx.graphPath).onLeft {
            logger.error("Post-save markdown flush failed for '$term': $it")
            _chipFailure.tryEmit("Couldn't link \"$term\"")
            return
        }
        savedContext = ctx.copy(block = updatedBlock, blocks = updatedBlocks)
        markAccepted(term)
    }

    companion object {
        private val IMAGE_PREFIX_REGEX = Regex("""^\[image: .*?](?:\n|$)""")
        private val IMAGE_PATH_REGEX = Regex("""^\[image: (.*?)]""")
        /** Shown when the unassigned-slot enqueue fails; the placeholder then stays open with Copy text. */
        internal const val NO_GRAPH_SAVE_FAILED = "Couldn't save this share. Copy the text before closing."
        private const val IMAGE_UNAVAILABLE = "The shared image is no longer available. Remove it from the note or share it again."
        private const val QUEUED_NO_GRAPH = "Couldn't pick a graph. Queued to add later."
        private const val UNASSIGNED_SAVED = "Saved. It will be added to the first graph you create."
    }
}
