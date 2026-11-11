// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectSettingsPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private var digest: Boolean? = null
    private var analyse: Boolean? = null
    private var title: String? = null
    private var confirmed = false
    private var dismissed = false
    private val terms = mutableListOf<String>()

    private fun show(state: ProjectListUiState) = rule.setContent {
        MaterialTheme {
            ProjectSettingsPanel(
                state, { digest = it }, { title = it }, { analyse = it },
                { confirmed = true }, { dismissed = true }, { terms += it },
            )
        }
    }

    @Test
    fun analysisSwitchIsHiddenWithoutAnExplicitProvider() {
        show(ProjectListUiState(analyseOffered = false))
        rule.onNodeWithText("Write a digest page after sync").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextCount("Analyse after sync") == 0)
    }

    @Test
    fun analysisSwitchShowsWhenAProviderIsConfigured() {
        show(ProjectListUiState(analyseOffered = true))
        rule.onNodeWithText("Analyse after sync").assertIsDisplayed()
    }

    @Test
    fun savingTheTitleReportsTheEditedText() {
        show(ProjectListUiState(digestTitle = "Closet Digest"))
        rule.onNodeWithText("Closet Digest").performTextReplacement("Closet Plan")
        rule.onNodeWithText("Save title").performClick()
        assertEquals("Closet Plan", title)
    }

    @Test
    fun pendingPreviewShowsTheExactTextAndConfirmsOrCancels() {
        show(ProjectListUiState(analyseOffered = true, pendingPreview = "Project: proj-0a1b2c\n", privateTerms = listOf("Maple")))
        rule.onNodeWithText("Review what would be sent").assertIsDisplayed()
        rule.onNodeWithText("Project: proj-0a1b2c", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Maple").assertIsDisplayed()
        rule.onNodeWithText("Send this after each sync").performClick()
        assertTrue(confirmed)
        assertFalse(dismissed)
    }

    @Test
    fun previewCancelAndAddWordCallBack() {
        show(ProjectListUiState(analyseOffered = true, pendingPreview = "Project: proj-0a1b2c\n"))
        rule.onNodeWithText("Add a word").performTextInput("Oak")
        rule.onNodeWithText("Add word").performClick()
        assertEquals(listOf("Oak"), terms)
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun listShowsNamesAndNeverSlugs() {
        rule.setContent {
            MaterialTheme {
                ProjectListContent(
                    ProjectListUiState(projects = listOf(ProjectRow("u1", "proj-0a1b2c", "Closet"))),
                    onCreate = {}, onDismissMessage = {}, settings = {},
                )
            }
        }
        rule.onNodeWithText("Closet").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTextCount("proj-0a1b2c"))
    }

    @Test
    fun newProjectDialogCreatesWithTheTypedName() {
        var created: String? = null
        rule.setContent { MaterialTheme { ProjectListContent(ProjectListUiState(), { created = it }, {}, {}) } }
        rule.onNodeWithText("New project").performClick()
        rule.onNodeWithText("Name").performTextInput("Garage")
        rule.onNodeWithText("Create").performClick()
        assertEquals("Garage", created)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
