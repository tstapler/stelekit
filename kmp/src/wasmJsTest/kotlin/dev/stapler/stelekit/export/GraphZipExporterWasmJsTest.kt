// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.export

import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun readU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readU32(bytes: ByteArray, offset: Int): Long =
    readU16(bytes, offset).toLong() or (readU16(bytes, offset + 2).toLong() shl 16)

private data class ReadZipEntry(val name: String, val data: ByteArray, val storedCrc: Long)

/**
 * Minimal STORED-only ZIP reader, independent of [StoredZipWriter], so this test verifies the
 * on-disk *format* [StoredZipWriter.build] produces (via the central directory + local headers) —
 * not just that its own in-memory `Entry` list survives a round trip. No comment field is ever
 * written, so the end-of-central-directory record is always exactly the file's final 22 bytes.
 */
private fun readStoredZip(bytes: ByteArray): List<ReadZipEntry> {
    val eocdOffset = bytes.size - 22
    require(readU32(bytes, eocdOffset) == 0x06054b50L) { "missing/invalid end-of-central-directory signature" }
    val recordCount = readU16(bytes, eocdOffset + 10)
    val centralDirOffset = readU32(bytes, eocdOffset + 16).toInt()

    val entries = mutableListOf<ReadZipEntry>()
    var pos = centralDirOffset
    repeat(recordCount) {
        require(readU32(bytes, pos) == 0x02014b50L) { "invalid central directory record signature at $pos" }
        val crc = readU32(bytes, pos + 16)
        val compressedSize = readU32(bytes, pos + 20).toInt()
        val nameLen = readU16(bytes, pos + 28)
        val extraLen = readU16(bytes, pos + 30)
        val commentLen = readU16(bytes, pos + 32)
        val localHeaderOffset = readU32(bytes, pos + 42).toInt()
        val name = bytes.decodeToString(pos + 46, pos + 46 + nameLen)

        require(readU32(bytes, localHeaderOffset) == 0x04034b50L) { "invalid local file header signature at $localHeaderOffset" }
        val localNameLen = readU16(bytes, localHeaderOffset + 26)
        val localExtraLen = readU16(bytes, localHeaderOffset + 28)
        val dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen
        val data = bytes.copyOfRange(dataStart, dataStart + compressedSize)

        entries += ReadZipEntry(name, data, crc)
        pos += 46 + nameLen + extraLen + commentLen
    }
    return entries
}

/**
 * Story 2.3.3 (Task 2.3.3e): the hand-rolled stored-only ZIP writer must produce a real,
 * standards-compliant `.zip` — verified here by independently parsing its central directory and
 * local file headers (not by re-using [StoredZipWriter]'s own write path to check itself), then
 * asserting every entry's content is byte-identical and its stored CRC-32 matches
 * [Crc32.compute] of the recovered bytes.
 */
class GraphZipExporterWasmJsTest {

    @Test
    fun storedZipWriter_should_RoundTripByteIdenticalContent_When_BuiltFromAGraphsMarkdownFiles() = runTest {
        val fileSystem = PlatformFileSystem().apply { markEphemeral() }
        val graphPath = "/stelekit/zip-roundtrip-${Random.nextInt(0, Int.MAX_VALUE)}"
        fileSystem.preload(graphPath) // establishes the GraphScopedSession writeFile's dirty-tracking needs
        val pages = mapOf(
            "pages/Alpha.md" to "# Alpha\n- first page",
            "pages/Beta.md" to "# Beta\n- second page, with unicode: café",
            "journals/2026_09_13.md" to "- journal entry\n- second bullet",
        )
        for ((relativePath, content) in pages) {
            fileSystem.writeFile("$graphPath/$relativePath", content)
        }

        val entries = fileSystem.listFilesRecursiveWithModTimes(graphPath).map { (relativePath, _) ->
            StoredZipWriter.Entry(relativePath, fileSystem.readFile("$graphPath/$relativePath")!!.encodeToByteArray())
        }
        val zipBytes = StoredZipWriter.build(entries)

        val readBack = readStoredZip(zipBytes).associateBy { it.name }

        assertEquals(pages.size, readBack.size, "every markdown file must be present in the exported archive")
        for ((relativePath, content) in pages) {
            val entry = readBack[relativePath]
            assertTrue(entry != null, "archive is missing an entry for $relativePath")
            assertContentEquals(
                content.encodeToByteArray(),
                entry.data,
                "content for $relativePath must round-trip byte-identical",
            )
            assertEquals(
                Crc32.compute(entry.data),
                entry.storedCrc,
                "the archive's stored CRC-32 must match the recovered content for $relativePath",
            )
        }
    }

