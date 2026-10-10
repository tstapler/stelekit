// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.PageFileResolver
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/** How long a manifest (and so undo) stays available. */
const val MERGE_UNDO_WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

enum class MergeStatus { InProgress, Complete }

@Serializable
data class CreatedFile(val path: String, val hash: String)

/** What one page's write added: files created (+hash, for safe undo) and block uuids inserted. */
@Serializable
data class ManifestPageEntry(
    val pageName: String,
    val createdFiles: List<CreatedFile> = emptyList(),
    val addedBlockUuids: List<String> = emptyList(),
    /** [BlockContentHash] per added uuid at copy time; undo leaves blocks whose hash changed. */
    val addedBlockHashes: Map<String, String> = emptyMap(),
    val isJournal: Boolean = false,
)

/** Per-run record that drives undo (ADR-003) and "interrupted" detection. */
data class MergeManifest(
    val mergeId: String,
    val sourceGraphId: String,
    val targetGraphId: String,
    val startedAtEpochMs: Long,
    val status: MergeStatus,
    val pages: List<ManifestPageEntry>,
)

@Serializable
private sealed interface ManifestRecord {
    @Serializable @SerialName("header")
    data class Header(
        val mergeId: String,
        val sourceGraphId: String,
        val targetGraphId: String,
        val startedAtEpochMs: Long,
    ) : ManifestRecord

    @Serializable @SerialName("page")
    data class Page(val entry: ManifestPageEntry) : ManifestRecord

    @Serializable @SerialName("complete")
    data object Complete : ManifestRecord
}

private val manifestJson = Json { ignoreUnknownKeys = true }

private fun ManifestRecord.toLine(): String =
    manifestJson.encodeToString(ManifestRecord.serializer(), this) + "\n"

/** Append-only writer: each record is one JSON line, flushed before the call returns. */
class MergeManifestWriter internal constructor(
    private val fileSystem: FileSystem,
    private val path: Path,
) {
    fun appendPage(entry: ManifestPageEntry): Either<MergeStorageError, Unit> = append(ManifestRecord.Page(entry))

    fun complete(): Either<MergeStorageError, Unit> = append(ManifestRecord.Complete)

    private fun append(record: ManifestRecord): Either<MergeStorageError, Unit> = io {
        val sink = fileSystem.appendingSink(path).buffer()
        try {
            sink.writeUtf8(record.toLine())
        } finally {
            sink.close()
        }
    }
}

/** `<appDataDir>/.stele-merge-manifests/<MergeId>.jsonl`; names come from MergeId only. */
class MergeManifestStore(private val fileSystem: FileSystem, appDataDir: String) {
    private val root: Path = appDataDir.toPath() / DIR_NAME

    fun begin(
        mergeId: MergeId,
        sourceGraphId: String,
        targetGraphId: String,
        startedAtEpochMs: Long,
    ): Either<MergeStorageError, MergeManifestWriter> {
        val path = manifestPath(mergeId).getOrNull() ?: return MergeStorageError("invalid merge id").left()
        val header = ManifestRecord.Header(mergeId.value, sourceGraphId, targetGraphId, startedAtEpochMs)
        return io {
            fileSystem.createDirectories(root)
            fileSystem.write(path) { writeUtf8(header.toLine()) }
            MergeManifestWriter(fileSystem, path)
        }
    }

    /** Re-attach to an existing manifest to keep appending (resume). */
    fun writerFor(mergeId: MergeId): MergeManifestWriter? =
        manifestPath(mergeId).getOrNull()?.takeIf { fileSystem.exists(it) }?.let { MergeManifestWriter(fileSystem, it) }

    fun load(mergeId: MergeId): MergeManifest? = manifestPath(mergeId).getOrNull()?.let(::parse)

    fun list(): List<MergeManifest> {
        val files = try {
            fileSystem.list(root)
        } catch (e: IOException) {
            return emptyList()
        }
        return files.filter { it.name.endsWith(EXT) }.mapNotNull(::parse).sortedBy { it.startedAtEpochMs }
    }

    fun delete(mergeId: MergeId): Either<MergeStorageError, Unit> {
        val path = manifestPath(mergeId).getOrNull() ?: return MergeStorageError("invalid merge id").left()
        return io { fileSystem.delete(path, mustExist = false) }
    }

    /** Runs whose manifest never reached Complete (process death mid-copy). */
    fun findInterrupted(): List<MergeManifest> = list().filter { it.status == MergeStatus.InProgress }

    /** Deletes manifests started more than [maxAgeMillis] ago; unreadable files are kept. */
    fun expire(nowEpochMs: Long, maxAgeMillis: Long = MERGE_UNDO_WINDOW_MILLIS) {
        for (m in list()) {
            if (nowEpochMs - m.startedAtEpochMs > maxAgeMillis) delete(MergeId(m.mergeId))
        }
    }

    private fun manifestPath(mergeId: MergeId): Either<MergeStorageError, Path> {
        if (!isSafeMergeIdToken(mergeId.value)) return MergeStorageError("invalid merge id").left()
        val path = root / "${mergeId.value}$EXT"
        return if (PageFileResolver.isWithin(root.toString(), path.toString())) path.right()
        else MergeStorageError("path outside manifest dir").left()
    }

    /** Tolerates a torn final line (crash mid-append); a missing/invalid header means unreadable. */
    private fun parse(path: Path): MergeManifest? {
        val text = try {
            fileSystem.read(path) { readUtf8() }
        } catch (e: IOException) {
            return null
        }
        val records = ArrayList<ManifestRecord>()
        for (line in text.lineSequence().filter { it.isNotBlank() }) {
            try {
                records.add(manifestJson.decodeFromString(ManifestRecord.serializer(), line))
            } catch (e: IllegalArgumentException) {
                break
            }
        }
        val header = records.firstOrNull() as? ManifestRecord.Header ?: return null
        return MergeManifest(
            mergeId = header.mergeId,
            sourceGraphId = header.sourceGraphId,
            targetGraphId = header.targetGraphId,
            startedAtEpochMs = header.startedAtEpochMs,
            status = if (records.any { it is ManifestRecord.Complete }) MergeStatus.Complete else MergeStatus.InProgress,
            pages = records.filterIsInstance<ManifestRecord.Page>().map { it.entry },
        )
    }

    private companion object {
        const val DIR_NAME = ".stele-merge-manifests"
        const val EXT = ".jsonl"
    }
}
