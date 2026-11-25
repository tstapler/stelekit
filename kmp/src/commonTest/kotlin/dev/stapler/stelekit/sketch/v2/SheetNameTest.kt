// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SheetNameError
import dev.stapler.stelekit.sketch.v2.commands.SheetNameExtras
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.sheetName
import dev.stapler.stelekit.sketch.v2.commands.withSheetName
import dev.stapler.stelekit.sketch.v2.merge.SceneMerge
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.search.SketchTextExtractor
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.ui.sketch.v2.EditorPorts
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import dev.stapler.stelekit.ui.sketch.v2.renameSheet
import dev.stapler.stelekit.ui.sketch.v2.sheetName
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sheet's name: one optional `extras.sheetName`, validated on rename, round-tripped by the codec, merged per key, searchable. */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetNameTest {
    private val limit = SceneLimits.Default.maxNameChars

    @Test
    fun validate_should_TrimAndJoinLines() {
        assertEquals("Kitchen plan", SheetNameExtras.validate("  Kitchen plan \n").getOrNull())
        assertEquals("Ground floor east", SheetNameExtras.validate("Ground floor\neast").getOrNull())
        assertEquals("a b c", SheetNameExtras.validate("a\u2028b\u0085c").getOrNull(), "line and paragraph separators are line breaks too")
    }

    @Test
    fun validate_should_RefuseBlankAndWhitespace() {
        assertEquals(SheetNameError.Blank, SheetNameExtras.validate("").leftOrNull())
        assertEquals(SheetNameError.Blank, SheetNameExtras.validate("   \t\n ").leftOrNull())
    }

    @Test
    fun validate_should_RefuseANamePastTheLimit_AndAcceptOneAtIt() {
        assertEquals("a".repeat(limit), SheetNameExtras.validate("a".repeat(limit)).getOrNull())
        assertEquals(SheetNameError.TooLong(limit), SheetNameExtras.validate("a".repeat(limit + 1)).leftOrNull())
        assertEquals("a".repeat(limit), SheetNameExtras.validate("  " + "a".repeat(limit) + "  ").getOrNull(), "the limit counts the trimmed name")
    }

    @Test
    fun anyAcceptedName_should_BeOneTrimmedLineWithinTheLimit() = runTest {
        checkAll(Arb.string(0, limit * 2)) { raw ->
            SheetNameExtras.validate(raw).onRight { name ->
                assertTrue(name.isNotEmpty() && name.length <= limit && name == name.trim() && name.none { it.isISOControl() || it == '\u2028' })
            }
        }
    }

    @Test
    fun codec_should_RoundTripTheName() {
        val doc = SketchDocumentV2(sheetUuid = "s1").withSheetName("Bathroom \"upstairs\" ✓")
        val decoded = SheetCodecV2.decode(SheetCodecV2.encode(doc).getOrNull()!!).getOrNull()!!
        assertEquals("Bathroom \"upstairs\" ✓", decoded.sheetName)
        assertEquals(doc.extras, decoded.extras)
    }

    @Test
    fun anUnnamedSheet_should_KeepItsExactBytes() {
        val doc = SketchDocumentV2(sheetUuid = "s1")
        val before = SheetCodecV2.encode(doc).getOrNull()!!.decodeToString()
        assertFalse(SheetNameExtras.KEY in before)
        assertEquals(before, SheetCodecV2.encode(doc.withSheetName(null)).getOrNull()!!.decodeToString())
        assertNull(doc.sheetName)
    }

    @Test
    fun read_should_IgnoreANonStringAndCutAnOverlongName() {
        val number = SketchDocumentV2(sheetUuid = "s1").copy(extras = kotlinx.serialization.json.JsonObject(mapOf(SheetNameExtras.KEY to JsonPrimitive(3))))
        assertNull(number.sheetName)
        val long = SketchDocumentV2(sheetUuid = "s1").withSheetName("b".repeat(limit + 5))
        assertEquals(limit, long.sheetName?.length)
        val emoji = SketchDocumentV2(sheetUuid = "s1").withSheetName("a".repeat(limit - 1) + "\uD83D\uDE00")
        assertEquals("a".repeat(limit - 1), emoji.sheetName, "a cut never leaves half of a surrogate pair")
    }

    @Test
    fun merge_should_KeepARenameOnOneSide_AndAnElementEditOnTheOther() {
        val base = SceneMergeSupport.doc(SceneMergeSupport.rect("r1"))
        val ours = base.withSheetName("Garage")
        val theirs = SceneMergeSupport.withElements(base, listOf(SceneMergeSupport.rect("r1"), SceneMergeSupport.rect("r2", x = 40, z = "a2")))
        val merged = SceneMerge.merge(base, ours, theirs).getOrNull()!!.doc
        assertEquals("Garage", merged.sheetName)
        assertEquals(setOf("r1", "r2"), SceneMergeSupport.ids(merged))
    }

    @Test
    fun merge_should_TakeTheRename_When_OnlyTheOtherSideRenamed() {
        val base = SceneMergeSupport.doc(SceneMergeSupport.rect("r1"))
        val theirs = base.withSheetName("Shed")
        val ours = SceneMergeSupport.withElements(base, listOf(SceneMergeSupport.rect("r1", x = 5, ver = 2)))
        assertEquals("Shed", SceneMerge.merge(base, ours, theirs).getOrNull()!!.doc.sheetName)
    }

    @Test
    fun searchText_should_IncludeTheName() {
        val text = SketchTextExtractor.extract(SketchDocumentV2(sheetUuid = "s1").withSheetName("Laundry Room"))
        assertTrue("laundry room" in text.body.lines())
        assertEquals(SketchTextExtractor.extract(SketchDocumentV2(sheetUuid = "s1")).body, "", "an unnamed empty sheet indexes nothing")
    }

    @Test
    fun renameSheet_should_ShowTheName_AndAddNoUndoStep() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var n = 0
        val c = SketchEditorController(
            SceneMergeSupport.doc(SceneMergeSupport.rect("r1")).copy(sheetUuid = "s1"),
            EditorPorts(StandardToolRegistry.create(FixedAdvanceTextMetricsProvider()), ids = ElementIdSource { ElementId("n${n++}") }),
            dispatcher,
        )
        assertNull(c.sheetName)
        assertEquals(SheetNameError.Blank, c.renameSheet("  ").leftOrNull())
        assertNull(c.sheetName, "a refused name changes nothing")
        assertEquals("Porch", c.renameSheet(" Porch ").getOrNull())
        assertEquals("Porch", c.sheetName)
        assertFalse(c.canUndo, "a rename is a sheet property, not an undo step")
        c.close()
    }
}
