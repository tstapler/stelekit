// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only
@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchSearchRow
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.runtime.persist.SketchTextSearch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The Sketches section of global search: it lists matches, opens one on tap, and shows nothing for a blank query. */
class SketchSearchSectionTest {
    @get:Rule
    val rule = createComposeRule()

    private fun search(): SketchTextSearch {
        val repos = inMemorySketchRepositories()
        runBlocking {
            repos.projects.saveProject(SketchTestSupport.project())
            repos.searchText.save(SketchSearchRow("p1", "s1", "kitchen pantry", "h", 1L))
        }
        return SketchTextSearch(repos, { null }, { it() }, { 1L })
    }

    @Test
    fun section_should_ListTheMatchAndOpenItsSketch_When_TheQueryMatches() {
        var opened: String? = null
        val search = search()
        rule.setContent { MaterialTheme { SketchSearchSection("pantry", search, { opened = it }, debounceMs = 0) } }
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("kitchen pantry").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Sketches").assertExists()
        rule.onNodeWithText("Kitchen").performClick()
        assertEquals("p1", opened)
    }

    @Test
    fun section_should_ShowNothing_When_TheQueryIsBlank() {
        val search = search()
        rule.setContent { MaterialTheme { SketchSearchSection("   ", search, {}, debounceMs = 0) } }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Sketches").fetchSemanticsNodes().size)
    }

    @Test
    fun section_should_ShowNothing_When_NothingMatches() {
        val search = search()
        rule.setContent { MaterialTheme { SketchSearchSection("bathroom", search, {}, debounceMs = 0) } }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("Sketches").fetchSemanticsNodes().size)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
