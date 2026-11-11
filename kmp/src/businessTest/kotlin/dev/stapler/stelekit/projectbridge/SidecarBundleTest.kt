// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 2.5: ProjectModelJson/SheetJson schema 1, canonical bytes, bundle hash, file classification. */
class SidecarBundleTest {
    private val bundle = SketchTestSupport.bundle(sheets = 2, primitivesPerSheet = 3)

    @Test
    fun encode_should_ProduceTheGoldenBytes_ForASmallProject() {
        val small = SketchTestSupport.bundle(sheets = 1, primitivesPerSheet = 1)
        val files = SidecarCodec.encode(small)
        assertEquals(setOf("project-model.json", "sheets/p1-s0.json"), files.keys)
        assertEquals(GOLDEN_SHEET, files.getValue("sheets/p1-s0.json").decodeToString())
        assertEquals(goldenModel(files), files.getValue("project-model.json").decodeToString())
    }

    private fun goldenModel(files: Map<String, ByteArray>): String {
        val sha = dev.stapler.stelekit.util.ContentHasher.sha256(files.getValue("sheets/p1-s0.json"))
        return GOLDEN_MODEL.replace("SHEET_SHA", sha)
    }

    @Test
    fun decode_should_RoundTripEveryField_Exactly() {
        val files = SidecarCodec.encode(bundle)
        val decoded = SidecarCodec.decode(files).getOrNull()!!
        assertEquals(bundle.project.copy(), decoded.project)
        assertEquals(bundle.sheets.sortedBy { it.uuid.value }, decoded.sheets.sortedBy { it.uuid.value })
        assertEquals(bundle.primitives.sortedBy { it.uuid }, decoded.primitives.sortedBy { it.uuid })
        assertEquals(Rational(379, 10), decoded.dimensions.single().value)
        assertEquals(bundle.dimensions, decoded.dimensions)
        assertContentEquals(files.getValue("project-model.json"), SidecarCodec.encode(decoded)["project-model.json"])
    }

    @Test
    fun sidecar_should_HoldNoPathUriOrFilename() {
        val text = SidecarCodec.encode(bundle).values.joinToString("") { it.decodeToString() }
        listOf("/home", "saf://", "content://", "file://", ".yaml", "C:\\").forEach { assertFalse(text.contains(it), it) }
    }

    @Test
    fun encode_should_StayUnder2MiB_ForATenThousandPrimitiveSheet() {
        val big = bundle.copy(
            primitives = (0 until 10_000).map { SketchPrimitive("pr-$it", SheetUuid("p1-s0"), "line", 1_000_000L + it, 2, 3_000_000L, 4, "n$it", "walls", it.toLong()) },
        )
        val sheetBytes = SidecarCodec.encode(big).getValue("sheets/p1-s0.json")
        assertTrue(sheetBytes.size < 2 * 1024 * 1024, "sheet file is ${sheetBytes.size} bytes")
    }

    @Test
    fun bundleHash_should_IgnoreModelRevision_And_MapOrder_ButNotContent() {
        val files = SidecarCodec.encode(bundle)
        val reordered = files.entries.reversed().associate { it.key to it.value }
        assertEquals(SidecarCodec.bundleHash(files), SidecarCodec.bundleHash(reordered))
        val bumped = SidecarCodec.encode(bundle.copy(project = bundle.project.copy(modelRevision = 99)))
        assertNotEquals(files.getValue("project-model.json").decodeToString(), bumped.getValue("project-model.json").decodeToString())
        assertEquals(SidecarCodec.bundleHash(files), SidecarCodec.bundleHash(bumped))
        val edited = SidecarCodec.encode(bundle.copy(primitives = bundle.primitives.dropLast(1)))
        assertNotEquals(SidecarCodec.bundleHash(files), SidecarCodec.bundleHash(edited))
    }

    @Test
    fun encode_should_BeIndependentOfInputOrder_Property() = runTest {
        checkAll(50, Arb.int(0..1000)) { seed ->
            val shuffled = bundle.copy(
                sheets = bundle.sheets.shuffledBy(seed), primitives = bundle.primitives.shuffledBy(seed),
                dimensions = bundle.dimensions.shuffledBy(seed),
            )
            assertEquals(SidecarCodec.bundleHash(SidecarCodec.encode(bundle)), SidecarCodec.bundleHash(SidecarCodec.encode(shuffled)))
        }
    }

    @Test
    fun decode_should_FlagAMissingOrTamperedSheetFile_AsTruncated() {
        val files = SidecarCodec.encode(bundle)
        assertEquals(CorruptReason.Truncated, SidecarCodec.decode(files - "sheets/p1-s1.json").leftOrNull())
        val tampered = files + ("sheets/p1-s1.json" to "{\"schema\":1,\"sheetUuid\":\"p1-s1\",\"primitives\":[]}".encodeToByteArray())
        assertEquals(CorruptReason.Truncated, SidecarCodec.decode(tampered).leftOrNull())
    }

