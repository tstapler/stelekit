// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import com.fleeksoft.ksoup.Ksoup
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.domain.FetchResult
import dev.stapler.stelekit.domain.HtmlBlockConverter
import dev.stapler.stelekit.domain.ImportService
import dev.stapler.stelekit.domain.NoOpTopicEnricher
import dev.stapler.stelekit.domain.TopicEnricher
import dev.stapler.stelekit.domain.TopicSuggestion
import dev.stapler.stelekit.domain.UrlFetcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.Validation
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock

enum class ImportTab { PASTE, URL }

/** Status of an optional Claude enrichment call. */
sealed interface ClaudeStatus {
    data object Idle : ClaudeStatus
    data object Loading : ClaudeStatus
    data object Done : ClaudeStatus
    sealed interface Failed : ClaudeStatus {
        data object Timeout : Failed
        data object RateLimited : Failed
        data object NetworkError : Failed
        data class ApiError(val code: Int) : Failed
        data object MalformedResponse : Failed
    }
}

data class ImportState(
    val rawText: String = "",
    val rawHtml: String? = null,
    val urlInput: String = "",
    val pageName: String = "",
    val linkedText: String = "",
    val matchedPageNames: List<String> = emptyList(),
    val isScanning: Boolean = false,
    val isSaving: Boolean = false,
    val isFetching: Boolean = false,
    val fetchError: FetchResult.Failure? = null,
    val pageNameError: String? = null,
    val activeTab: ImportTab = ImportTab.PASTE,
    val savedPageName: String? = null,
    // Suggestion tray state
    val topicSuggestions: List<TopicSuggestion> = emptyList(),
    val isEnhancing: Boolean = false,
    val claudeStatus: ClaudeStatus = ClaudeStatus.Idle,
    // Undo buffer (cleared after confirm or navigation away)
    val undoBuffer: List<Page> = emptyList(),
    val showUndoSnackbar: Boolean = false,
    val undoLinkedText: String = "",
)

/**
 * Functional interface for persisting a page and its blocks.
 * Exists to allow the [ImportViewModel] to be tested without a real [GraphWriter].
 */
fun interface PageSaver {
    suspend fun save(page: Page, blocks: List<Block>, graphPath: String)

    companion object {
        fun from(writer: GraphWriter): PageSaver = PageSaver { page, blocks, path ->
            writer.savePage(page, blocks, path)
        }
    }
}

/**
 * Functional interface for deleting a page.
 * Used by the undo path to remove stub pages created during import.
 */
fun interface PageDeleter {
    suspend fun delete(page: Page): Boolean

    companion object {
        fun from(writer: GraphWriter): PageDeleter = PageDeleter { page -> writer.deletePage(page) }
        val NoOp: PageDeleter = PageDeleter { _ -> false }
    }
}

