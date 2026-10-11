// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionFilter
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionManifestParser
import dev.stapler.stelekit.sections.SectionManifestWriter
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.sections.putSectionStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Owns graph-section bookkeeping: the on-disk [SectionManifest], per-device [SectionState]
 * visibility overrides, the device-setup wizard gate, and the section picker/quick-toggle
 * dialogs.
 *
 * Extracted from [StelekitViewModel] (Phase 1 of the decomposition plan in
 * `project_plans/stelekit-viewmodel-decomposition/plan.md`) to shrink that God Object. This is a
 * plain Kotlin class, not a `@Composable` — there is no UI concern here, just ViewModel-internal
 * state — following the parameter-object-driven collaborator pattern [GraphContentActiveShell]
 * established for `App.kt` (PR #367), adapted for a non-Compose context.
 *
 * Shares the ViewModel's [AppState] directly via [uiState] rather than owning a separate
 * `MutableStateFlow` of its own (contrast [dev.stapler.stelekit.llm.LlmSuggestionInbox] or
 * [dev.stapler.stelekit.ui.annotate.DepthEstimationCoordinator], which introduce new state not
 * previously part of `AppState`): the 8 fields this coordinator owns
 * (`currentManifest`/`currentSectionStates`/`defaultSection`/`deviceSetupComplete`/
 * `deviceSetupWizardVisible`/`sectionPickerVisible`/`sectionPickerPage`/
 * `sectionQuickToggleVisible`) are pre-existing `AppState` fields read directly by Compose call
 * sites across the app (`ScreenRouter`, `GraphDialogLayer`, `GraphContentLeftSidebar`,
 * `screens/PageView`) via `uiState.value.xxx`. Splitting them into a second `StateFlow` would
 * require reworking every one of those read sites to combine two flows — out of scope for a
 * mechanical, behavior-preserving extraction — so this coordinator mutates the same backing
 * `MutableStateFlow<AppState>` the ViewModel exposes as `uiState`, exactly as the original
 * methods did before the move.
 *
 * Reuses the ViewModel's own [scope] rather than creating a new one: that scope already carries
 * the ViewModel's `CoroutineExceptionHandler` guard (an uncaught `Throwable` — notably `OutOfMemoryError`
 * — would otherwise crash the Android process) and is cancelled in [StelekitViewModel.close], so
 * this coordinator's launched work is cancelled for free at the same point. This is safe because
 * the coordinator is held as a `private val` field with the same lifetime as the ViewModel itself
 * — never a `rememberCoroutineScope()`-derived scope, which this repo's coroutine-ownership rule
 * forbids passing to anything that outlives a single composition.
 */
