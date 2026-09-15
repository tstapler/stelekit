// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.settings.GeneralSettings
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/** Coverage for Story 1.4.2 — [FirstRunHotkeyNotice] and its Settings revisit row. */
class FirstRunHotkeyNoticeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun firstRunHotkeyNotice_should_ShowExactlyOnceThenBeRevisitableFromSettings_When_InstallFlagPersists() {
        val settings = InMemorySettings()
        val flagKey = "capture.firstRunNoticeShown"
        val combo = "Ctrl+Shift+Space"

        composeTestRule.setContent {
            MaterialTheme {
                var noticeVisible by remember { mutableStateOf(!settings.getBoolean(flagKey, false)) }
                Column {
                    if (noticeVisible) {
                        FirstRunHotkeyNotice(
                            hotkeyCombo = combo,
                            onDismiss = {
                                settings.putBoolean(flagKey, true)
                                noticeVisible = false
                            },
                        )
                    }
                    // Always rendered, independent of the one-time flag — the revisit path.
                    GeneralSettings(
                        currentTheme = StelekitThemeMode.SYSTEM,
                        onThemeChange = {},
                        currentLanguage = Language.ENGLISH,
                        onLanguageChange = {},
                        hotkeyComboLabel = combo,
                    )
                }
            }
        }

        // Shown on first composition, since the persisted flag starts false.
        composeTestRule.onNodeWithTag("firstRunHotkeyNotice").assertExists()
        composeTestRule.onNodeWithText("New: Quick Capture — Press $combo anywhere to capture a note into today's journal.").assertExists()

        composeTestRule.onNodeWithTag("firstRunHotkeyNoticeDismiss").performClick()

        assertTrue(settings.getBoolean(flagKey, false), "dismissing must persist the shown flag")
        composeTestRule.onNodeWithTag("firstRunHotkeyNotice").assertDoesNotExist()

        // Still revisitable afterward, independent of the one-time flag.
        composeTestRule.onNodeWithText("Keyboard Shortcuts").assertExists()
        composeTestRule.onNodeWithText(combo).assertExists()
    }
}
