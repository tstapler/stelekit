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
import dev.stapler.stelekit.ui.components.settings.SectionsSettings
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

// Create/rename dialogs hold OutlinedTextFields, which hang Robolectric (see SectionsSettingsTest).
class SectionsSettingsDialogsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val work = SectionDefinition("work", "Work", "#3B82F6", "work", "journals-work")

    private class Calls {
        val created = mutableListOf<List<String?>>()
        val renamed = mutableListOf<Pair<String, String>>()
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
                    onDeleteSection = {},
                )
            }
        }
        return calls
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionText(
        description: String,
    ) = onAllNodesWithContentDescription(description).onFirst()

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
}
