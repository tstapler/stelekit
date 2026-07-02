// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import io.github.takahirom.roborazzi.captureRoboImage
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

    /**
     * UX Acceptance Test (validation.md): "Provider list never shows an optimistic 'Available'
     * before the async check resolves" — golden captures the exact first frame, pending forever
     * (gate never completed), so the screenshot is deterministic across runs.
     *
     * To record: `./gradlew jvmTest -Proborazzi.test.record=true`
     */
    @Test
    fun initialRender_should_ShowCheckingAvailability() {
        val gate = CompletableDeferred<LlmProviderAvailability>()
        val registry = LlmProviderRegistry(listOf(SlowProvider("anthropic", "Anthropic Claude", gate)))

        composeTestRule.setContent {
            StelekitTheme(themeMode = dev.stapler.stelekit.ui.theme.StelekitThemeMode.LIGHT) {
                LlmProviderListScreen(registry = registry, onAddProvider = {}, onEditProvider = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/llm_provider_list_checking_availability.png")
    }
}
