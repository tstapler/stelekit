// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.sections.SectionDefinition
import dev.stapler.stelekit.sections.SectionManifest
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.ui.components.settings.DeviceSubscriptionsPanel
import dev.stapler.stelekit.ui.components.settings.SectionsSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

// Tall screen: dialogs with four text fields clip on the 320x470 default.
@Config(qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class SectionsSettingsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val work = SectionDefinition("work", "Work", "#3B82F6", "work", "journals-work")
    private val home = SectionDefinition("home", "Home", null, "home", "journals-home")

    private class Calls {
        val created = mutableListOf<List<String?>>()
        val renamed = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
    }

    private fun show(manifest: SectionManifest): Calls {
        val calls = Calls()
        composeTestRule.setContent {
            MaterialTheme {
                SectionsSettings(
                    manifest = manifest,
                    onCreateSection = { id, name, color, pages, journals ->
                        calls.created += listOf(id, name, color, pages, journals)
                    },
                    onRenameSection = { id, name -> calls.renamed += id to name },
                    onDeleteSection = { calls.deleted += it },
                )
            }
        }
        return calls
    }

    @Test
    fun emptyState_should_ShowHint_AndNoRows() {
        show(SectionManifest())

        composeTestRule.onNodeWithText("No sections defined. Add a section to organize your notes.").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Rename section").assertCountEquals(0)
    }

    @Test
    fun sections_should_BeListedWithPrefixes() {
        show(SectionManifest(sections = listOf(work, home)))

        composeTestRule.onNodeWithText("Work").assertIsDisplayed()
        composeTestRule.onNodeWithText("Home").assertIsDisplayed()
        composeTestRule.onNodeWithText("pages: work  journals: journals-work").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Delete section").assertCountEquals(2)
    }

    @Test
    fun createDialog_should_AutofillFolders_AndSubmit() {
        val calls = show(SectionManifest())

        composeTestRule.onNodeWithText("New Section").performClick()
        composeTestRule.onNodeWithText("Display name").performTextInput("My Notes")
        composeTestRule.onNodeWithText("Create").performClick()

        assertEquals(listOf(listOf("my-notes", "My Notes", null, "my-notes", "journals-my-notes")), calls.created)
    }

    @Test
    fun createDialog_should_ShowError_WhenNameBlank() {
        val calls = show(SectionManifest())

        composeTestRule.onAllNodesWithText("New Section").onFirst().performClick()
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.onNodeWithText("Display name is required").assertIsDisplayed()
        assertEquals(0, calls.created.size)
    }

    @Test
    fun createDialog_should_RequireFolders_WhenCleared() {
        val calls = show(SectionManifest())

        composeTestRule.onNodeWithText("New Section").performClick()
        composeTestRule.onNodeWithText("Display name").performTextInput("Notes")
        composeTestRule.onNodeWithText("Pages folder").performTextClearance()
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.onNodeWithText("Pages folder is required").assertIsDisplayed()

        composeTestRule.onNodeWithText("Pages folder").performTextInput("notes")
        composeTestRule.onNodeWithText("Journals folder").performTextClearance()
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.onNodeWithText("Journals folder is required").assertIsDisplayed()
        assertEquals(0, calls.created.size)
    }

    @Test
    fun createDialog_cancel_should_DismissWithoutCallback() {
        val calls = show(SectionManifest())

        composeTestRule.onNodeWithText("New Section").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.onAllNodesWithText("Create").assertCountEquals(0)
        assertEquals(0, calls.created.size)
    }

    @Test
    fun deleteConfirmation_should_CallDelete_WhenConfirmed() {
        val calls = show(SectionManifest(sections = listOf(work)))

        composeTestRule.onNodeWithContentDescriptionText("Delete section").performClick()
        composeTestRule.onNodeWithText("Delete Section").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf("work"), calls.deleted)
    }

    @Test
    fun deleteConfirmation_cancel_should_NotCallDelete() {
        val calls = show(SectionManifest(sections = listOf(work)))

        composeTestRule.onNodeWithContentDescriptionText("Delete section").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, calls.deleted.size)
        composeTestRule.onAllNodesWithText("Delete Section").assertCountEquals(0)
    }

    @Test
    fun renameDialog_should_CallRename_WithTrimmedName() {
        val calls = show(SectionManifest(sections = listOf(work)))

        composeTestRule.onNodeWithContentDescriptionText("Rename section").performClick()
        composeTestRule.onNodeWithText("Display name").performTextClearance()
        composeTestRule.onNodeWithText("Display name").performTextInput("  Job  ")
        composeTestRule.onNodeWithText("Rename").performClick()

        assertEquals(listOf("work" to "Job"), calls.renamed)
    }

    @Test
    fun renameDialog_should_ShowError_WhenNameCleared() {
        val calls = show(SectionManifest(sections = listOf(work)))

        composeTestRule.onNodeWithContentDescriptionText("Rename section").performClick()
        composeTestRule.onNodeWithText("Display name").performTextClearance()
        composeTestRule.onNodeWithText("Rename").performClick()

        composeTestRule.onNodeWithText("Name is required").assertIsDisplayed()
        assertEquals(0, calls.renamed.size)
    }

    @Test
    fun deviceSubscriptions_should_ToggleAndShowRestartBanner_AndHideRemoved() {
        val toggles = mutableListOf<Pair<String, SectionState>>()
        val removed = SectionDefinition("old", "Old", null, "old", "journals-old")
        composeTestRule.setContent {
            MaterialTheme {
                DeviceSubscriptionsPanel(
                    manifest = SectionManifest(sections = listOf(work, removed)),
                    sectionStates = mapOf("old" to SectionState.REMOVED),
                    onToggleSection = { id, state -> toggles += id to state },
                )
            }
        }

        composeTestRule.onAllNodesWithText("Old").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Restart required for subscription changes to take effect.").assertCountEquals(0)

        composeTestRule.onNode(androidx.compose.ui.test.isToggleable()).performClick()

        assertEquals(listOf("work" to SectionState.HIDDEN), toggles)
        composeTestRule.onNodeWithText("Restart required for subscription changes to take effect.").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionText(
        description: String,
    ) = onAllNodesWithContentDescription(description).onFirst()
}
