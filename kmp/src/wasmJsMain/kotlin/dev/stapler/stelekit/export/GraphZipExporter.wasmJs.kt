// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.export

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.WasmJsShareProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Hand-rolled stored-only (uncompressed, method `0`) ZIP writer — no `java.util.zip` (JVM-only,
 * unavailable on Kotlin/Wasm) and no third-party compression dependency (ADR-003 Amendment,
 * `research/build-vs-buy.md`'s "no new dependency justified" finding). `STORED` entries are copied
 * verbatim, so only a CRC-32 ([Crc32]) is needed per entry, not a DEFLATE implementation. The
 * binary layout follows the PKZIP APPNOTE format: local file header + raw data per entry, followed
 * by one central-directory record per entry, followed by a single end-of-central-directory record.
 */
internal object StoredZipWriter {
    data class Entry(val name: String, val data: ByteArray)

    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
    private const val VERSION = 20 // 2.0 — minimum needed for STORED entries + long file names
    private const val METHOD_STORED = 0
    // A fixed DOS date/time (1980-01-01, midnight) rather than deriving one per file — this
    // export is a one-shot escape hatch, not a mtime-preserving backup, and a fixed timestamp
    // keeps the output byte-for-byte reproducible for the same input (useful for the round-trip
    // test's byte-identical assertion).
    private const val DOS_TIME = 0
    private const val DOS_DATE = 0x21

    private data class CentralRecord(val nameBytes: ByteArray, val crc: Long, val size: Long, val offset: Int)

    fun build(entries: List<Entry>): ByteArray {
        val out = ArrayList<Byte>()
        val central = entries.map { entry -> out.writeLocalEntry(entry) }

        val centralDirectoryStart = out.size
        for (record in central) out.writeCentralRecord(record)
        val centralDirectorySize = out.size - centralDirectoryStart

        out.writeEndOfCentralDirectory(central.size, centralDirectorySize, centralDirectoryStart)
        return out.toByteArray()
    }

    private fun ArrayList<Byte>.writeLocalEntry(entry: Entry): CentralRecord {
        val offset = size // this entry's local file header starts here
        val nameBytes = entry.name.encodeToByteArray()
        val crc = Crc32.compute(entry.data)
        val entrySize = entry.data.size.toLong()

        putU32(LOCAL_FILE_HEADER_SIGNATURE)
        putU16(VERSION)
        putU16(0) // general purpose bit flag
        putU16(METHOD_STORED)
        putU16(DOS_TIME)
        putU16(DOS_DATE)
        putU32(crc)
        putU32(entrySize) // compressed size == uncompressed size for STORED
        putU32(entrySize)
        putU16(nameBytes.size)
        putU16(0) // extra field length
        putBytes(nameBytes)
        putBytes(entry.data)

        return CentralRecord(nameBytes, crc, entrySize, offset)
    }

    private fun ArrayList<Byte>.writeCentralRecord(record: CentralRecord) {
        putU32(CENTRAL_DIRECTORY_SIGNATURE)
        putU16(VERSION) // version made by
        putU16(VERSION) // version needed to extract
        putU16(0) // general purpose bit flag
        putU16(METHOD_STORED)
        putU16(DOS_TIME)
        putU16(DOS_DATE)
        putU32(record.crc)
        putU32(record.size)
        putU32(record.size)
        putU16(record.nameBytes.size)
        putU16(0) // extra field length
        putU16(0) // file comment length
        putU16(0) // disk number start
        putU16(0) // internal file attributes
        putU32(0) // external file attributes
        putU32(record.offset.toLong())
        putBytes(record.nameBytes)
    }

    private fun ArrayList<Byte>.writeEndOfCentralDirectory(recordCount: Int, directorySize: Int, directoryStart: Int) {
        putU32(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        putU16(0) // number of this disk
        putU16(0) // disk where central directory starts
        putU16(recordCount) // central directory records on this disk
        putU16(recordCount) // total central directory records
        putU32(directorySize.toLong())
        putU32(directoryStart.toLong())
        putU16(0) // comment length
    }

    private fun ArrayList<Byte>.putU16(value: Int) {
        add((value and 0xFF).toByte())
        add(((value ushr 8) and 0xFF).toByte())
    }

    private fun ArrayList<Byte>.putU32(value: Long) {
        add((value and 0xFF).toByte())
        add(((value ushr 8) and 0xFF).toByte())
        add(((value ushr 16) and 0xFF).toByte())
        add(((value ushr 24) and 0xFF).toByte())
    }

    private fun ArrayList<Byte>.putBytes(bytes: ByteArray) {
        ensureCapacity(size + bytes.size)
        for (b in bytes) add(b)
    }
}

/**
 * Web [GraphZipExporter] (Story 2.3.3, Task 2.3.3c / ADR-003's Amendment): recursively reads a
 * graph's files via [FileSystem.listFilesRecursiveWithModTimes], zips them with [StoredZipWriter],
 * and triggers a browser download via [WasmJsShareProvider.saveBytesToFile] (no SAF/File-System-Access
 * prompt involved, per this story's acceptance criteria).
 */
class WasmJsGraphZipExporter : GraphZipExporter {
    override suspend fun export(
        fileSystem: FileSystem,
        graphPath: String,
        graphName: String,
    ): Either<DomainError, Unit> = withContext(PlatformDispatcher.Default) {
        try {
            val entries = fileSystem.listFilesRecursiveWithModTimes(graphPath).mapNotNull { (relativePath, _) ->
                val path = "$graphPath/$relativePath"
                // Read raw bytes, not through the String API: a round-trip through String mangles
                // non-UTF-8 byte sequences (images/attachments, paranoid-mode STEK-encrypted
                // files). FileSystem.readFileBytes() has no wasmJs override (throws), so go through
                // the wasmJs actual's own byte-level accessor directly — it already prefers
                // bytesCache (the authoritative store for content written via writeFileBytes) and
                // falls back to the plain-text cache, encoded, for everything else.
                val content = (fileSystem as? PlatformFileSystem)?.getContentBytes(path)
                    ?: fileSystem.readFile(path)?.encodeToByteArray()
                    ?: return@mapNotNull null
                StoredZipWriter.Entry(relativePath, content)
            }
            val zipBytes = StoredZipWriter.build(entries)
            val safeName = graphName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "graph" }
            WasmJsShareProvider().saveBytesToFile(zipBytes, "$safeName.zip")
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DomainError.ExportError.ShareFailed(e.message ?: "Failed to export zip").left()
        }
    }
}
