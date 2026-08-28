// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.getOrElse
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
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.FractionalIndexing
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
        data class Error(val throwable: Throwable?) : SaveState()
    }

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
     * Snapshot of what a successful [save] just wrote, retained so a later post-save chip
     * accept (Epic 4.2) can write through the exact same graph/repositories instead of
     * re-resolving "the active graph" (which may have changed by then — ADR-002's scope
     * boundary).
     *
     * Note: [writer]/[writeActor]/[pageRepository]/[blockRepository] don't override
     * `equals`/`hashCode`, so the generated `equals()` falls back to reference equality on those
     * four fields — this class does not have full structural equality despite being a data class.
     */
    private data class SavedCaptureContext(
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
    private var savedContext: SavedCaptureContext? = null

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

    fun save() {
        // Read _scanState.value as a plain, atomic StateFlow read — no lock needed. save() and
        // any prior synchronous chip-accept fold run on the same single dispatcher, so there is
        // no suspension window in which this read can race a concurrent fold (Story 2.3.1b).
        val current = _scanState.value
        val text = if (current is ScanState.Ready && current.text == _captureText.value) {
            val (imagePrefix, _) = splitImagePrefix(current.text)
            ((imagePrefix ?: "") + current.result.linkedText).trim()
        } else {
            _captureText.value.trim()
        }
        if (text.isEmpty()) return

        val steleApp = getApplication<SteleKitApplication>()
        val graphManager = steleApp.graphManager ?: run {
            _saveState.value = SaveState.Error(IllegalStateException("No graph configured"))
            return
        }

        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            val result = performSave(graphManager, steleApp.fileSystem, text)
            result.getOrNull()?.let { savedContext = it }
            _saveState.value = if (result.isSuccess) SaveState.Saved
                                else SaveState.Error(result.exceptionOrNull())
        }
    }

    private suspend fun performSave(
        graphManager: GraphManager,
        fileSystem: PlatformFileSystem,
        text: String,
    ): Result<SavedCaptureContext> = runCatching {
        val repoSet = graphManager.getActiveRepositorySet()
            ?: error("No active graph — open SteleKit to set up your graph")

        val page = repoSet.journalService.ensureTodayJournal()
        val graphPath = graphManager.getActiveGraphInfo()?.path
            ?: error("No active graph path")

        val existingBlocks = repoSet.blockRepository
            .getBlocksForPage(page.uuid)
            .first()
            .getOrElse { error("Failed to load blocks: $it") }

        val now = Clock.System.now()
        val newBlock = Block(
            uuid = BlockUuid(UuidGenerator.generateV7()),
            pageUuid = page.uuid,
            content = text,
            position = FractionalIndexing.generateKeyBetween(
                existingBlocks.maxByOrNull { it.position }?.position, null
            ),
            createdAt = now,
            updatedAt = now,
        )

        // Bug 1 mitigation: catch ClosedSendChannelException from a graph-switch race
        val writeActor = repoSet.writeActor
        if (writeActor != null) {
            try {
                writeActor.saveBlock(newBlock).getOrElse { error("Save failed: $it") }
            } catch (e: ClosedSendChannelException) {
                throw IllegalStateException("Graph switched during save — please retry", e)
            }
        } else {
            @OptIn(DirectRepositoryWrite::class)
            repoSet.blockRepository.saveBlock(newBlock).getOrElse { error("Save failed: $it") }
        }

        // Bug 8 mitigation: flush the Markdown file after every actor write.
        // Pass writeActor so GraphWriter can persist filePath for newly created journal pages.
        val writer = GraphWriter(fileSystem, writeActor = repoSet.writeActor)
        writer.startAutoSave(viewModelScope)
        writer.savePage(page, existingBlocks + newBlock, graphPath).getOrElse { error("Save failed: $it") }

        SavedCaptureContext(
            block = newBlock,
            page = page,
            blocks = existingBlocks + newBlock,
            graphPath = graphPath,
            graphId = graphManager.getActiveGraphId() ?: error("no active graph"),
            writer = writer,
            writeActor = repoSet.writeActor,
            pageRepository = repoSet.pageRepository,
            blockRepository = repoSet.blockRepository,
        )
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
     * page exists (pre-existing or newly created), `false` if creation failed — in which case the
     * failure is already logged and reported via [_chipFailure]; the caller decides what to do
     * next (the pre-save path does nothing further, the post-save path aborts before the second
     * write).
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
    }
}