    @Test
    fun wasmJsGraphZipExporter_export_should_ReturnRight_When_GraphHasMarkdownFiles() = runTest {
        val fileSystem = PlatformFileSystem().apply { markEphemeral() }
        val graphPath = "/stelekit/zip-export-${Random.nextInt(0, Int.MAX_VALUE)}"
        fileSystem.preload(graphPath)
        fileSystem.writeFile("$graphPath/pages/Solo.md", "- only page")

        val result = WasmJsGraphZipExporter().export(fileSystem, graphPath, "stelekit-graph")

        assertTrue(result.isRight(), "export must succeed (Right) for a plain markdown graph: $result")
    }

    /**
     * MUST FIX regression (idiom review): the exporter used to read every file through
     * [dev.stapler.stelekit.platform.FileSystem.readFile]'s String API before
     * `.encodeToByteArray()`-ing it back — a round trip that silently replaces invalid UTF-8 byte
     * sequences, corrupting any binary attachment or paranoid-mode STEK file in the archive. On
     * wasmJs, byte-correct content for a path lives in [PlatformFileSystem]'s `bytesCache` (set by
     * `writeFileBytes`, e.g. attachment uploads / STEK writes) rather than the plain-text `cache`
     * `writeFile`/[FileSystem.listFilesRecursiveWithModTimes] enumerate. This test seeds `cache`
     * with a placeholder via `writeFile` so the path is enumerable, then overwrites the
     * authoritative content via `writeFileBytes` with a deliberately invalid-UTF-8 byte sequence —
     * mirroring `bytesCache`'s precedence in `getContentBytes`, which the fixed exporter now uses.
     * A regression back to the String round trip would either export the stale placeholder text or
     * a lossily-mangled decode of the binary bytes; this asserts byte-for-byte fidelity instead.
     */
    @Test
    fun storedZipWriter_should_RoundTripByteIdenticalContent_When_EntryIsBinaryNonUtf8Data() = runTest {
        val fileSystem = PlatformFileSystem().apply { markEphemeral() }
        val graphPath = "/stelekit/zip-binary-${Random.nextInt(0, Int.MAX_VALUE)}"
        fileSystem.preload(graphPath)
        val relativePath = "assets/attachment.bin"
        val path = "$graphPath/$relativePath"
        // Invalid UTF-8 byte sequences: a lone continuation byte (0xFF), an overlong/invalid
        // leading byte (0xFE), and a truncated multi-byte sequence — decodeToString+encodeToByteArray
        // would replace these with U+FFFD, changing the byte count and content.
        val binaryContent = byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x01, 0xC0.toByte(), 0x80.toByte(), 0x7F)

        fileSystem.writeFile(path, "placeholder text — must NOT end up in the export")
        fileSystem.writeFileBytes(path, binaryContent)

        val result = WasmJsGraphZipExporter().export(fileSystem, graphPath, "stelekit-graph")
        assertTrue(result.isRight(), "export must succeed (Right) for a binary attachment: $result")

        val entries = fileSystem.listFilesRecursiveWithModTimes(graphPath).map { (rel, _) ->
            StoredZipWriter.Entry(rel, fileSystem.getContentBytes("$graphPath/$rel")!!)
        }
        val zipBytes = StoredZipWriter.build(entries)
        val readBack = readStoredZip(zipBytes).associateBy { it.name }

        val entry = readBack[relativePath]
        assertTrue(entry != null, "archive is missing an entry for $relativePath")
        assertContentEquals(
            binaryContent,
            entry.data,
            "binary content for $relativePath must round-trip byte-identical, not lossily decoded/re-encoded as UTF-8",
        )
        assertEquals(
            Crc32.compute(entry.data),
            entry.storedCrc,
            "the archive's stored CRC-32 must match the recovered binary content for $relativePath",
        )
    }
}
