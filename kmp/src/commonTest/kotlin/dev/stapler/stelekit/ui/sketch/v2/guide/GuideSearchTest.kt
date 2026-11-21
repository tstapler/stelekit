// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import dev.stapler.stelekit.sketch.v2.tools.ToolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuideSearchTest {
    private val all = GuideSearch.entries(ToolId.entries, listOf(GuideActions.UNDO, GuideActions.REDO, GuideActions.CONSTRAIN, GuideActions.GRID, GuideActions.SNAP, GuideActions.SHORTCUTS))

    private fun titles(query: String) = GuideSearch.rank(query, all).map { it.title }

    @Test
    fun blankQuery_should_MatchNothing() {
        assertTrue(GuideSearch.rank("   ", all).isEmpty())
    }

    @Test
    fun exactTitle_should_BeatPrefixAndSubstring() {
        val ranked = titles("line")
        assertEquals("Line", ranked.first())
    }

    @Test
    fun prefix_should_BeatWordPrefix_And_BothBeatKeyword() {
        val entries = listOf(
            SearchEntry("a", "Toggle grid", SearchKind.Action, SearchAction.Run("a")),
            SearchEntry("b", "Grid size", SearchKind.Action, SearchAction.Run("b")),
            SearchEntry("c", "Snap", SearchKind.Action, SearchAction.Run("c"), listOf("grid")),
        )
        assertEquals(listOf("Grid size", "Toggle grid", "Snap"), GuideSearch.rank("grid", entries).map { it.title })
    }

    @Test
    fun everyWord_should_Match_ForAnEntryToQualify() {
        assertTrue("Constrain" in titles("con"))
        assertTrue("Toggle grid" in titles("tog grid"))
        assertFalse("Toggle snap" in titles("tog zzz"))
        assertTrue(titles("tog grid").indexOf("Toggle grid") < titles("tog grid").indexOf("Toggle snap"))
    }

    @Test
    fun keywords_should_FindAnEntryByAnotherName() {
        assertTrue("Undo" in titles("revert"))
        assertTrue("Constraints" in titles("parallel"))
    }

    @Test
    fun matching_should_IgnoreCase() {
        assertEquals(titles("RECT"), titles("rect"))
        assertTrue("Rectangle" in titles("RECT"))
    }

    @Test
    fun entries_should_OfferOnlyTheToolsAndActionsGiven_AndNoPerToolHelpTopic() {
        val few = GuideSearch.entries(listOf(ToolId.Select), listOf(GuideActions.UNDO))
        assertEquals(1, few.count { it.kind == SearchKind.Tool })
        assertEquals(1, few.count { it.kind == SearchKind.Action })
        assertTrue(few.filter { it.kind == SearchKind.Help }.none { it.id.startsWith("help.tool.") })
        assertIs<SearchAction.UseTool>(few.first { it.kind == SearchKind.Tool }.action)
        assertIs<SearchAction.OpenTopic>(few.first { it.kind == SearchKind.Help }.action)
    }

    @Test
    fun noMatch_should_ReturnEmpty() {
        assertTrue(titles("zzzzqq").isEmpty())
    }
}
