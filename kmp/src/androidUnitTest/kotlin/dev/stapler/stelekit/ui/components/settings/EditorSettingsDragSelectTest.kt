// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.components.MULTI_BLOCK_DRAG_SELECT_KEY
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@Config(sdk = [29], qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class EditorSettingsDragSelectTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private class MapSettings : Settings {
        val bools = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, defaultValue: Boolean) = bools[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { bools[key] = value }
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) {}
        override fun containsKey(key: String) = bools.containsKey(key)
    }

    @Test
    fun dragSelectToggle_should_PersistFlag_WhenClicked() {
        val settings = MapSettings().apply { bools[MULTI_BLOCK_DRAG_SELECT_KEY] = false }
        composeTestRule.setContent { MaterialTheme { EditorSettings(annotatorSettings = settings) } }

        composeTestRule.onNodeWithText(DRAG_SELECT_LABEL).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(true, settings.bools[MULTI_BLOCK_DRAG_SELECT_KEY])
    }
}