    @Test
    fun bundle_should_RoundTripThroughASecondDatabase() = SketchTestSupport.backends().forEach { b ->
        runBlocking {
            b.repos.projects.replaceProjectContents(SketchTestSupport.P, bundle).getOrNull()
            val other = SketchTestSupport.backends().first { it.name == b.name }
            val decoded = SidecarCodec.decode(SidecarCodec.encode(bundle)).getOrNull()!!
            other.repos.projects.replaceProjectContents(SketchTestSupport.P, decoded).getOrNull()
            val rows = other.repos.sheets.getPrimitives(SheetUuid("p1-s1")).first().getOrNull()!!
            assertEquals(bundle.primitives.filter { it.sheetUuid.value == "p1-s1" }.sortedBy { it.z }, rows, b.name)
            assertEquals(bundle.dimensions, other.repos.dimensions.getDimensions(SketchTestSupport.P).first().getOrNull(), b.name)
        }
    }

    // ── classification ────────────────────────────────────────────────────────────────────────

    private fun classify(text: String) = SidecarClassifier.classifyJson(text.encodeToByteArray())

    @Test
    fun classify_should_OnlyCallAMissingFileAbsent() {
        assertEquals(SidecarFileState.Absent, SidecarClassifier.classifyJson(null))
        assertIs<SidecarFileState.Valid>(classify("{\"schema\":1}"))
    }

    @Test
    fun classify_should_FlagEveryUnreadableFileCorrupt() {
        assertEquals(SidecarFileState.Corrupt(CorruptReason.ConflictMarkers), classify("{\n<<<<<<< HEAD\n\"schema\":1\n=======\n\"schema\":1\n>>>>>>> origin\n}"))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.InvalidUtf8), SidecarClassifier.classifyJson(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d)))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.Truncated), classify(""))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.Truncated), classify("{\"schema\":1,\"uuid\":\"p"))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.InvalidJson), classify("{\"schema\":1,}"))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.InvalidJson), classify("[1]"))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.NewerSchema), classify("{\"schema\":2}"))
    }

    @Test
    fun classifyText_should_CheckUtf8AndMarkersOnly() {
        assertIs<SidecarFileState.Valid>(SidecarClassifier.classifyText("a: 1\n".encodeToByteArray()))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.ConflictMarkers), SidecarClassifier.classifyText("a: 1\n=======\n".encodeToByteArray()))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.InvalidUtf8), SidecarClassifier.classifyText(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun classify_should_NeverReadATruncatedPrefixAsValid_Property() = runTest {
        val full = SidecarCodec.encode(bundle).getValue("project-model.json")
        checkAll(60, Arb.int(1 until full.size - 2)) { cut ->
            assertTrue(SidecarClassifier.classifyJson(full.copyOf(cut)) is SidecarFileState.Corrupt, "cut at $cut read as valid")
        }
    }

    @Test
    fun classify_should_TreatEveryMarkerLine_Property() = runTest {
        checkAll(Arb.list(Arb.element("a", "<<<<<<< HEAD", "=======", ">>>>>>> x", "b"), 0..6)) { lines ->
            val expected = lines.any { it.startsWith("<<<") || it == "=======" || it.startsWith(">>>") }
            assertEquals(expected, SidecarClassifier.hasConflictMarkers(lines.joinToString("\n")))
        }
    }

    private fun <T> List<T>.shuffledBy(seed: Int): List<T> = shuffled(kotlin.random.Random(seed))

    private companion object {
        val GOLDEN_SHEET = """
            |{
            |  "schema": 1,
            |  "sheetUuid": "p1-s0",
            |  "primitives": [
            |    {
            |      "uuid": "p1-s0-pr0",
            |      "kind": "line",
            |      "x1": 0,
            |      "y1": 0,
            |      "x2": 608,
            |      "y2": 0,
            |      "name": "n",
            |      "layer": "walls",
            |      "z": 0
            |    }
            |  ]
            |}
            |""".trimMargin()

        val GOLDEN_MODEL = """
            |{
            |  "schema": 1,
            |  "uuid": "p1",
            |  "slug": "proj-0a1b2c",
            |  "name": "Kitchen",
            |  "createdAt": 1,
            |  "modelRevision": 0,
            |  "settings": {},
            |  "sources": [],
            |  "dimensions": [
            |    {
            |      "uuid": "p1-d1",
            |      "key": "width",
            |      "kind": "length",
            |      "display": "379/10",
            |      "num": 379,
            |      "den": 10,
            |      "precisionNum": 1,
            |      "precisionDen": 20,
            |      "toleranceNum": 0,
            |      "toleranceDen": 1,
            |      "sourceKind": "sheet",
            |      "sourceRef": "r1",
            |      "locked": false
            |    }
            |  ],
            |  "sheets": [
            |    {
            |      "uuid": "p1-s0",
            |      "kind": "plan",
            |      "viewDirection": "top",
            |      "unit": "in",
            |      "scaleSixteenthsPerUnit": 16,
            |      "layers": [
            |        "walls"
            |      ],
            |      "zones": [],
            |      "position": 0,
            |      "sourceAssetUuid": null,
            |      "fileSha256": "SHEET_SHA"
            |    }
            |  ]
            |}
            |""".trimMargin()
    }
}
