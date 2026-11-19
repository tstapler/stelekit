// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class PhoneMapSettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

/** The owner's phone is a narrow, short screen: a created project and the New sketch action must stay reachable on it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp")
class ProjectListPhoneLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun viewModel(): ProjectListViewModel {
        val raw = PhoneMapSettings()
        return ProjectListViewModel(
            inMemorySketchRepositories().projects, "g1", ProjectBridgeSettings(raw), LlmSettings(raw), { it() }, { 1L },
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun createdProjectIsVisibleOnAPhoneScreen() {
        val vm = viewModel()
        rule.setContent { MaterialTheme { ProjectListScreen(vm, onOpenProject = {}) } }
        vm.createProject("Garden shed")
        rule.waitForIdle()
        rule.onNodeWithText("Garden shed").assertIsDisplayed()
    }

    /** Once a project exists the screen also shows the source picker, drawings and registry controls; the new row must stay on screen. */
    @Test
    fun createdProjectStaysVisibleBesideTheTallSettingsPanels() {
        val state = ProjectListUiState(projects = listOf(ProjectRow("u1", "proj-0a1b2c", "Garden shed")))
        rule.setContent {
            MaterialTheme {
                ProjectListContent(
                    state = state, onCreate = {}, onDismissMessage = {}, onOpen = {},
                    settings = { Column { repeat(14) { TextButton(onClick = {}) { Text("Setting $it") } } } },
                )
            }
        }
        rule.onNodeWithText("Garden shed").assertIsDisplayed()
    }
}
