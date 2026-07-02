// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/**
 * Epic 6 Story 6.2: [LlmProviderListScreen] must never show an optimistic "Available" default
 * while [LlmProvider.checkAvailability] is still resolving — it always starts at "Checking
 * availability…" (features research §2 / Android ML Kit docs' explicit rule).
 */
class LlmProviderListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class SlowProvider(
        override val id: String,
        override val displayName: String,
        private val gate: CompletableDeferred<LlmProviderAvailability>,
    ) : LlmProvider {
        override val kind: LlmProviderKind = LlmProviderKind.REMOTE
        override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> LlmResult.Success("unused") }
        override suspend fun checkAvailability(): LlmProviderAvailability = gate.await()
    }

    @Test
    fun providerRow_should_ShowCheckingAvailability_BeforeAsyncResolutionCompletes() {
        val gate = CompletableDeferred<LlmProviderAvailability>()
        val registry = LlmProviderRegistry(listOf(SlowProvider("anthropic", "Anthropic Claude", gate)))

        composeTestRule.setContent {
            MaterialTheme {
                LlmProviderListScreen(registry = registry, onAddProvider = {}, onEditProvider = {})
            }
        }

        // First frame: the availability check has not resolved yet — must show the neutral
        // "Checking availability…" placeholder, never an optimistic "Connected".
        composeTestRule.onNodeWithText("Checking availability…").assertExists()

        gate.complete(LlmProviderAvailability.Available)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Connected").assertExists()
    }

    @Test
    fun providerRow_should_ShowUnavailableReason_When_CheckResolvesUnavailable() {
        val gate = CompletableDeferred<LlmProviderAvailability>()
        val registry = LlmProviderRegistry(listOf(SlowProvider("openai", "OpenAI", gate)))

        composeTestRule.setContent {
            MaterialTheme {
                LlmProviderListScreen(registry = registry, onAddProvider = {}, onEditProvider = {})
            }
        }

        gate.complete(LlmProviderAvailability.Unavailable("no key configured"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("no key configured").assertExists()
    }

    // MA11: navigation callbacks (row click -> onEditProvider(id), add-button click ->
    // onAddProvider()) were previously untested — only rendering was covered.

    @Test
    fun providerRow_should_InvokeOnEditProviderWithCorrectId_When_Clicked() {
        val gate = CompletableDeferred<LlmProviderAvailability>()
        val registry = LlmProviderRegistry(listOf(SlowProvider("anthropic", "Anthropic Claude", gate)))
        var editedProviderId: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                LlmProviderListScreen(
                    registry = registry,
                    onAddProvider = {},
                    onEditProvider = { editedProviderId = it },
                )
            }
        }

        composeTestRule.onNodeWithText("Anthropic Claude").performClick()

        assertEquals("anthropic", editedProviderId)
    }

    @Test
    fun addProviderButton_should_InvokeOnAddProvider_When_Clicked() {
        val registry = LlmProviderRegistry(emptyList())
        var addProviderCalled = false

        composeTestRule.setContent {
            MaterialTheme {
                LlmProviderListScreen(
                    registry = registry,
                    onAddProvider = { addProviderCalled = true },
                    onEditProvider = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Add provider").performClick()

        assertTrue(addProviderCalled)
    }

    @Test
    fun emptyRegistry_should_ShowEmptyStateMessage() {
        composeTestRule.setContent {
            MaterialTheme {
                LlmProviderListScreen(registry = LlmProviderRegistry(emptyList()), onAddProvider = {}, onEditProvider = {})
            }
        }

        composeTestRule.onNodeWithText(
            "No providers configured yet. Add a custom provider, or set an API key on a " +
                "built-in provider above.",
        ).assertExists()
    }
}
