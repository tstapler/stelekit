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
import dev.stapler.stelekit.domain.NoOpTopicEnricher
import dev.stapler.stelekit.domain.ScanOutcome
import dev.stapler.stelekit.domain.ScanResult
import dev.stapler.stelekit.domain.TopicSuggestion
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
                                if (coordinator.topicEnricher !is NoOpTopicEnricher) {
                                    val textHash = text.hashCode()
                                    val localSuggestions = outcome.result.topicSuggestions
                                    scope.launch {
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
     */
    private fun mergeBySource(
        local: List<TopicSuggestion>,
        enriched: List<TopicSuggestion>,
    ): List<TopicSuggestion> {
        val localTermsNormalized = local.map { it.term.trim().lowercase() }.toSet()
        return local + enriched.filter { it.term.trim().lowercase() !in localTermsNormalized }
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

    companion object {
        private val IMAGE_PREFIX_REGEX = Regex("""^\[image: .*?](?:\n|$)""")
    }
}
