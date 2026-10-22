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

// Tall screen: avoids clipping on the 320x470 default.
// Dialogs containing text fields live in jvmTest SectionsSettingsDialogsTest: under Robolectric any
// OutlinedTextField inside a Dialog window never lets Compose go idle (infinite layout loop -> heap OOM).
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
