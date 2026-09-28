// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import org.junit.Rule
import org.junit.Test

/**
 * Epic 6 Story 6.5: SettingsDialog wires the "AI Providers" category in when the required
 * dependencies are present.
 */
class SettingsDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class InMemoryCredentialAccess : CredentialAccess {
        private val map = mutableMapOf<String, String>()
        override fun retrieve(key: String): String? = map[key]
        override fun store(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
    }

    @Test
    fun settingsDialog_should_ShowLlmProvidersCategory_When_DependenciesProvided() {
        val llmCredentialStore = LlmCredentialStore(InMemoryCredentialAccess())
        val llmSettings = LlmSettings(InMemorySettings())
        val registry = LlmProviderRegistry(emptyList())

        composeTestRule.setContent {
            SettingsDialog(
                visible = true,
                onDismiss = {},
                currentTheme = StelekitThemeMode.SYSTEM,
                onThemeChange = {},
                currentLanguage = Language.ENGLISH,
                onLanguageChange = {},
                onReindex = {},
                llmProviderRegistry = registry,
                llmSettings = llmSettings,
                llmCredentialStore = llmCredentialStore,
            )
        }

        composeTestRule.onNodeWithText("AI Providers").assertExists()
    }

    @Test
    fun settingsDialog_should_HideLlmProvidersCategory_When_DependenciesMissing() {
        composeTestRule.setContent {
            SettingsDialog(
                visible = true,
                onDismiss = {},
                currentTheme = StelekitThemeMode.SYSTEM,
                onThemeChange = {},
                currentLanguage = Language.ENGLISH,
                onLanguageChange = {},
                onReindex = {},
            )
        }

        composeTestRule.onNodeWithText("AI Providers").assertDoesNotExist()
    }

    @Test
    fun settingsDialog_should_OpenOnLlmProvidersCategory_When_InitialCategorySet() {
        val llmCredentialStore = LlmCredentialStore(InMemoryCredentialAccess())
        val llmSettings = LlmSettings(InMemorySettings())
        val registry = LlmProviderRegistry(emptyList())

        composeTestRule.setContent {
            SettingsDialog(
                visible = true,
                onDismiss = {},
                currentTheme = StelekitThemeMode.SYSTEM,
                onThemeChange = {},
                currentLanguage = Language.ENGLISH,
                onLanguageChange = {},
                onReindex = {},
                llmProviderRegistry = registry,
                llmSettings = llmSettings,
                llmCredentialStore = llmCredentialStore,
                initialCategory = SettingsCategory.LLM_PROVIDERS,
            )
        }

        // The category content itself (from LlmProviderListScreen) should already be visible.
        composeTestRule.onNodeWithText("Add provider").assertExists()
    }
}