class SectionManagementCoordinator(
    private val fileSystem: FileSystem,
    private val graphLoader: GraphLoaderPort,
    private val graphWriter: GraphWriterPort,
    private val pageRepository: PageRepository,
    private val writeActor: DatabaseWriteActor?,
    private val platformSettings: Settings,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<AppState>,
    private val onSectionsLoaded: (suspend (SectionManifest, Map<String, SectionState>) -> Unit)?,
    private val sendSnackbar: (String) -> Unit,
    private val onJournalPageCreated: (Page) -> Unit,
) {
    private val logger = Logger("SectionManagementCoordinator")
    private val sectionManifestParser = SectionManifestParser(fileSystem)
    private val sectionManifestWriter = SectionManifestWriter(fileSystem)

    internal suspend fun loadSectionManifest(graphPath: String) {
        val manifest = sectionManifestParser.parse(graphPath).getOrNull() ?: SectionManifest()
        uiState.update { it.copy(currentManifest = manifest) }
        // Wire section filter so GraphLoader assigns sectionId to page paths on disk
        val states = uiState.value.currentSectionStates
        if (manifest.sections.isNotEmpty()) {
            graphLoader.updateSectionFilter(SectionFilter(manifest, states))
        }
        // Show device setup wizard on first load when sections exist and setup not complete
        val setupComplete = uiState.value.deviceSetupComplete
        if (!setupComplete && manifest.sections.isNotEmpty()) {
            uiState.update { it.copy(deviceSetupWizardVisible = true) }
        }
        // Platform-specific sync (WASM: seed INDEX_ONLY stubs from GitHub tree)
        onSectionsLoaded?.invoke(manifest, states)
    }

    @OptIn(DirectRepositoryWrite::class)
    fun movePageToSection(page: Page, sectionId: String) {
        val manifest = uiState.value.currentManifest ?: return
        val section = if (sectionId.isEmpty()) null else manifest.sections.find { it.id == sectionId }
        val pathPrefix = section?.pagePathPrefix ?: "pages"
        val typedSectionId = SectionId.fromDbString(sectionId)
        scope.launch {
            graphWriter.movePageToSection(page, typedSectionId, pathPrefix).fold(
                ifLeft = { err ->
                    logger.error("movePageToSection failed: ${err.message}")
                    sendSnackbar("Failed to move page: ${err.message}")
                },
                ifRight = { updatedPage ->
                    if (writeActor != null) {
                        writeActor.execute { pageRepository.savePage(updatedPage) }
                    } else {
                        pageRepository.savePage(updatedPage)
                    }
                    uiState.update { state ->
                        state.copy(
                            currentPage = if (state.currentPage?.uuid == page.uuid) updatedPage else state.currentPage,
                            currentScreen = if (state.currentScreen is Screen.PageView &&
                                state.currentScreen.page.uuid == page.uuid
                            ) Screen.PageView(updatedPage) else state.currentScreen,
                            sectionPickerVisible = false,
                            sectionPickerPage = null,
                        )
                    }
                },
            )
        }
    }

    fun createSection(
        id: String,
        displayName: String,
        color: String?,
        pagePathPrefix: String,
        journalPathPrefix: String,
    ) {
        val manifest = uiState.value.currentManifest ?: SectionManifest()
        val graphPath = uiState.value.currentGraphPath ?: return
        val newSection = SectionDefinition(
            id = id,
            displayName = displayName,
            color = color,
            pagePathPrefix = pagePathPrefix,
            journalPathPrefix = journalPathPrefix,
        )
        val updated = manifest.copy(sections = manifest.sections + newSection)
        scope.launch {
            sectionManifestWriter.write(graphPath, updated).fold(
                ifLeft = { err -> logger.error("createSection write failed: ${err.message}") },
                ifRight = { uiState.update { it.copy(currentManifest = updated) } },
            )
        }
    }

    fun renameSection(id: String, newDisplayName: String) {
        val manifest = uiState.value.currentManifest ?: return
        val graphPath = uiState.value.currentGraphPath ?: return
        val updated = manifest.copy(
            sections = manifest.sections.map { if (it.id == id) it.copy(displayName = newDisplayName) else it }
        )
        scope.launch {
            sectionManifestWriter.write(graphPath, updated).fold(
                ifLeft = { err -> logger.error("renameSection write failed: ${err.message}") },
                ifRight = { uiState.update { it.copy(currentManifest = updated) } },
            )
        }
    }

    fun deleteSection(id: String) {
        val manifest = uiState.value.currentManifest ?: return
        val graphPath = uiState.value.currentGraphPath ?: return
        val updated = manifest.copy(sections = manifest.sections.filter { it.id != id })
        scope.launch {
            sectionManifestWriter.write(graphPath, updated).fold(
                ifLeft = { err -> logger.error("deleteSection write failed: ${err.message}") },
                ifRight = {
                    val newStates = uiState.value.currentSectionStates - id
                    platformSettings.putSectionStates(newStates)
                    uiState.update { it.copy(currentManifest = updated, currentSectionStates = newStates) }
                },
            )
        }
    }

    fun setDefaultSection(sectionId: String) {
        platformSettings.putString("defaultSection", sectionId)
        uiState.update { it.copy(defaultSection = SectionId.fromDbString(sectionId)) }
    }

    fun setSectionState(sectionId: String, state: SectionState) {
        val newStates = uiState.value.currentSectionStates + (sectionId to state)
        platformSettings.putSectionStates(newStates)
        uiState.update { it.copy(currentSectionStates = newStates) }
    }

    fun setSectionStates(states: Map<String, SectionState>) {
        platformSettings.putSectionStates(states)
        uiState.update { it.copy(currentSectionStates = states) }
    }

    fun completeDeviceSetup(defaultSection: String, sectionStates: Map<String, SectionState>) {
        platformSettings.putBoolean("deviceSetupComplete", true)
        platformSettings.putString("defaultSection", defaultSection)
        platformSettings.putSectionStates(sectionStates)
        uiState.update {
            it.copy(
                deviceSetupComplete = true,
                defaultSection = SectionId.fromDbString(defaultSection),
                currentSectionStates = sectionStates,
                deviceSetupWizardVisible = false,
            )
        }
    }

    fun showSectionPicker(page: Page) {
        uiState.update { it.copy(sectionPickerVisible = true, sectionPickerPage = page) }
    }

    fun dismissSectionPicker() {
        uiState.update { it.copy(sectionPickerVisible = false, sectionPickerPage = null) }
    }

    fun setSectionQuickToggleVisible(visible: Boolean) {
        uiState.update { it.copy(sectionQuickToggleVisible = visible) }
    }

    /** Creates today's journal page in [sectionId] and hands it back via [onJournalPageCreated] for navigation. */
    fun newSectionJournalForToday(sectionId: String) {
        scope.launch {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val result = graphLoader.createSectionJournalPage(sectionId, today)
            result.onRight { page -> onJournalPageCreated(page) }
        }
    }
}
