// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Fixtures for Story 0.1.1 (`resources/sketch/old-reader/`). */
internal object OldReaderFixtures {
    fun bytes(name: String): ByteArray = checkNotNull(OldReaderFixtures::class.java.classLoader.getResourceAsStream("sketch/old-reader/$name")) {
        "sketch/old-reader/$name not on the test classpath"
    }.use { it.readBytes() }

    val s1 get() = bytes("sheet-s1.v1.json")
    val s2 get() = bytes("sheet-s2.v2.json")
    val s3 get() = bytes("sheet-s3.json")

    /** A model that references each `(uuid, file)` with the sha of [files]'s bytes at that path. */
    fun model(refs: List<Pair<String, String?>>, files: Map<String, ByteArray>): ByteArray {
        val sheets = refs.joinToString(",") { (uuid, file) ->
            val path = file ?: "sheets/$uuid.json"
            val fileField = if (file == null) "" else ",\"file\":\"$file\""
            "{\"uuid\":\"$uuid\"$fileField,\"fileSha256\":\"${ContentHasher.sha256(files[path] ?: ByteArray(0))}\"}"
        }
        return "{\"schema\":1,\"slug\":\"kitchen\",\"sheets\":[$sheets]}\n".encodeToByteArray()
    }
}

/** Story 0.1.1: today's reader fails closed on a newer schema, and ignores `backup/`. */
class SidecarOldReaderSchemaGateTest {
    private val slug = "kitchen"

    private fun seed(fs: SidecarFs, files: Map<String, ByteArray>) = files.forEach { (rel, bytes) -> fs.seed(sidecarPath(slug, rel), bytes) }

    @Test
    fun read_should_BeCorruptNewerSchema_WhenModelReferencesSchema3Sheet_AndWriteNothing() = runBlocking<Unit> {
        val fs = SidecarFs()
        val sheets = mapOf("sheets/s1.json" to OldReaderFixtures.s1, "sheets/s3.json" to OldReaderFixtures.s3)
        val files = sheets + (SidecarCodec.MODEL_PATH to OldReaderFixtures.model(listOf("s1" to null, "s3" to null), sheets))
        seed(fs, files)
        val before = fs.allFilePaths().sorted()

        val result = assertIs<SidecarRead.Corrupt>(newStore(fs).read(slug))

        assertEquals(CorruptReason.NewerSchema, result.reason)
        assertEquals(sidecarPath(slug, "sheets/s3.json"), result.path)
        assertEquals(before, fs.allFilePaths().sorted())
        files.forEach { (rel, bytes) -> assertContentEquals(bytes, fs.readFileBytes(sidecarPath(slug, rel)), rel) }
    }

    @Test
    fun read_should_BePresentWithExactlyModelAndSheets_WhenBackupExistsBesideTheBundle() = runBlocking<Unit> {
        val fs = SidecarFs()
        val sheets = mapOf("sheets/s1.json" to OldReaderFixtures.s1)
        val files = sheets + (SidecarCodec.MODEL_PATH to OldReaderFixtures.model(listOf("s1" to null), sheets))
        seed(fs, files)
        fs.seed(sidecarPath(slug, "backup/s1.v1.json"), OldReaderFixtures.s3)

        val present = assertIs<SidecarRead.Present>(newStore(fs).read(slug))

        assertEquals(setOf("project-model.json", "sheets/s1.json"), present.files.keys)
        assertNotNull(fs.readFileBytes(sidecarPath(slug, "backup/s1.v1.json")))
    }

    @Test
    fun read_should_BeCorrupt_WhenModelNamesAFileOutsideTheSheetPathRule() = runBlocking<Unit> {
        val fs = SidecarFs()
        val model = "{\"schema\":1,\"sheets\":[{\"uuid\":\"s1\",\"file\":\"../../secret.json\"}]}".encodeToByteArray()
        seed(fs, mapOf(SidecarCodec.MODEL_PATH to model))

        assertEquals(CorruptReason.InvalidJson, assertIs<SidecarRead.Corrupt>(newStore(fs).read(slug)).reason)
    }
}
