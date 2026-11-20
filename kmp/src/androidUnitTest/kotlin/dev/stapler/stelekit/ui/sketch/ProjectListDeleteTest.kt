// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.SketchDeleteService
import dev.stapler.stelekit.sketch.StandaloneSketchService
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEngineStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class DeleteTestSettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

/** Deleting a sketch from the Sketches list: confirm first, cancel keeps it (Gradle-only Compose host test, see BUILD.bazel). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp")
class ProjectListDeleteTest {
    @get:Rule
    val rule = createComposeRule()

    private fun deletableViewModel(): ProjectListViewModel {
        val repos = inMemorySketchRepositories()
        val raw = DeleteTestSettings()
        val write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit> = { it() }
        return ProjectListViewModel(
            repos.projects, "g1", ProjectBridgeSettings(raw), LlmSettings(raw), { it() }, { 1L },
            newSketch = StandaloneSketchService(repos.projects, write, null, { 1L }),
            deleter = SketchDeleteService(repos.projects, { null }, write),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun aSketchIsDeletedOnlyAfterTheConfirmation() {
        val vm = deletableViewModel()
        rule.setContent { MaterialTheme { ProjectListScreen(vm, onOpenProject = {}) } }
        vm.createSketch {}
        rule.waitForIdle()

        rule.onNodeWithTag(SKETCH_DELETE_ROW_TAG).performClick()
        rule.onNodeWithText(SketchEngineStrings.SketchDelete.TITLE).assertIsDisplayed()
        rule.onNodeWithText(SketchEngineStrings.SketchDelete.CANCEL).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(SKETCH_DELETE_ROW_TAG).assertCountEquals(1)

        rule.onNodeWithTag(SKETCH_DELETE_ROW_TAG).performClick()
        rule.onNodeWithTag(SKETCH_DELETE_CONFIRM_TAG).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(SKETCH_DELETE_ROW_TAG).assertCountEquals(0)
    }
}
