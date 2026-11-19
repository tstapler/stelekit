// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.util.ContentHasher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 1.1.3a: `ProjectBundle` and `SidecarCodec` carry a v2 sheet's bytes untouched; v1 output is unchanged. */
class BundleV2PassthroughTest {
    private val v2 = SheetUuid(V2Fixtures.V2_SHEET)

    @Test
    fun encode_should_WriteTheV2BytesVerbatim_AtTheRulePath_AndNameThemInTheModel() {
        val files = SidecarCodec.encode(V2Fixtures.mixedBundle())

        assertContentEquals(V2Fixtures.bytes, files.getValue("sheets/${V2Fixtures.V2_SHEET}.v2.json"))
        assertEquals(setOf("project-model.json", "sheets/p1-s0.json", "sheets/p1-v2.v2.json"), files.keys)
        assertEquals(mapOf("sheets/p1-v2.v2.json" to ContentHasher.sha256(V2Fixtures.bytes)), SidecarCodec.v2SheetShas(files.getValue("project-model.json")))
    }

    @Test
    fun decode_should_ReturnV2OpaqueWithoutInterpretingIt_AndEncodeShouldRoundTripByteForByte() {
        val files = SidecarCodec.encode(V2Fixtures.mixedBundle())

        val decoded = SidecarCodec.decode(files).getOrNull()!!

        val payload = assertIs<SheetPayload.V2Opaque>(decoded.sheetPayloads.getValue(v2))
        assertContentEquals(V2Fixtures.bytes, payload.bytes)
        assertEquals(ContentHasher.sha256(V2Fixtures.bytes), payload.sha256)
        assertEquals(2L, decoded.sheets.single { it.uuid == v2 }.schemaVersion)
        assertEquals(1L, decoded.sheets.single { it.uuid != v2 }.schemaVersion)
        assertTrue(decoded.primitives.none { it.sheetUuid == v2 }, "a v2 sheet has no primitives")
        assertEquals(2, decoded.primitives.size)
        val again = SidecarCodec.encode(decoded)
        assertEquals(files.keys, again.keys)
        files.forEach { (path, bytes) -> assertContentEquals(bytes, again.getValue(path), path) }
    }

    @Test
    fun encode_should_LeaveV1BundlesByteIdentical_WithNoSchemaOrFileKeyInTheModel() {
        val files = SidecarCodec.encode(SketchTestSupport.bundle(sheets = 2, primitivesPerSheet = 3))
        val model = files.getValue("project-model.json").decodeToString()

        assertFalse("\"schema\": 2" in model)
        assertFalse("\"file\"" in model)
        assertNull(SidecarCodec.v2SheetShas(files.getValue("project-model.json")).orEmpty().entries.firstOrNull())
        assertEquals(files.keys, SidecarCodec.encode(SidecarCodec.decode(files).getOrNull()!!).keys)
    }

    @Test
    fun encode_should_Refuse_AV2SheetWithNoOpaquePayload() {
        val bundle = V2Fixtures.mixedBundle().copy(sheetPayloads = emptyMap())
        assertFailsWith<IllegalArgumentException> { SidecarCodec.encode(bundle) }
    }

    private fun mutatedModel(files: Map<String, ByteArray>, from: String, to: String): Map<String, ByteArray> {
        val model = files.getValue("project-model.json").decodeToString()
        assertTrue(from in model, "fixture has $from")
        return files + ("project-model.json" to model.replace(from, to).encodeToByteArray())
    }

    @Test
    fun decode_should_RejectASheetRefFileThatIsNotTheRulePath() {
        val files = SidecarCodec.encode(V2Fixtures.mixedBundle())

        val elsewhere = mutatedModel(files, "\"file\": \"sheets/p1-v2.v2.json\"", "\"file\": \"sheets/../../evil.json\"")
        assertEquals(CorruptReason.InvalidJson, SidecarCodec.decode(elsewhere).leftOrNull())

        val v1Path = mutatedModel(files, "\"file\": \"sheets/p1-v2.v2.json\"", "\"file\": \"sheets/p1-v2.json\"")
        assertEquals(CorruptReason.InvalidJson, SidecarCodec.decode(v1Path).leftOrNull())
    }

    @Test
    fun decode_should_ClassifyTheSchemaOfTheRefAndOfTheFile() {
        val files = SidecarCodec.encode(V2Fixtures.mixedBundle())

        assertEquals(CorruptReason.NewerSchema, SidecarCodec.decode(mutatedModel(files, "\"schema\": 2", "\"schema\": 3")).leftOrNull())

        val v1Bytes = "{\"schema\":1,\"sheetUuid\":\"p1-v2\",\"primitives\":[]}\n".encodeToByteArray()
        val lying = mutatedModel(files, ContentHasher.sha256(V2Fixtures.bytes), ContentHasher.sha256(v1Bytes)) + (V2Fixtures.v2Path to v1Bytes)
        assertEquals(CorruptReason.InvalidJson, SidecarCodec.decode(lying).leftOrNull(), "a v2 ref must hold schema-2 bytes")
    }

    @Test
    fun decode_should_TreatAHashMismatchOfAV2FileAsTruncated() {
        val files = SidecarCodec.encode(V2Fixtures.mixedBundle())
        val tampered = files + (V2Fixtures.v2Path to (V2Fixtures.bytes + " ".encodeToByteArray()))
        assertEquals(CorruptReason.Truncated, SidecarCodec.decode(tampered).leftOrNull())
    }

    @Test
    fun sheetPaths_should_BeTheOneRule() {
        assertEquals("sheets/a.json", SheetPaths.pathFor(SheetUuid("a"), 1))
        assertEquals("sheets/a.v2.json", SheetPaths.pathFor(SheetUuid("a"), 2))
        assertFailsWith<IllegalArgumentException> { SheetPaths.pathFor(SheetUuid("a"), 3) }
        assertEquals("sheets/a.json", SidecarCodec.sheetPath(SheetUuid("a")))
    }
}
