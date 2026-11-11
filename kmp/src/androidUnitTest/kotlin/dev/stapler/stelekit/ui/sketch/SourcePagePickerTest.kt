// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.projectbridge.BoundSourceRow
import dev.stapler.stelekit.projectbridge.PickerPage
import dev.stapler.stelekit.projectbridge.SourceBindingKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SourcePagePickerTest {
    @get:Rule
    val rule = createComposeRule()

    private val searches = mutableListOf<String>()
    private val bound = mutableListOf<Pair<SourceBindingKind, String>>()
    private val removed = mutableListOf<Pair<SourceBindingKind, String>>()

    private fun show(state: ProjectListUiState) = rule.setContent {
        MaterialTheme {
            SourcePagePicker(state, { searches += it }, { k, id -> bound += k to id }, { k, id -> removed += k to id })
        }
    }

    @Test
    fun searchBindAsTableAndAsNotes() {
        show(ProjectListUiState(pickerResults = listOf(PickerPage("u1", "Closet Plan"))))
        rule.onNodeWithText("Find a page").performTextInput("clo")
        assertEquals("clo", searches.last())
        rule.onNodeWithText("Closet Plan").assertIsDisplayed()
        rule.onNodeWithText("Use as table").performClick()
        rule.onNodeWithText("Use as notes").performClick()
        assertEquals(listOf(SourceBindingKind.TABLE to "u1", SourceBindingKind.PROSE to "u1"), bound)
    }

    @Test
    fun boundSourcesShowTitlesAndCanBeRemoved() {
        show(ProjectListUiState(boundSources = listOf(BoundSourceRow(SourceBindingKind.TABLE, "u1", "Closet Plan"), BoundSourceRow(SourceBindingKind.PROSE, "u2", null))))
        rule.onNodeWithText("Closet Plan (table)").assertIsDisplayed()
        rule.onNodeWithText("Missing page (notes)").assertIsDisplayed()
        rule.onAllNodesWithText("Remove")[0].performClick()
        assertEquals(SourceBindingKind.TABLE to "u1", removed.single())
    }
}
