// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import io.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

/**
 * Roborazzi screenshot test — split out of [LlmProviderListScreenTest] because it has no Bazel
 * integration (kept in Gradle only, see `kmp/src/jvmTest/kotlin/BUILD.bazel`'s
 * `*ScreenshotTest.kt` exclusion glob).
 */
class LlmProviderListScreenScreenshotTest {

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
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                LlmProviderListScreen(registry = registry, onAddProvider = {}, onEditProvider = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/llm_provider_list_checking_availability.png")
    }
}
