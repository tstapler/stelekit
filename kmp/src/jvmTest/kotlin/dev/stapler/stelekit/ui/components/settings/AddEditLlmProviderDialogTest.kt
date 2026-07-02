// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.llm.CustomProviderConfig
import org.junit.Rule
import org.junit.Test

class AddEditLlmProviderDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun saveButton_should_BeDisabled_When_NonLoopbackHttpUrlEntered() {
        composeTestRule.setContent {
            MaterialTheme {
                AddEditLlmProviderDialog(existingConfig = null, onSave = { _, _ -> }, onCancel = {})
            }
        }

        composeTestRule.onNodeWithText("Display name").performTextInput("My remote server")
        composeTestRule.onNodeWithText("Base URL").performTextInput("http://example.com/v1")

        composeTestRule.onNodeWithText(
            "Non-HTTPS URLs are only allowed for localhost/loopback addresses " +
                "(e.g. Ollama, LM Studio). Remote endpoints must use HTTPS.",
        ).assertExists()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun saveButton_should_BeEnabled_When_LoopbackHttpUrlEntered() {
        composeTestRule.setContent {
            MaterialTheme {
                AddEditLlmProviderDialog(existingConfig = null, onSave = { _, _ -> }, onCancel = {})
            }
        }

        composeTestRule.onNodeWithText("Display name").performTextInput("Ollama")
        composeTestRule.onNodeWithText("Base URL").performTextInput("http://localhost:11434/v1")

        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun fetchModelsButton_should_PopulateDropdown_When_ProbeSucceeds() {
        var savedConfig: CustomProviderConfig? = null

        composeTestRule.setContent {
            MaterialTheme {
                AddEditLlmProviderDialog(
                    existingConfig = null,
                    onSave = { config, _ -> savedConfig = config },
                    onCancel = {},
                    fetchModels = { _, _ -> FetchModelsResult.Success(listOf("llama3", "mistral")) },
                )
            }
        }

        composeTestRule.onNodeWithText("Display name").performTextInput("Ollama")
        composeTestRule.onNodeWithText("Base URL").performTextInput("http://localhost:11434/v1")
        composeTestRule.onNodeWithText("Fetch models").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Connected — 2 model(s) available.").assertExists()

        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitForIdle()

        assert(savedConfig?.model == "llama3") { "expected first fetched model to be pre-selected, got ${savedConfig?.model}" }
    }

    @Test
    fun fetchModelsButton_should_ShowInlineError_When_ProbeFails_And_NotBlockManualModelEntry() {
        composeTestRule.setContent {
            MaterialTheme {
                AddEditLlmProviderDialog(
                    existingConfig = null,
                    onSave = { _, _ -> },
                    onCancel = {},
                    fetchModels = { _, _ -> FetchModelsResult.Failure("connection refused") },
                )
            }
        }

        composeTestRule.onNodeWithText("Display name").performTextInput("Ollama")
        composeTestRule.onNodeWithText("Base URL").performTextInput("http://localhost:11434/v1")
        composeTestRule.onNodeWithText("Fetch models").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("connection refused").assertExists()

        // Manual model entry must still work after a failed probe.
        composeTestRule.onNodeWithText("Model").performTextInput("llama3:8b")
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun dialog_should_PrefillFields_When_EditingExistingConfig() {
        val existing = CustomProviderConfig(
            id = "custom:abc-123",
            displayName = "My Ollama",
            baseUrl = "http://localhost:11434/v1",
            model = "llama3",
        )

        composeTestRule.setContent {
            MaterialTheme {
                AddEditLlmProviderDialog(existingConfig = existing, onSave = { _, _ -> }, onCancel = {})
            }
        }

        composeTestRule.onNodeWithText("Edit custom provider").assertExists()
        composeTestRule.onNodeWithText("My Ollama").assertExists()
        composeTestRule.onNodeWithText("llama3").assertExists()
    }
}