class ImportViewModel(
    private val coroutineScope: CoroutineScope,
    private val pageRepository: PageRepository,
    private val pageSaver: PageSaver,
    private val graphPath: String,
    private val urlFetcher: UrlFetcher,
    private val matcherFlow: StateFlow<AhoCorasickMatcher?>,
    private val topicEnricher: TopicEnricher = NoOpTopicEnricher(),
    private val pageDeleter: PageDeleter = PageDeleter.NoOp,
    /** Dispatcher used for CPU-bound scan work. Override in tests to avoid real threads. */
    private val scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Secondary constructor that accepts a [GraphWriter] for production use.
     *
     * [pageDeleter] is always wired from [graphWriter] here so stub-page undo works
     * in production (addresses the PageDeleter-not-exposed bug noted in the plan).
     */
    constructor(
        coroutineScope: CoroutineScope,
        pageRepository: PageRepository,
        graphWriter: GraphWriter,
        graphPath: String,
        urlFetcher: UrlFetcher,
        matcherFlow: StateFlow<AhoCorasickMatcher?>,
        topicEnricher: TopicEnricher = NoOpTopicEnricher(),
    ) : this(
        coroutineScope = coroutineScope,
        pageRepository = pageRepository,
        pageSaver = PageSaver.from(graphWriter),
        graphPath = graphPath,
        urlFetcher = urlFetcher,
        matcherFlow = matcherFlow,
        topicEnricher = topicEnricher,
        pageDeleter = PageDeleter.from(graphWriter),
    )

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        // When the matcher becomes available and rawText is non-blank, re-run the scan
        coroutineScope.launch {
            matcherFlow.collect { matcher ->
                if (matcher != null && _state.value.rawText.isNotBlank()) {
                    runScan(_state.value.rawText, matcher)
                }
            }
        }
    }

    fun onRawTextChanged(text: String) {
        _state.update { it.copy(rawText = text) }

        scanJob?.cancel()

        if (text.isBlank()) {
            _state.update { it.copy(linkedText = "", matchedPageNames = emptyList(), isScanning = false) }
            return
        }

        _state.update { it.copy(isScanning = true) }

        scanJob = coroutineScope.launch {
            delay(300)
            val matcher = matcherFlow.value
            if (matcher == null) {
                _state.update { it.copy(isScanning = false) }
                return@launch
            }
            runScan(text, matcher)
        }
    }

    private suspend fun runScan(text: String, matcher: AhoCorasickMatcher) {
        // Get existing page names so suggestions don't duplicate known pages
        val existingNames = pageRepository.getAllPages()
            .first()
            .getOrNull()
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val oldSuggestions = _state.value.topicSuggestions
        // Capture dismissed terms NOW (before the state update resets the list).
        // The enrichment coroutine uses this to prevent re-surfacing dismissed items
        // even when the new scan produces an empty suggestion list.
        val previouslyDismissed = oldSuggestions
            .filter { it.dismissed }
            .map { it.term }
            .toSet()

        val result = withContext(scanDispatcher) {
            ImportService.scan(text, matcher, existingNames)
        }

        // Re-merge accepted/dismissed flags from the previous scan result
        val mergedSuggestions = reapplyAcceptedState(result.topicSuggestions, oldSuggestions)

        // Re-insert wiki links for any already-accepted terms
        val acceptedTerms = mergedSuggestions.filter { it.accepted }.map { it.term }
        val finalLinkedText = if (acceptedTerms.isNotEmpty()) {
            ImportService.insertWikiLinks(result.linkedText, acceptedTerms)
        } else {
            result.linkedText
        }

        _state.update {
            it.copy(
                linkedText = finalLinkedText,
                matchedPageNames = result.matchedPageNames,
                topicSuggestions = mergedSuggestions,
                isScanning = false,
                isEnhancing = topicEnricher !is NoOpTopicEnricher,
                claudeStatus = if (topicEnricher !is NoOpTopicEnricher) ClaudeStatus.Loading else ClaudeStatus.Idle,
            )
        }

        // Coroutine 2: async Claude enrichment (fire-and-forget, never blocks review UI)
        if (topicEnricher !is NoOpTopicEnricher) {
            val textHash = text.hashCode()
            coroutineScope.launch {
                try {
                    withTimeout(8_000) {
                        val enriched = topicEnricher.enhance(text, _state.value.topicSuggestions)
                        // Discard stale enrichment if rawText changed while we were waiting
                        if (_state.value.rawText.hashCode() != textHash) return@withTimeout
                        val currentSuggestions = _state.value.topicSuggestions
                        val merged = mergeEnrichedSuggestions(currentSuggestions, enriched, previouslyDismissed)
                        _state.update {
                            it.copy(
                                topicSuggestions = merged,
                                isEnhancing = false,
                                claudeStatus = ClaudeStatus.Done,
                            )
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    _state.update { it.copy(isEnhancing = false, claudeStatus = ClaudeStatus.Failed.Timeout) }
                } catch (e: Exception) {
                    _state.update { it.copy(isEnhancing = false, claudeStatus = ClaudeStatus.Failed.NetworkError) }
                }
            }
        }
    }

    fun onHtmlPasted(html: String) {
        val plainText = Ksoup.parse(html).body().text()
        _state.update { it.copy(rawHtml = html) }
        onRawTextChanged(plainText)
    }

    fun onUrlChanged(url: String) {
        _state.update { it.copy(urlInput = url, fetchError = null) }
    }

    fun onPageNameChanged(name: String) {
        _state.update { it.copy(pageName = name, pageNameError = null) }
    }

    fun onTabChanged(tab: ImportTab) {
        _state.update { it.copy(activeTab = tab) }
    }

    suspend fun fetchUrl() {
        _state.update { it.copy(isFetching = true, fetchError = null) }
        try {
            when (val result = urlFetcher.fetch(_state.value.urlInput)) {
                is FetchResult.Success -> {
                    _state.update { it.copy(rawText = result.text, pageName = result.pageTitle ?: "") }
                    onRawTextChanged(result.text)
                }
                is FetchResult.Failure -> {
                    _state.update { it.copy(fetchError = result) }
                }
            }
        } finally {
            _state.update { it.copy(isFetching = false) }
        }
    }

    // -------------------------------------------------------------------------
    // Suggestion handlers
    // -------------------------------------------------------------------------

    fun onSuggestionAccepted(term: String) {
        val snapshot = _state.value.linkedText
        _state.update { state ->
            val updated = state.topicSuggestions.map {
                if (it.term == term) it.copy(accepted = true) else it
            }
            state.copy(
                topicSuggestions = updated,
                linkedText = ImportService.insertWikiLinks(state.linkedText, listOf(term)),
                undoLinkedText = if (state.undoBuffer.isEmpty()) snapshot else state.undoLinkedText,
            )
        }
    }

    fun onSuggestionDismissed(term: String) {
        _state.update { state ->
            state.copy(
                topicSuggestions = state.topicSuggestions.map {
                    if (it.term == term) it.copy(dismissed = true) else it
                },
            )
        }
    }

    fun onAcceptAllSuggestions() {
        // Cap at 10 per bulk-accept gesture
        val toAccept = _state.value.topicSuggestions
            .filter { !it.accepted && !it.dismissed }
            .take(10)
        toAccept.forEach { onSuggestionAccepted(it.term) }
    }

    fun onUndoStubCreation() {
        coroutineScope.launch {
            _state.value.undoBuffer.forEach { page -> pageDeleter.delete(page) }
            _state.update { state ->
                state.copy(
                    topicSuggestions = state.topicSuggestions.map { it.copy(accepted = false) },
                    linkedText = state.undoLinkedText,
                    undoBuffer = emptyList(),
                    showUndoSnackbar = false,
                    undoLinkedText = "",
                )
            }
        }
    }

    /** Clear the undo snackbar without deleting stubs (called when snackbar times out). */
    fun clearUndoSnackbar() {
        _state.update { it.copy(showUndoSnackbar = false) }
    }

    // -------------------------------------------------------------------------
    // Import confirmation
    // -------------------------------------------------------------------------

    suspend fun confirmImport() {
        val currentState = _state.value

        // Validate page name not blank
        if (currentState.pageName.isBlank()) {
            _state.update { it.copy(pageNameError = "Page name is required") }
            return
        }

        // Validate name via Validation
        val normalizedName = try {
            Validation.validateName(currentState.pageName)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(pageNameError = e.message) }
            return
        }

        // URL deduplication: reject if another page was already imported from this URL
        if (currentState.activeTab == ImportTab.URL && currentState.urlInput.isNotBlank()) {
            val allPages = pageRepository.getAllPages().first().getOrNull()
            val duplicatePage = allPages?.firstOrNull { it.properties["source"] == currentState.urlInput }
            if (duplicatePage != null) {
                _state.update { it.copy(pageNameError = "A page from this URL already exists: '${duplicatePage.name}'") }
                return
            }
        }

        // Check for collision
        val existingPage = pageRepository.getPageByName(normalizedName).first().getOrNull()
        if (existingPage != null) {
            _state.update { it.copy(pageNameError = "A page named '$normalizedName' already exists") }
            return
        }

        val now = Clock.System.now()
        val pageUuid = UuidGenerator.generateV7()

        // Create stub pages for accepted suggestions (pre-existence check)
        val acceptedSuggestions = currentState.topicSuggestions.filter { it.accepted }
        val createdStubs = mutableListOf<Page>()
        for (suggestion in acceptedSuggestions) {
            val existingStub = pageRepository.getPageByName(suggestion.term).first().getOrNull()
            if (existingStub == null) {
                val stubPage = Page(
                    uuid = UuidGenerator.generateV7(),
                    name = suggestion.term,
                    createdAt = now,
                    updatedAt = now,
                )
                pageSaver.save(stubPage, emptyList(), graphPath)
                createdStubs.add(stubPage)
            }
        }

        // Apply wiki links for all accepted terms to the final content
        val acceptedTerms = acceptedSuggestions.map { it.term }
        val contentSource = if (currentState.linkedText.isNotBlank()) currentState.linkedText else currentState.rawText
        val finalText = if (acceptedTerms.isEmpty()) contentSource
                        else ImportService.insertWikiLinks(contentSource, acceptedTerms)

        // Determine content to split into blocks
        val htmlBlocks = currentState.rawHtml?.let { HtmlBlockConverter.convert(it) }

        val blocks = if (htmlBlocks != null) {
            htmlBlocks.mapIndexed { index, rawBlock ->
                Block(
                    uuid = UuidGenerator.generateV7(),
                    pageUuid = pageUuid,
                    content = rawBlock.content.trim(),
                    level = rawBlock.level,
                    position = index,
                    createdAt = now,
                    updatedAt = now,
                    blockType = "paragraph",
                )
            }
        } else {
            finalText
                .split("\n\n")
                .filter { it.isNotBlank() }
                .mapIndexed { index, paragraph ->
                    Block(
                        uuid = UuidGenerator.generateV7(),
                        pageUuid = pageUuid,
                        content = paragraph.trim(),
                        level = 0,
                        position = index,
                        createdAt = now,
                        updatedAt = now,
                        blockType = "paragraph",
                    )
                }
        }

        // Build page properties
        val properties = buildMap<String, String> {
            if (currentState.activeTab == ImportTab.URL && currentState.urlInput.isNotBlank()) {
                put("source", currentState.urlInput)
            }
        }

        val page = Page(
            uuid = pageUuid,
            name = normalizedName,
            createdAt = now,
            updatedAt = now,
            properties = properties,
        )

        _state.update { it.copy(isSaving = true) }
        try {
            pageSaver.save(page, blocks, graphPath)

            // Show undo snackbar before setting savedPageName (prevents premature dismiss)
            if (createdStubs.isNotEmpty()) {
                _state.update { state ->
                    state.copy(
                        undoBuffer = createdStubs,
                        showUndoSnackbar = true,
                        undoLinkedText = contentSource,
                    )
                }
            }

            _state.update { it.copy(savedPageName = page.name) }
        } finally {
            _state.update { it.copy(isSaving = false) }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Merges enriched suggestions from Claude with the current suggestion list.
     * - Never re-shows dismissed items (checks both the current list and [previouslyDismissed]
     *   from the scan phase, which may not be in [current] after a re-scan).
     * - Uses Claude confidence scores for items present in both lists.
     * - Appends net-new Claude items without re-sorting (avoid jarring reorder).
     * - Caps result at 15.
     */
    private fun mergeEnrichedSuggestions(
        current: List<TopicSuggestion>,
        enriched: List<TopicSuggestion>,
        previouslyDismissed: Set<String> = emptySet(),
    ): List<TopicSuggestion> {
        val dismissedTerms = (current.filter { it.dismissed }.map { it.term } + previouslyDismissed).toSet()
        val currentByTerm = current.associateBy { it.term }

        val updatedCurrent = current.map { suggestion ->
            val enrichedVersion = enriched.find { it.term == suggestion.term }
            if (enrichedVersion != null) {
                suggestion.copy(confidence = enrichedVersion.confidence)
            } else {
                suggestion
            }
        }

        val netNew = enriched
            .filter { it.term !in currentByTerm && it.term !in dismissedTerms }

        return (updatedCurrent + netNew).take(15)
    }

    /**
     * Re-applies accepted/dismissed flags from [oldSuggestions] onto [newSuggestions]
     * by matching on term. Called after a re-scan so user selections survive text edits.
     */
    private fun reapplyAcceptedState(
        newSuggestions: List<TopicSuggestion>,
        oldSuggestions: List<TopicSuggestion>,
    ): List<TopicSuggestion> {
        val oldByTerm = oldSuggestions.associateBy { it.term }
        return newSuggestions.map { new ->
            val old = oldByTerm[new.term]
            if (old != null) new.copy(accepted = old.accepted, dismissed = old.dismissed) else new
        }
    }
}
