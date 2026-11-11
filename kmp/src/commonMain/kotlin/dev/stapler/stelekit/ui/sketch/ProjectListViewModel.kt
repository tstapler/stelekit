// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.projectbridge.AnalyseToggleDecision
import dev.stapler.stelekit.projectbridge.AttachedSheetRow
import dev.stapler.stelekit.projectbridge.AttachedSheetService
import dev.stapler.stelekit.projectbridge.BindOutcome
import dev.stapler.stelekit.projectbridge.BoundSourceRow
import dev.stapler.stelekit.projectbridge.PickerPage
import dev.stapler.stelekit.projectbridge.SourceBindingKind
import dev.stapler.stelekit.projectbridge.SourceBindingService
import dev.stapler.stelekit.ui.sketchimport.ImportPhase
import dev.stapler.stelekit.projectbridge.AnalyseToggleRules
import dev.stapler.stelekit.projectbridge.OutboundPreview
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.projectbridge.ProjectNaming
import dev.stapler.stelekit.projectbridge.ProjectSlugs
import dev.stapler.stelekit.projectbridge.hasAnyProject
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One list row; [slug] is the opaque id, [name] the device-local display name. */
data class ProjectRow(val uuid: String, val slug: String, val name: String)

data class ProjectListUiState(
    val projects: List<ProjectRow> = emptyList(),
    val hasMore: Boolean = false,
    val digestEnabled: Boolean = false,
    val digestTitle: String = "",
    val analyseAfterSync: Boolean = false,
    /** Only with an explicit, acknowledged `PROJECT_ANALYSIS` provider; otherwise the toggle is hidden. */
    val analyseOffered: Boolean = false,
    /** The exact outbound text awaiting the owner's approval; non-null while the preview dialog is open. */
    val pendingPreview: String? = null,
    val privateTerms: List<String> = emptyList(),
    val pickerResults: List<PickerPage> = emptyList(),
    val boundSources: List<BoundSourceRow> = emptyList(),
    /** True once the project records a YAML snapshot; the file itself is never shown. */
    val yamlSnapshotSet: Boolean = false,
    val attachedSheets: List<AttachedSheetRow> = emptyList(),
    val message: String? = null,
)

/** Serialises a repository write; production passes `DatabaseWriteActor.execute`. */
typealias ProjectWriteRunner = suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>

/**
 * Project list, creation and the per-graph digest settings (Story 5.9a). Owns its scope (never a composition scope) and
 * guards it with a [CoroutineExceptionHandler]. Settings changes are validated here so the composables hold no rules.
 */
