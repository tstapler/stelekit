// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.ui.components.GraphSwitcher
import dev.stapler.stelekit.ui.components.NewGraphDialog
import dev.stapler.stelekit.ui.components.isValidGraphFolderName
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** New-graph flow: path resolution per platform mode, name validation, dialog gating, header description. */
@RunWith(RobolectricTestRunner::class)
class NewGraphFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Fs(
        override val supportsAppOwnedStorage: Boolean,
        override val supportsNativeDirectoryPicker: Boolean,
    ) : FileSystem {
        override fun getDefaultGraphPath() = "/fake"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    // ── path resolution, including the no-picker / no-app-storage mode ──────────────────────

    @Test
    fun `no picker and no app storage uses the name dialog and the browser-private path`() {
        val mode = addGraphFlowMode(Fs(supportsAppOwnedStorage = false, supportsNativeDirectoryPicker = false))
        assertEquals(AddGraphFlowMode.ShowNameDialog, mode)
        assertEquals("/stelekit/Work", newGraphPathFor(mode, parent = "", name = "  Work "))
    }

    @Test
    fun `native picker mode nests the graph under the parent folder`() {
        assertEquals(
            "/home/me/Documents/Work",
            newGraphPathFor(AddGraphFlowMode.ImmediateNativePicker, "/home/me/Documents/", "Work"),
        )
    }

    @Test
    fun `app storage mode has no name-derived path`() {
        assertNull(newGraphPathFor(AddGraphFlowMode.ShowLocationPicker, "", "Work"))
    }

    @Test
    fun `graph folder names reject separators and dot segments`() {
        assertTrue(isValidGraphFolderName("Work notes"))
        listOf("", "  ", ".", "..", "a/b", "a\\b", "c:d").forEach { assertFalse(isValidGraphFolderName(it), it) }
    }

    // ── dialog gating ───────────────────────────────────────────────────────────────────────

    @Test
    fun `Create button follows canCreate and fires onCreate`() {
        var created = false
        composeTestRule.setContent {
            MaterialTheme {
                NewGraphDialog(
                    name = "Work", onNameChange = {}, description = "", onDescriptionChange = {},
                    canCreate = true, onCreate = { created = true }, onDismiss = {},
                    location = { Text("location slot") },
                )
            }
        }
        composeTestRule.onNodeWithText("location slot").assertExists()
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()
        assertTrue(created)
    }

    @Test
    fun `Create button is disabled when canCreate is false and invalid name shows an error`() {
        composeTestRule.setContent {
            MaterialTheme {
                NewGraphDialog(
                    name = "a/b", onNameChange = {}, description = "", onDescriptionChange = {},
                    canCreate = false, onCreate = {}, onDismiss = {}, location = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Can't contain / \\ or :").assertExists()
    }

    // ── header description ──────────────────────────────────────────────────────────────────

    private fun switcher(description: String) {
        val graph = GraphInfo(GraphId("g1"), "/x/notes", "Notes", 0L, description = description)
        composeTestRule.setContent {
            MaterialTheme {
                GraphSwitcher(
                    currentGraphName = "Notes",
                    availableGraphs = listOf(graph),
                    activeGraphId = "g1",
                    onGraphSelected = {}, onAddGraph = {}, onRemoveGraph = {},
                )
            }
        }
    }

    @Test
    fun `header shows the active graph description`() {
        switcher("Team wiki")
        composeTestRule.onNodeWithText("Team wiki").assertExists()
    }

    @Test
    fun `header omits the description line when blank`() {
        switcher("")
        composeTestRule.onNodeWithText("Notes").assertExists()
        composeTestRule.onNodeWithText("Team wiki").assertDoesNotExist()
    }
}
