package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.I18n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavLabelUniquenessTest {
    private fun label(spec: NavDestinationSpec) = I18n.get(spec.labelKey)

    @Test
    fun noTwoSpecsShareALabelOrAShortLabel() {
        val labels = NavModel.allSpecs.map(::label)
        assertEquals(labels.toSet().size, labels.size, "duplicate labels: $labels")
        val shorts = NavModel.allSpecs.mapNotNull { it.shortLabelKey }.map { I18n.get(it) }
        assertTrue(shorts.none { it in labels }, "a short label collides with a row label: $shorts vs $labels")
    }

    @Test
    fun libraryBelongsOnlyToTheLibraryScreen_andStatsIsGraphStats() {
        val owners = NavModel.allSpecs.filter { label(it) == "Library" }
        assertEquals(listOf(Screen.Library()), owners.map { it.screen })
        assertEquals("Graph Stats", label(NavModel.graphStats))
        assertFalse(NavModel.allSpecs.any { label(it) == "Library Stats" })
    }

    @Test
    fun everyKeyResolvesToRealText_notTheKeyItself() {
        for (spec in NavModel.allSpecs) {
            for (key in listOfNotNull(spec.labelKey, spec.descriptionKey, spec.shortLabelKey)) {
                assertTrue(I18n.get(key) != key, "missing translation for $key")
            }
        }
        for (section in NavModel.sections(Screen.Logs())) assertTrue(I18n.get(section.headerKey) != section.headerKey)
    }

    @Test
    fun descriptionsAreShort() {
        for (spec in NavModel.allSpecs) {
            val words = I18n.get(spec.descriptionKey).split(" ").size
            assertTrue(words <= MAX_DESC_WORDS, "${spec.id} description has $words words")
        }
    }

    private companion object {
        const val MAX_DESC_WORDS = 10
    }
}
