// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import org.junit.Rule
import org.junit.Test

/**
 * Coverage for the pre-repos "Initializing…" loading screen's escape hatch (see
 * [InitializingScreenThemed]'s own doc) — previously zero test coverage existed for this
 * user-facing feature. Follows [MigrationReadyLoadingTest]'s pattern: mounts the composable in
 * isolation via [createComposeRule], not the whole [StelekitApp].
 */
class InitializingScreenThemedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settingsButton_opensSettingsDialog_onDeveloperCategory() {
        composeTestRule.setContent {
            InitializingScreenThemed(platformSettings = InMemorySettings())
        }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()

        // DeveloperSettings' content proves the dialog opened directly on SettingsCategory.
        // DEVELOPER, not just that some dialog appeared.
        composeTestRule.onNodeWithText("Use libsql JNI driver").assertIsDisplayed()
    }

    @Test
    fun onlySettingsButton_isReachable_noPerformanceOrLogs() {
        // Security fix regression: Performance/Logs must not be reachable from this
        // zero-auth pre-repos screen — see InitializingScreenThemed's doc.
        composeTestRule.setContent {
            InitializingScreenThemed(platformSettings = InMemorySettings())
        }

        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Performance").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Logs").assertDoesNotExist()
    }

    @Test
    fun libsqlToggle_roundTripsThroughPlatformSettings() {
        val settings = InMemorySettings()
        composeTestRule.setContent {
            InitializingScreenThemed(platformSettings = settings)
        }

        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Active: system SQLite. Reload the graph to apply.").assertIsDisplayed()
        assertFalseInitially(settings)

        composeTestRule.onNode(isToggleable()).performClick()

        composeTestRule.onNodeWithText(
            "Active: libsql JNI driver (WAL mode). Reload the graph to apply."
        ).assertIsDisplayed()
        assert(settings.getBoolean("db.libsql.enabled", false)) {
            "expected the toggle to have written db.libsql.enabled=true back to platformSettings"
        }
    }

    private fun assertFalseInitially(settings: InMemorySettings) {
        assert(!settings.getBoolean("db.libsql.enabled", false)) {
            "expected db.libsql.enabled to default to false"
        }
    }
}
