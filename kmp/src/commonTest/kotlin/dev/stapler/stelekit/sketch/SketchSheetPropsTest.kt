package dev.stapler.stelekit.sketch

import arrow.core.Either
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchSheetPropsTest {
    private fun <A> Either<*, A>.ok(): A = fold({ error("unexpected $it") }, { it })
    private fun <L> Either<L, *>.err(): L = fold({ it }, { error("expected a failure, got $it") })
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    // ---- DimensionInput ----

    @Test
    fun typedLengthsParseToExactSixteenths() {
        assertEquals(Inches(12 * 16 + 3), DimensionInput.parse("12 3/16").ok())
        assertEquals(Inches(40 * 16), DimensionInput.parse("40\"").ok())
        assertEquals(Inches(52 * 16), DimensionInput.parse("4' 4\"").ok())
        assertEquals(Inches(8), DimensionInput.parse("1/2\"").ok())
    }

    @Test
    fun invalidTextNamesTheProblemAndGivesAnExample() {
        val cases = mapOf(
            "" to DimensionInputError.EMPTY,
            "   " to DimensionInputError.EMPTY,
            "abc" to DimensionInputError.NOT_A_LENGTH,
            "51-54\"" to DimensionInputError.NOT_A_SINGLE_LENGTH,
            "37.9\"" to DimensionInputError.NOT_WHOLE_SIXTEENTH,
            "0\"" to DimensionInputError.NOT_POSITIVE,
            "-3\"" to DimensionInputError.NOT_POSITIVE,
            "999999999\"" to DimensionInputError.TOO_LARGE,
        )
        for ((text, expected) in cases) {
            val error = DimensionInput.parse(text).err()
            assertEquals(expected, error, "text=$text")
            assertTrue(error.message.contains("12 3/16"), error.message)
        }
    }

    @Test
    fun formattedLengthsReparseToTheSameValue() = runTest {
        checkAll(Arb.long(1L..100_000L), Arb.of(LengthFormat.entries)) { sixteenths, format ->
            val text = LengthFormatter.format(Inches(sixteenths), format)
            assertEquals(Inches(sixteenths), DimensionInput.parse(text).ok(), "text=$text")
        }
    }

    // ---- sheet kinds, direction, layers ----

    @Test
    fun blankSheetsTakeTheirKindsDefaults() {
        assertEquals(ViewDirection.TOP, SheetProps.blank(SketchSheetKind.PLAN).viewDirection)
        assertEquals(ViewDirection.FROM_FRONT, SheetProps.blank(SketchSheetKind.ELEVATION).viewDirection)
        assertEquals(8L, SheetProps.blank(SketchSheetKind.SECTION).scaleSixteenths)
        val switched = SheetProps(SketchSheetKind.ELEVATION, ViewDirection.FROM_BACK, 4).withKind(SketchSheetKind.SECTION)
        assertEquals(ViewDirection.FROM_FRONT, switched.viewDirection)
        assertEquals(8L, switched.scaleSixteenths)
    }

    @Test
    fun fromBackMirrorsLabelOrderAndTwiceIsIdentity() = runTest {
        assertEquals(listOf("c", "b", "a"), ViewDirection.FROM_BACK.labelOrder(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), ViewDirection.FROM_FRONT.labelOrder(listOf("a", "b", "c")))
        checkAll(Arb.list(Arb.int(), 0..20), Arb.of(ViewDirection.entries)) { keys, dir ->
            assertEquals(keys, dir.labelOrder(dir.labelOrder(keys)))
        }
    }

    @Test
    fun layerEncodingRoundTripsAndStoredJunkFallsBack() = runTest {
        checkAll(Arb.of("A", "wall 1", "trim-2"), Arb.of(true, false), Arb.of(true, false)) { name, visible, locked ->
            val layer = SketchLayer(name, visible, locked)
            assertEquals(layer, SketchLayer.decode(layer.encode()))
        }
        assertNull(SketchLayer.decode("bad;name"))
        assertNull(SketchLayer.decode(" "))
        val props = SheetProps.fromStored("mystery", "sideways", -5, listOf("A;hidden", "bad;x", "A"))
        assertEquals(SketchSheetKind.PLAN, props.kind)
        assertEquals(ViewDirection.TOP, props.viewDirection)
        assertEquals(16L, props.scaleSixteenths)
        assertEquals(listOf(SketchLayer("A", visible = false)), props.layers)
        assertEquals(ViewDirection.FROM_FRONT, SheetProps.fromStored("elevation", "top", 16, emptyList()).viewDirection)
    }

    // ---- commands ----

    private fun docWith(vararg prims: SketchPrimitive) =
        SketchDocument(scene = prims.fold(SketchScene.EMPTY) { s, p -> s.add(p).ok() })

    @Test
    fun typingALineLengthMovesTheFarEndAlongTheLine() {
        val doc = docWith(SketchPrimitive.Line("h", pt(16, 16), pt(-64, 16)), SketchPrimitive.Line("v", pt(0, 0), pt(0, 32)))
        val h = SketchCommand.SetLineLength("h", Inches(100)).apply(doc).ok().scene.primitives.first { it.id == "h" } as SketchPrimitive.Line
        assertEquals(pt(16 - 100, 16), h.b)
        val v = SketchCommand.SetLineLength("v", Inches(7)).apply(doc).ok().scene.primitives.first { it.id == "v" } as SketchPrimitive.Line
        assertEquals(pt(0, 7), v.b)
        assertEquals(listOf("h", "v"), SketchCommand.SetLineLength("h", Inches(1)).apply(doc).ok().scene.primitives.map { it.id })
    }

    @Test
    fun lineLengthRefusesWhatHasNoExactLength() {
        val doc = docWith(
            SketchPrimitive.Line("slant", pt(0, 0), pt(16, 16)),
            SketchPrimitive.Line("dot", pt(5, 5), pt(5, 5)),
            SketchPrimitive.Rect("r", pt(0, 0), pt(16, 16)),
        )
        assertIs<CommandError.NotAxisAligned>(SketchCommand.SetLineLength("slant", Inches(8)).apply(doc).err())
        assertIs<CommandError.ZeroLengthLine>(SketchCommand.SetLineLength("dot", Inches(8)).apply(doc).err())
        assertIs<CommandError.NotALine>(SketchCommand.SetLineLength("r", Inches(8)).apply(doc).err())
        assertIs<CommandError.UnknownPrimitive>(SketchCommand.SetLineLength("none", Inches(8)).apply(doc).err())
        val far = docWith(SketchPrimitive.Line("f", pt(SketchScene.MAX_COORD_SIXTEENTHS - 16, 0), pt(SketchScene.MAX_COORD_SIXTEENTHS, 0)))
        assertIs<CommandError.Scene>(SketchCommand.SetLineLength("f", Inches(32)).apply(far).err())
    }

    @Test
    fun hiddenLayersLeaveTheVisibleSceneAndLockedLayersRefuseEdits() {
        var doc = docWith(SketchPrimitive.Line("a", pt(0, 0), pt(16, 0)), SketchPrimitive.Line("b", pt(0, 8), pt(16, 8)))
        doc = SketchCommand.AddLayer("wall").apply(doc).ok()
        doc = SketchCommand.SetPrimitiveLayer("a", "wall").apply(doc).ok()
        assertEquals(2, doc.visibleScene.size)
        doc = SketchCommand.SetLayerVisible("wall", false).apply(doc).ok()
        assertEquals(listOf("b"), doc.visibleScene.primitives.map { it.id })
        doc = SketchCommand.SetLayerLocked("wall", true).apply(doc).ok()
        assertEquals(CommandError.LayerLocked("wall"), SketchCommand.RemovePrimitive("a").apply(doc).err())
        assertEquals(CommandError.LayerLocked("wall"), SketchCommand.SetLineLength("a", Inches(3)).apply(doc).err())
        assertEquals(CommandError.LayerLocked("wall"), SketchCommand.SetPrimitiveLayer("b", "wall").apply(doc).err())
        assertEquals(CommandError.LayerLocked("wall"), SketchCommand.SetPrimitiveLayer("a", "").apply(doc).err())
        assertEquals(1, SketchCommand.RemovePrimitive("b").apply(doc).ok().scene.size)
    }

    @Test
    fun movingARectToALayerKeepsItsCorners() {
        val rect = SketchPrimitive.Rect("r", pt(0, 0), pt(16, 32))
        val moved = rect.withLayer("wall") as SketchPrimitive.Rect
        assertEquals("wall", moved.layer)
        assertEquals(rect.copy(layer = "wall"), moved)
    }

    @Test
    fun layerNamesAndSheetSettingsAreValidated() {
        val doc = SketchDocument()
        assertIs<CommandError.InvalidLayerName>(SketchCommand.AddLayer(" ").apply(doc).err())
        assertIs<CommandError.InvalidLayerName>(SketchCommand.AddLayer("a;b").apply(doc).err())
        val withA = SketchCommand.AddLayer("A").apply(doc).ok()
        assertIs<CommandError.DuplicateLayer>(SketchCommand.AddLayer("A").apply(withA).err())
        assertIs<CommandError.UnknownLayer>(SketchCommand.SetLayerVisible("Z", false).apply(withA).err())
        assertIs<CommandError.DirectionNotAllowed>(SketchCommand.SetViewDirection(ViewDirection.FROM_BACK).apply(doc).err())
        assertIs<CommandError.InvalidScale>(SketchCommand.SetScale(0).apply(doc).err())
        assertIs<CommandError.InvalidScale>(SketchCommand.SetScale(SheetProps.MAX_SCALE_SIXTEENTHS + 1).apply(doc).err())
        val elevation = SketchCommand.SetSheetKind(SketchSheetKind.ELEVATION).apply(doc).ok()
        assertEquals(ViewDirection.FROM_BACK, SketchCommand.SetViewDirection(ViewDirection.FROM_BACK).apply(elevation).ok().sheet.viewDirection)
        (1..SheetProps.MAX_LAYERS).fold(SketchDocument()) { d, i -> SketchCommand.AddLayer("L$i").apply(d).ok() }
            .let { assertEquals(CommandError.TooManyLayers, SketchCommand.AddLayer("extra").apply(it).err()) }
    }

    @Test
    fun everyRefusalHasAPlainLanguageMessage() {
        val all = listOf(
            CommandError.Scene(SceneError.CapacityExceeded(1)), CommandError.Scene(SceneError.DuplicateId("x")),
            CommandError.Scene(SceneError.CoordinateOutOfRange("x")), CommandError.UnknownPrimitive("x"), CommandError.UnknownZone("x"),
            CommandError.DuplicateZone("x"), CommandError.UnknownLayer("x"), CommandError.DuplicateLayer("x"), CommandError.InvalidLayerName("x"),
            CommandError.LayerLocked("x"), CommandError.LayerLocked(""), CommandError.TooManyLayers, CommandError.NotALine("x"),
            CommandError.NotAxisAligned("x"), CommandError.ZeroLengthLine("x"), CommandError.InvalidScale(0),
            CommandError.DirectionNotAllowed(SketchSheetKind.PLAN, ViewDirection.FROM_BACK),
        )
        all.forEach { assertTrue(it.message().isNotBlank(), "$it") }
    }

    @Test
    fun sheetChangesAreOneUndoStepEach() {
        var h = SketchHistory.of()
        h = h.commit(SketchCommand.SetSheetKind(SketchSheetKind.SECTION).apply(h.present).ok())
        h = h.commit(SketchCommand.SetScale(4).apply(h.present).ok())
        assertEquals(2, h.undoDepth)
        assertEquals(8L, h.undo().present.sheet.scaleSixteenths)
        assertEquals(SketchSheetKind.PLAN, h.undo().undo().present.sheet.kind)
        val same = h.commit(SketchCommand.SetScale(4).apply(h.present).ok())
        assertEquals(2, same.undoDepth)
    }

    // ---- zones ----

    @Test
    fun anyTotalToggleSequenceLeavesAtMostOneTotal() = runTest {
        val keys = listOf("a", "b", "c", "d")
        val toggles = Arb.list(arbitrary { rs -> Arb.element(keys).sample(rs).value to Arb.of(true, false).sample(rs).value }, 0..30)
        checkAll(toggles) { ops ->
            var doc = SketchDocument(zones = ZoneSet(keys.map { Zone(it) }))
            for ((key, on) in ops) doc = SketchCommand.SetZoneTotal(key, on).apply(doc).ok()
            assertTrue(doc.zones.zones.count { it.isTotal } <= 1)
            assertEquals(keys, doc.zones.zones.map { it.key })
        }
    }

    @Test
    fun sumToTotalFlagsAViolationAndDerivesTheMissingPart() {
        var doc = SketchDocument()
        for (k in listOf("total", "left", "right")) doc = SketchCommand.AddZone(Zone(k)).apply(doc).ok()
        doc = SketchCommand.SetZoneTotal("total", true).apply(doc).ok()
        doc = SketchCommand.SetZoneText("total", "40").apply(doc).ok()
        doc = SketchCommand.SetZoneText("left", "12 3/16").apply(doc).ok()
        val derived = ZoneConstraints.resolve(doc.zones).ok()
        assertFalse(derived.sumViolated)
        assertNotNull(derived.dimensions["right"])
        doc = SketchCommand.SetZoneText("right", "20").apply(doc).ok()
        assertTrue(ZoneConstraints.resolve(doc.zones).ok().sumViolated)
        doc = SketchCommand.SetZoneTotal("total", false).apply(doc).ok()
        assertFalse(ZoneConstraints.resolve(doc.zones).ok().sumViolated)
        assertEquals(setOf("left", "right", "total"), doc.zones.zones.map { it.key }.toSet())
    }

    @Test
    fun removingAZoneAndMirroredDisplayOrder() {
        var doc = SketchDocument(zones = ZoneSet(listOf(Zone("t", isTotal = true), Zone("a"), Zone("b"), Zone("c"))))
        assertEquals(listOf("a", "b", "c"), doc.zones.displayKeys(ViewDirection.FROM_FRONT))
        assertEquals(listOf("c", "b", "a"), doc.zones.displayKeys(ViewDirection.FROM_BACK))
        doc = SketchCommand.RemoveZone("b").apply(doc).ok()
        assertEquals(listOf("t", "a", "c"), doc.zones.zones.map { it.key })
        assertIs<CommandError.UnknownZone>(SketchCommand.RemoveZone("b").apply(doc).err())
        assertIs<CommandError.UnknownZone>(SketchCommand.SetZoneTotal("zz", true).apply(doc).err())
    }
}