class ProjectListViewModel(
    private val projects: SketchProjectRepository,
    private val graphId: String,
    private val settings: ProjectBridgeSettings,
    private val llmSettings: LlmSettings,
    private val write: ProjectWriteRunner,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    private val sources: SourceBindingService? = null,
    private val yaml: YamlPickerAction? = null,
    private val drawings: DrawingAttachAction? = null,
    private val sheets: AttachedSheetService? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> setMessage("Something went wrong (${e::class.simpleName})") },
    )
    private val _state = MutableStateFlow(snapshot(emptyList(), false))
    val state: StateFlow<ProjectListUiState> = _state
    val sourcesAvailable: Boolean = sources != null
    val yamlAvailable: Boolean = yaml != null
    val drawingsAvailable: Boolean = drawings != null
    private val _session = MutableStateFlow<DrawingSession?>(null)
    val importSession: StateFlow<DrawingSession?> = _session

    init {
        scope.launch {
            projects.getProjects(PAGE_SIZE + 1, 0).collect { result ->
                result.onRight { rows ->
                    val shown = rows.take(PAGE_SIZE).map { ProjectRow(it.uuid.value, it.slug, it.name) }
                    _state.update { snapshot(shown, rows.size > PAGE_SIZE, it) }
                    reloadBound()
                }.onLeft { setMessage("Could not load projects") }
            }
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun createProject(rawName: String) {
        val name = ProjectNaming.normalizeName(rawName)
        if (name == null) {
            setMessage("Enter a project name of up to ${ProjectNaming.MAX_NAME} characters")
            return
        }
        scope.launch {
            val taken = _state.value.projects.mapTo(HashSet()) { it.slug }
            val slug = ProjectSlugs.generateUnique({ it in taken }, random)
            val project = ProjectFactory.create(name, slug, ProjectFactory.newUuid(), clock())
            write { projects.saveProject(project) }.onLeft { setMessage("Could not create the project") }
        }
    }

    fun setDigestEnabled(enabled: Boolean) {
        settings.setDigestEnabled(graphId, enabled)
        refresh()
    }

    fun setDigestTitle(raw: String) {
        val title = ProjectNaming.normalizeDigestTitle(raw)
        if (title == null) {
            setMessage("Use a plain page name of up to ${ProjectNaming.MAX_TITLE} characters, without [ ] # | or backslash")
            return
        }
        settings.setDigestPageTitle(graphId, title)
        refresh()
    }

    /** Turning analysis on passes through the outbound preview unless the owner already accepted this exact text. */
    fun setAnalyseAfterSync(enable: Boolean) {
        val slug = _state.value.projects.firstOrNull()?.slug ?: run {
            if (enable) setMessage("Create a project first")
            else { settings.setAnalyseAfterSync(graphId, false); refresh() }
            return
        }
        val text = AnalyseToggleRules.emptyPreview(slug)
        when (val d = AnalyseToggleRules.decide(enable, providerConfigured(), text, settings.isOutboundPreviewAccepted(graphId, text))) {
            AnalyseToggleDecision.Disable -> settings.setAnalyseAfterSync(graphId, false)
            AnalyseToggleDecision.NotOffered -> setMessage("Choose a Project analysis provider in Settings first")
            AnalyseToggleDecision.Enable -> settings.setAnalyseAfterSync(graphId, true)
            is AnalyseToggleDecision.ShowPreview -> {
                _state.update { it.copy(pendingPreview = d.previewText) }
                return
            }
        }
        refresh()
    }

    /** Records acceptance as the SHA-256 of the exact text shown, then enables analysis. */
    fun confirmPreview() {
        val text = _state.value.pendingPreview ?: return
        settings.acceptOutboundPreview(graphId, OutboundPreview.hash(text))
        settings.setAnalyseAfterSync(graphId, true)
        _state.update { it.copy(pendingPreview = null) }
        refresh()
    }

    fun dismissPreview() = _state.update { it.copy(pendingPreview = null) }

    fun addPrivateTerm(term: String) {
        if (!settings.addOutboundPrivateTerm(graphId, term)) setMessage("Enter a new word")
        refresh()
    }

    fun searchSourcePages(query: String) {
        val service = sources ?: return
        scope.launch {
            val hits = service.search(query)
            _state.update { it.copy(pickerResults = hits) }
            val project = _state.value.projects.firstOrNull() ?: return@launch
            if (service.autoRebind(project.uuid, hits) > 0) reloadBound()
        }
    }

    fun bindSource(kind: SourceBindingKind, pageUuid: String) {
        val service = sources ?: return
        val project = _state.value.projects.firstOrNull() ?: return setMessage("Create a project first")
        scope.launch {
            when (service.bind(project.uuid, kind, pageUuid)) {
                BindOutcome.Bound -> reloadBound()
                BindOutcome.NotReadable -> setMessage("That page is not loaded yet; try again after it syncs")
                BindOutcome.LimitReached -> setMessage("This project already has the maximum number of source pages")
                else -> setMessage("Could not use that page")
            }
        }
    }

    fun unbindSource(bindingId: String) {
        val service = sources ?: return
        val project = _state.value.projects.firstOrNull() ?: return
        scope.launch {
            service.unbind(project.uuid, bindingId)
            reloadBound()
        }
    }

    /** Picks a YAML file once and snapshots its content into the project (Story 5.9c). */
    fun chooseYaml() {
        val action = yaml ?: return
        val project = _state.value.projects.firstOrNull() ?: return setMessage("Create a project first")
        scope.launch {
            action.run(project.uuid)?.let(::setMessage)
            reloadBound()
        }
    }

    /** Picks a drawing and opens the existing import flow for it (Story 5.9d). */
    fun addDrawing() {
        val action = drawings ?: return
        val project = _state.value.projects.firstOrNull() ?: return setMessage("Create a project first")
        if (_session.value != null) return
        scope.launch {
            when (val start = action.start(project.uuid)) {
                DrawingStart.Cancelled -> Unit
                is DrawingStart.Refused -> setMessage(start.message)
                is DrawingStart.Started -> _session.value = start.session
            }
        }
    }

    /** Called once the import screen reports Save or Cancel; only a saved drawing is recorded. */
    fun finishDrawing(phase: ImportPhase, registry: DimensionRegistry?) {
        val session = _session.value ?: return
        _session.value = null
        val action = drawings
        if (phase != ImportPhase.Done || action == null) return
        scope.launch {
            action.finish(session, registry)?.let(::setMessage)
            reloadBound()
        }
    }

    fun removeDrawing(contentHash: String) {
        val service = sheets ?: return
        val project = _state.value.projects.firstOrNull() ?: return
        scope.launch {
            service.detach(project.uuid, contentHash)
            reloadBound()
        }
    }

    private suspend fun reloadBound() {
        refreshYamlFlag()
        refreshSheets()
        val service = sources ?: return
        val project = _state.value.projects.firstOrNull() ?: return _state.update { it.copy(boundSources = emptyList()) }
        val rows = service.listBound(project.uuid)
        _state.update { it.copy(boundSources = rows) }
    }

    private suspend fun refreshSheets() {
        val service = sheets ?: return
        val project = _state.value.projects.firstOrNull()
        val rows = project?.let { service.list(it.uuid) }.orEmpty()
        _state.update { it.copy(attachedSheets = rows) }
    }

    private suspend fun refreshYamlFlag() {
        val project = _state.value.projects.firstOrNull()
        val set = project != null && yaml?.hasSnapshot(project.uuid) == true
        _state.update { it.copy(yamlSnapshotSet = set) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun close() = scope.cancel()

    private fun providerConfigured() = llmSettings.resolveExplicitProvider(LlmFeature.PROJECT_ANALYSIS) != null

    private fun refresh() = _state.update { snapshot(it.projects, it.hasMore, it) }

    private fun setMessage(text: String) = _state.update { it.copy(message = text) }

    private fun snapshot(rows: List<ProjectRow>, hasMore: Boolean, previous: ProjectListUiState? = null) = ProjectListUiState(
        projects = rows,
        hasMore = hasMore,
        digestEnabled = settings.digestEnabled(graphId),
        digestTitle = settings.digestPageTitle(graphId, rows.firstOrNull()?.name ?: DEFAULT_DISPLAY_NAME),
        analyseAfterSync = settings.analyseAfterSync(graphId),
        analyseOffered = providerConfigured(),
        pendingPreview = previous?.pendingPreview,
        pickerResults = previous?.pickerResults.orEmpty(),
        boundSources = previous?.boundSources.orEmpty(),
        yamlSnapshotSet = previous?.yamlSnapshotSet == true,
        attachedSheets = previous?.attachedSheets.orEmpty(),
        privateTerms = settings.outboundPrivateTerms(graphId),
        message = previous?.message,
    )

    companion object {
        const val PAGE_SIZE = 50
        const val DEFAULT_DISPLAY_NAME = "Project"
    }
}


/** Builds the list view model for the open graph; writes go through [writeActor] when the graph has one. */
fun projectListViewModelFor(
    repositories: dev.stapler.stelekit.repository.SketchRepositories,
    writeActor: dev.stapler.stelekit.db.DatabaseWriteActor?,
    graphId: String,
    settings: dev.stapler.stelekit.platform.Settings,
    sources: SourceBindingService? = null,
    yaml: YamlPickerAction? = null,
    drawings: DrawingAttachAction? = null,
    sheets: AttachedSheetService? = null,
): ProjectListViewModel = ProjectListViewModel(
    projects = repositories.projects,
    graphId = graphId,
    settings = ProjectBridgeSettings(settings),
    llmSettings = LlmSettings(settings),
    write = { op -> writeActor?.execute(op = op) ?: op() },
    clock = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    sources = sources,
    yaml = yaml,
    drawings = drawings,
    sheets = sheets,
)

/** True when the graph has at least one project (the post-sync job's `hasConfiguredProject`). */
suspend fun hasConfiguredProject(repositories: dev.stapler.stelekit.repository.SketchRepositories): Boolean =
    hasAnyProject(repositories.projects.getProjects(1, 0).first().getOrNull().orEmpty())
