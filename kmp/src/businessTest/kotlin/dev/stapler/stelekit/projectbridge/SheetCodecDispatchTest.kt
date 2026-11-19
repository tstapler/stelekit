// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.repository.SketchTestSupport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Story 1.1.1: per-kind schema limits and the per-schema sheet codec. */
class SheetCodecDispatchTest {
    private fun json(schema: Int) = "{\"schema\":$schema,\"sheetUuid\":\"s\"}".encodeToByteArray()

    @Test
    fun classify_should_UseTheLimitOfTheFileKind() {
        assertIs<SidecarFileState.Valid>(SidecarClassifier.classifyJson(json(2), SidecarFileKind.Sheet))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.NewerSchema), SidecarClassifier.classifyJson(json(3), SidecarFileKind.Sheet))
        assertEquals(SidecarFileState.Corrupt(CorruptReason.NewerSchema), SidecarClassifier.classifyJson(json(2), SidecarFileKind.Model))
        assertIs<SidecarFileState.Valid>(SidecarClassifier.classifyJson(json(1), SidecarFileKind.Model))
    }

    @Test
    fun classify_should_RefuseASchema2Sheet_WhenReadWithThePreviousMax() {
        val old = SidecarClassifier.classifyJson(OldReaderFixtures.s2, SidecarFileKind.Sheet, maxSchema = 1L)
        assertEquals(SidecarFileState.Corrupt(CorruptReason.NewerSchema), old)
        assertIs<SidecarFileState.Valid>(SidecarClassifier.classifyJson(OldReaderFixtures.s2, SidecarFileKind.Sheet))
    }

    @Test
    fun store_should_ReadAReferencedSchema2Sheet_AndRefuseSchema3() = runBlocking<Unit> {
        val slug = "kitchen"
        val v2 = mapOf("sheets/s2.v2.json" to OldReaderFixtures.s2)
        val model = OldReaderFixtures.model(listOf("s2" to "sheets/s2.v2.json"), v2)
        val fs = SidecarFs()
        (v2 + (SidecarCodec.MODEL_PATH to model)).forEach { (rel, bytes) -> fs.seed(sidecarPath(slug, rel), bytes) }
        val present = assertIs<SidecarRead.Present>(newStore(fs).read(slug))
        assertEquals(setOf("project-model.json", "sheets/s2.v2.json"), present.files.keys)

        val s3 = mapOf("sheets/s3.v2.json" to OldReaderFixtures.s3)
        fs.seed(sidecarPath(slug, "sheets/s3.v2.json"), OldReaderFixtures.s3)
        fs.seed(sidecarPath(slug, SidecarCodec.MODEL_PATH), OldReaderFixtures.model(listOf("s3" to "sheets/s3.v2.json"), s3))
        val corrupt = assertIs<SidecarRead.Corrupt>(newStore(fs).read(slug))
        assertEquals(CorruptReason.NewerSchema, corrupt.reason)
    }

    @Test
    fun v1Codec_should_RoundTripEveryGoldenBundleByteForByte() {
        val bundle = SketchTestSupport.bundle(sheets = 2, primitivesPerSheet = 3)
        val files = SidecarCodec.encode(bundle)
        for (sheet in bundle.sheets) {
            val bytes = files.getValue(SidecarCodec.sheetPath(sheet.uuid))
            val prims = SheetCodecV1.decode(sheet.uuid, bytes)
            assertContentEquals(bytes, SheetCodecV1.encode(sheet.uuid, prims), sheet.uuid.value)
        }
        assertEquals(files.keys, SidecarCodec.encode(SidecarCodec.decode(files).getOrNull()!!).keys)
    }

    @Test
    fun dispatch_should_PickTheCodecByTheSchemaInTheBytes() {
        assertSame(SheetCodecV1, SheetCodecs.forBytes(OldReaderFixtures.s1))
        assertNull(SheetCodecs.forBytes(OldReaderFixtures.s2))
        assertNull(SheetCodecs.forBytes("not json".encodeToByteArray()))
    }

    @Test
    fun decode_should_NotReadASchema2SheetAsAnEmptyV1Sheet() {
        val bundle = SketchTestSupport.bundle(sheets = 1, primitivesPerSheet = 1)
        val files = SidecarCodec.encode(bundle)
        val path = SidecarCodec.sheetPath(bundle.sheets.single().uuid)
        val v2 = "{\"schema\":2,\"sheetUuid\":\"${bundle.sheets.single().uuid.value}\",\"scene\":{}}".encodeToByteArray()
        val model = files.getValue(SidecarCodec.MODEL_PATH).decodeToString()
            .replace(dev.stapler.stelekit.util.ContentHasher.sha256(files.getValue(path)), dev.stapler.stelekit.util.ContentHasher.sha256(v2))
        val swapped = files + (path to v2) + (SidecarCodec.MODEL_PATH to model.encodeToByteArray())
        assertEquals(CorruptReason.NewerSchema, SidecarCodec.decode(swapped).leftOrNull())
    }
}
