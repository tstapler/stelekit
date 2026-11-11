// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketchimport

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.sketchimport.DrawingPreview
import dev.stapler.stelekit.sketchimport.ImportError
import dev.stapler.stelekit.sketchimport.ImportFlow
import dev.stapler.stelekit.sketchimport.KeyError
import dev.stapler.stelekit.sketchimport.TagChoice
import dev.stapler.stelekit.sketchimport.Tagging
import dev.stapler.stelekit.sketchimport.TaggingItem
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportPhase { Idle, Tagging, Saving, Done, Cancelled, Failed }

data class SketchImportUiState(
    val phase: ImportPhase = ImportPhase.Idle,
    val fileName: String = "",
    val items: List<TaggingItem> = emptyList(),
    val boundCount: Int = 0,
    val choices: Map<String, TagChoice> = emptyMap(),
    val registryKeys: List<String> = emptyList(),
    val keyError: KeyError? = null,
    val error: String? = null,
    /** The registry with any keys created while tagging; the host persists it when [phase] is [ImportPhase.Done]. */
    val registry: DimensionRegistry? = null,
)

/** Owns its scope (never a composition scope); `cancel()` writes nothing because `ImportFlow.prepare` only reads. */
class SketchImportViewModel(
    private val flow: ImportFlow,
    private val projectUuid: ProjectUuid,
    registry: DimensionRegistry,
) {
    private val logger = Logger("SketchImportViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + PlatformDispatcher.Default +
            CoroutineExceptionHandler { _, t ->
                logger.error("import failed: ${t.message}")
                _state.update { it.copy(phase = ImportPhase.Failed, error = "Import failed") }
            },
    )
    private var registry = registry
    private var preview: DrawingPreview? = null
    private val _state = MutableStateFlow(SketchImportUiState(registryKeys = registry.dimensions.map { it.key }))
    val state: StateFlow<SketchImportUiState> = _state.asStateFlow()

    fun open(fileName: String, bytes: ByteArray) {
        flow.prepare(registry, fileName, bytes).fold(
            { e -> _state.update { it.copy(phase = ImportPhase.Failed, error = describe(e)) } },
            { p ->
                preview = p
                _state.value = SketchImportUiState(
                    phase = ImportPhase.Tagging,
                    fileName = fileName,
                    items = p.taggingItems,
                    boundCount = p.labels.bound.size,
                    registryKeys = registry.dimensions.map { it.key },
                )
            },
        )
    }

    fun choose(ref: String, choice: TagChoice) {
        _state.update { it.copy(choices = it.choices + (ref to choice), keyError = null) }
    }

    fun save() {
        val p = preview ?: return
        val outcome = Tagging.apply(registry, _state.value.choices).fold(
            { e -> _state.update { it.copy(keyError = e) }; return },
            { it },
        )
        _state.update { it.copy(phase = ImportPhase.Saving, keyError = null) }
        scope.launch {
            flow.commit(projectUuid, p, outcome.registry, outcome.assignments).fold(
                { e -> _state.update { it.copy(phase = ImportPhase.Failed, error = describe(e)) } },
                {
                    registry = outcome.registry
                    _state.update { it.copy(phase = ImportPhase.Done, registry = outcome.registry) }
                },
            )
        }
    }

    fun cancel() {
        preview = null
        _state.update { it.copy(phase = ImportPhase.Cancelled, choices = emptyMap(), items = emptyList()) }
    }

    fun close() = scope.cancel()

    private fun describe(e: ImportError): String = when (e) {
        is ImportError.UnsupportedType -> "Unsupported file type: ${e.extension.ifEmpty { "none" }}"
        ImportError.Empty -> "The file is empty"
        ImportError.ProjectMissing -> "The project no longer exists"
        is ImportError.Failed -> e.message
    }
}
