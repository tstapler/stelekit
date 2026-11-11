// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.projectbridge.AliasKind
import dev.stapler.stelekit.projectbridge.ConstraintEditing
import dev.stapler.stelekit.projectbridge.ConstraintPreview
import dev.stapler.stelekit.projectbridge.ConstraintPreviewRow
import dev.stapler.stelekit.projectbridge.ConstraintTemplate
import dev.stapler.stelekit.projectbridge.EditError
import dev.stapler.stelekit.projectbridge.Edited
import dev.stapler.stelekit.projectbridge.FusionSettingsLoader
import dev.stapler.stelekit.projectbridge.FusionSettingsState
import dev.stapler.stelekit.projectbridge.FusionSpec
import dev.stapler.stelekit.projectbridge.KeySuggestion
import dev.stapler.stelekit.projectbridge.ProjectFusionSettings
import dev.stapler.stelekit.projectbridge.RegistryEditing
import dev.stapler.stelekit.projectbridge.YamlKeySuggestions
import dev.stapler.stelekit.projectbridge.message
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RegistryEditorState(
    val loaded: Boolean = false,
    val hasProject: Boolean = false,
    val registry: DimensionRegistry = DimensionRegistry(dimensions = emptyList()),
    val spec: FusionSpec = FusionSpec(),
    /** Non-null when the saved registry or constraints are unreadable; editing stays off until the owner starts over. */
    val needsAttention: String? = null,
    val suggestions: List<KeySuggestion> = emptyList(),
    val preview: List<ConstraintPreviewRow> = emptyList(),
    val message: String? = null,
)

/**
 * Edits the first project's dimension registry and [FusionSpec] (Story 5.9e) and saves them into the project's synced
 * `settings`, where the post-sync fusion stage reads them. Owns its scope; every edit goes through pure functions that
 * validate before anything is written.
 */
class RegistryEditorViewModel(
    private val projects: SketchProjectRepository,
    private val write: ProjectWriteRunner,
    private val yamlText: suspend () -> String?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> setMessage("Something went wrong (${e::class.simpleName})") },
    )
    private val lock = Mutex()
    private var yaml: String? = null
    private val _state = MutableStateFlow(RegistryEditorState())
    val state: StateFlow<RegistryEditorState> = _state

    init {
        scope.launch { lock.withLock { reload() } }
    }

    fun addKey(key: String, kind: DimensionKind, label: String) =
        edit { RegistryEditing.addKey(it.registry, key, kind, label).map { registry -> it.copy(registry = registry) } }

    fun renameKey(old: String, new: String) = edit { RegistryEditing.renameKey(it, old, new) }

    fun deleteKey(key: String) = edit { RegistryEditing.deleteKey(it, key) }

    fun setKind(key: String, kind: DimensionKind) =
        edit { RegistryEditing.setKind(it.registry, key, kind).map { registry -> it.copy(registry = registry) } }

    fun addAlias(key: String, kind: AliasKind, alias: String) =
        edit { RegistryEditing.addAlias(it.registry, key, kind, alias).map { registry -> it.copy(registry = registry) } }

    fun removeAlias(key: String, kind: AliasKind, alias: String) =
        edit { RegistryEditing.removeAlias(it.registry, key, kind, alias).map { registry -> it.copy(registry = registry) } }

    fun acceptSuggestion(path: String) {
        val suggestion = _state.value.suggestions.firstOrNull { it.path == path } ?: return
        edit { YamlKeySuggestions.accept(it.registry, suggestion).map { registry -> it.copy(registry = registry) } }
    }

    fun addConstraint(template: ConstraintTemplate) =
        edit { ConstraintEditing.add(it.registry, it.spec, template).map { spec -> it.copy(spec = spec) } }

    fun removeConstraint(id: Int) = edit { Edited(it.registry, ConstraintEditing.remove(it.spec, id)).right() }

    /** Replaces unreadable saved settings with an empty registry and spec. */
    fun startOver() {
        if (_state.value.needsAttention == null) return
        scope.launch { lock.withLock { persist(Edited(DimensionRegistry(dimensions = emptyList()), FusionSpec())) } }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun close() = scope.cancel()

    private fun edit(change: (Edited) -> Either<EditError, Edited>) {
        scope.launch {
            lock.withLock {
                val current = _state.value
                if (current.needsAttention != null) return@withLock setMessage("Start over before editing")
                if (!current.hasProject) return@withLock setMessage("Create a project first")
                change(Edited(current.registry, current.spec)).fold({ setMessage(it.message()) }, { persist(it) })
            }
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun persist(edited: Edited) {
        val project = firstProject() ?: return setMessage("Create a project first")
        val updated = project.copy(
            settings = ProjectFusionSettings.with(project.settings, edited.registry, edited.spec),
            modelRevision = project.modelRevision + 1,
        )
        write { projects.saveProject(updated) }.fold({ setMessage("Could not save the registry") }, { reload() })
    }

    /** Re-reads the saved settings so the screen always shows what was actually stored. */
    private suspend fun reload() {
        val project = firstProject()
        yaml = yamlText()
        val loaded = project?.let { FusionSettingsLoader.load(it.settings) } ?: FusionSettingsState.Absent
        val (registry, spec, attention) = when (loaded) {
            FusionSettingsState.Absent -> Triple(DimensionRegistry(dimensions = emptyList()), FusionSpec(), null)
            is FusionSettingsState.Valid -> Triple(loaded.registry, loaded.spec, null)
            is FusionSettingsState.NeedsAttention -> Triple(DimensionRegistry(dimensions = emptyList()), FusionSpec(), loaded.reason)
        }
        _state.update {
            it.copy(
                loaded = true,
                hasProject = project != null,
                registry = registry,
                spec = spec,
                needsAttention = attention,
                suggestions = YamlKeySuggestions.suggest(yaml, registry),
                preview = ConstraintPreview.rows(registry, spec, ConstraintPreview.yamlObservations(registry, yaml)),
            )
        }
    }

    private suspend fun firstProject(): SketchProject? = projects.getProjects(1, 0).first().getOrNull()?.firstOrNull()

    private fun setMessage(text: String) = _state.update { it.copy(message = text) }
}

/** Binds every editor action to this view model; kept out of the composable so the wiring is unit-tested. */
fun RegistryEditorViewModel.actions(onBack: () -> Unit): RegistryEditorActions =
    RegistryEditorActions(
            onBack = onBack,
            onAddKey = this::addKey,
            onRenameKey = this::renameKey,
            onDeleteKey = this::deleteKey,
            onToggleKind = this::setKind,
            onAddAlias = this::addAlias,
            onRemoveAlias = this::removeAlias,
            onAcceptSuggestion = this::acceptSuggestion,
            onAddConstraint = this::addConstraint,
            onRemoveConstraint = this::removeConstraint,
            onStartOver = this::startOver,
            onDismissMessage = this::dismissMessage,
    )
