package dev.stapler.stelekit.ui.library

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryFilterRulesPropertyTest {
    private val chips: List<LibraryChip> =
        LibraryMediaClass.entries.map { LibraryChip.Type(it) } +
            listOf(LibraryChip.Annotated, LibraryChip.Orphaned, LibraryChip.Tag("site"), LibraryChip.Tag("kitchen"))

    @Test
    fun noSequenceOfEnabledChipTaps_yieldsAStateNormalizeWouldChange() = runTest {
        checkAll(Arb.list(Arb.element(chips), 0..25)) { taps ->
            val end = taps.fold(LibraryFilterState()) { s, chip -> LibraryFilterRules.toggle(s, chip) }
            assertEquals(end, LibraryFilterRules.normalize(end), "taps=$taps")
        }
    }

    @Test
    fun normalize_isIdempotent_forArbitraryRestoredState() = runTest {
        checkAll(Arb.list(Arb.element(chips), 0..12), Arb.list(Arb.element(chips), 0..12)) { a, b ->
            // A possibly contradictory state built field by field, as a restore from storage would.
            val types = (a + b).filterIsInstance<LibraryChip.Type>().map { it.mediaClass }.toSet()
            val tags = (a + b).filterIsInstance<LibraryChip.Tag>().map { it.tag }.toSet()
            val raw = LibraryFilterState(
                types = types, annotated = LibraryChip.Annotated in a, orphaned = LibraryChip.Orphaned in b, tags = tags, search = " x ",
            )
            val once = LibraryFilterRules.normalize(raw)
            assertEquals(once, LibraryFilterRules.normalize(once))
        }
    }
}
