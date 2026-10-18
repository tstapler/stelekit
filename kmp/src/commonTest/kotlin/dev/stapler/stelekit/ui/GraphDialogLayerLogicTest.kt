// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.sections.SectionManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphDialogLayerLogicTest {

    private val graph = GraphInfo(id = GraphId("g1"), path = "/g1", displayName = "G1", addedAt = 0L)
    private val manifest = SectionManifest()
    private val conflict = requireNotNull(
        DiskConflict.ifReal(
            pageUuid = "p", pageName = "P", filePath = "/g1/pages/p.md", editingBlockUuid = null,
            localContent = "local edit", diskContent = "disk edit", diskBlockContent = "disk edit",
        )
    )

    @Test
    fun `nothing is shown for a default AppState`() {
        val s = AppState()
        assertFalse(s.shouldShowSettingsDialog())
        assertFalse(s.shouldShowConflictResolution())
        assertFalse(s.shouldShowJournalMergeReview())
        assertFalse(s.shouldShowLlmSuggestionReview())
        assertFalse(s.shouldShowDiskConflictDialog())
        assertFalse(s.shouldShowDiskConflictFullScreen())
        assertFalse(s.shouldShowWikiSubdirFixDialog())
        assertFalse(s.shouldShowDeviceSetupWizard())
        assertFalse(s.shouldShowSectionPicker())
        assertFalse(s.shouldShowSectionQuickToggle())
    }

    @Test
    fun `settings dialog opens for settingsVisible alone`() =
        assertTrue(AppState(settingsVisible = true).shouldShowSettingsDialog())

    @Test
    fun `settings dialog opens for llmProviderSettingsVisible alone`() =
        assertTrue(AppState(llmProviderSettingsVisible = true).shouldShowSettingsDialog())

    @Test
    fun `conflict resolution follows its flag`() {
        assertTrue(AppState(conflictResolutionVisible = true).shouldShowConflictResolution())
    }

    @Test
    fun `journal merge review follows its flag`() {
        assertTrue(AppState(journalMergeReviewVisible = true).shouldShowJournalMergeReview())
    }

    @Test
    fun `llm suggestion review follows its flag`() {
        assertTrue(AppState(llmSuggestionReviewVisible = true).shouldShowLlmSuggestionReview())
    }

    @Test
    fun `compact disk conflict dialog shows when a conflict exists`() {
        val s = AppState(diskConflict = conflict)
        assertTrue(s.shouldShowDiskConflictDialog())
        assertFalse(s.shouldShowDiskConflictFullScreen())
    }

    @Test
    fun `full-screen comparison replaces the compact dialog`() {
        val s = AppState(diskConflict = conflict, diskConflictViewFullVisible = true)
        assertFalse(s.shouldShowDiskConflictDialog())
        assertTrue(s.shouldShowDiskConflictFullScreen())
    }

    @Test
    fun `full-screen flag without a conflict shows nothing`() {
        val s = AppState(diskConflictViewFullVisible = true)
        assertFalse(s.shouldShowDiskConflictDialog())
        assertFalse(s.shouldShowDiskConflictFullScreen())
    }

    @Test
    fun `wiki subdir fix needs both the flag and a matching active graph`() {
        val flagOnly = AppState(wikiSubdirFixDialogVisible = true)
        assertFalse(flagOnly.shouldShowWikiSubdirFixDialog())

        val graphOnly = AppState(availableGraphs = listOf(graph), currentGraphId = "g1")
        assertFalse(graphOnly.shouldShowWikiSubdirFixDialog())

        val both = graphOnly.copy(wikiSubdirFixDialogVisible = true)
        assertTrue(both.shouldShowWikiSubdirFixDialog())
    }

    @Test
    fun `wiki subdir fix is hidden when the current graph is not in the available list`() {
        val s = AppState(wikiSubdirFixDialogVisible = true, availableGraphs = listOf(graph), currentGraphId = "other")
        assertNull(s.activeGraphInfo())
        assertFalse(s.shouldShowWikiSubdirFixDialog())
    }

    @Test
    fun `activeGraphInfo resolves the current graph`() {
        val s = AppState(availableGraphs = listOf(graph), currentGraphId = "g1")
        assertEquals(graph, assertNotNull(s.activeGraphInfo()))
    }

    @Test
    fun `device setup wizard requires a manifest`() {
        assertFalse(AppState(deviceSetupWizardVisible = true).shouldShowDeviceSetupWizard())
        assertFalse(AppState(currentManifest = manifest).shouldShowDeviceSetupWizard())
        assertTrue(AppState(deviceSetupWizardVisible = true, currentManifest = manifest).shouldShowDeviceSetupWizard())
    }

    @Test
    fun `section picker requires a manifest`() {
        assertFalse(AppState(sectionPickerVisible = true).shouldShowSectionPicker())
        assertTrue(AppState(sectionPickerVisible = true, currentManifest = manifest).shouldShowSectionPicker())
    }

    @Test
    fun `section quick toggle requires a manifest`() {
        assertFalse(AppState(sectionQuickToggleVisible = true).shouldShowSectionQuickToggle())
        assertTrue(AppState(sectionQuickToggleVisible = true, currentManifest = manifest).shouldShowSectionQuickToggle())
    }
}
