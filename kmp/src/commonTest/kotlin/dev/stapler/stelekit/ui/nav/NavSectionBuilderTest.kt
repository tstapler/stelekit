package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.library.LibraryFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavSectionBuilderTest {
    private val screens = listOf(Screen.Journals, Screen.Library(), Screen.Logs(), Screen.Performance, Screen.AllPages(), Screen.LibraryStats)

    @Test
    fun noDestinationAppearsTwice_inAnySectionList() {
        for (current in screens) {
            val ids = NavModel.sections(current).flatMap { it.items }.map { it.id }
            assertEquals(ids.toSet().size, ids.size, "duplicate row for $current: $ids")
        }
    }

    @Test
    fun libraryIsTheFirstRowOfTheGraphSection() {
        val graph = NavModel.sections(Screen.Journals).single { it.id == "graph" }
        assertEquals(NavModel.library, graph.items.first())
        assertEquals(listOf("library", "graph-stats", "git-setup"), graph.items.map { it.id })
    }

    @Test
    fun developerSectionIsAbsentUnlessOnLogsOrPerformance_andThenLast() {
        assertFalse(NavModel.sections(Screen.Journals).any { it.id == "developer" })
        for (dev in listOf(Screen.Logs(), Screen.Performance)) {
            val sections = NavModel.sections(dev)
            assertEquals("developer", sections.last().id)
            assertEquals(listOf("logs", "performance"), sections.last().items.map { it.id })
        }
    }

    @Test
    fun rowsAboveDeveloperDoNotMoveWhenItAppears() {
        val without = NavModel.sections(Screen.Journals)
        val with = NavModel.sections(Screen.Logs())
        assertEquals(without, with.dropLast(1))
    }

    @Test
    fun aliasesAreRedirectsNeverRows() {
        val rows = NavModel.sections(Screen.Journals).flatMap { it.items }.mapNotNull { it.screen }
        assertTrue(rows.none { it is Screen.Gallery || it is Screen.AssetBrowser })
        assertEquals(
            NavEntry.NonNavRedirect(Screen.Library(initialFilter = LibraryFilter.Annotated)),
            NavModel.forScreen(Screen.Gallery),
        )
        assertEquals(
            NavEntry.NonNavRedirect(Screen.Library(initialSearch = "kitchen")),
            NavModel.forScreen(Screen.AssetBrowser("kitchen")),
        )
    }

    @Test
    fun legacyAliasResolvesToLibrary_andLeavesOtherScreensAlone() {
        assertEquals(Screen.Library(initialFilter = LibraryFilter.Annotated), LegacyScreenAlias.resolve(Screen.Gallery))
        assertEquals(Screen.Library(initialSearch = "q"), LegacyScreenAlias.resolve(Screen.AssetBrowser("q")))
        assertEquals(Screen.Journals, LegacyScreenAlias.resolve(Screen.Journals))
        assertEquals("Library", LegacyScreenAlias.resolveTracingKey("Gallery"))
        assertEquals("Library", LegacyScreenAlias.resolveTracingKey("AssetBrowser"))
    }

    @Test
    fun bottomBarShowsPagesWhileTheRowSaysAllPages() {
        assertEquals("Pages", I18n.get(checkNotNullKey(NavModel.allPages.shortLabelKey)))
        assertEquals("All pages", I18n.get(NavModel.allPages.labelKey))
        assertNull(NavModel.library.shortLabelKey)
    }

    private fun checkNotNullKey(k: String?): String = k ?: error("missing short label")
}
