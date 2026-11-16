// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.AtomicFileWriter
import dev.stapler.stelekit.platform.AtomicWriteMode
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What reading one project's sidecar directory found. */
sealed interface SidecarRead {
    /** This platform cannot write the sidecar safely (wasmJs, iOS): the project stays device-local. */
    data object Unavailable : SidecarRead
    data object Absent : SidecarRead
    data class Corrupt(val reason: CorruptReason, val path: String) : SidecarRead
    data class Present(val files: Map<String, ByteArray>) : SidecarRead
}

sealed interface SidecarWriteOutcome {
    data object Written : SidecarWriteOutcome
    data object Unavailable : SidecarWriteOutcome

    /** A git sync or merge is in flight; the files are queued and flushed on the next trigger. */
    data object Deferred : SidecarWriteOutcome
    data class Failed(val error: DomainError) : SidecarWriteOutcome
}

/**
 * The in-graph sidecar bundle of each sketch project, under `assets/stelekit-sketch/<slug>/`. Sidecar files are
 * assets, not pages: they are written here, never through `GraphWriter`.
 */
interface ProjectSidecarStore {
    suspend fun read(slug: String): SidecarRead
    suspend fun write(slug: String, files: Map<String, ByteArray>): SidecarWriteOutcome

    /** Keeps [files] (the local state) beside a sidecar the reconcile would not overwrite; returns the path. */
    suspend fun writeConflict(slug: String, files: Map<String, ByteArray>): Either<DomainError, String>

    /** Stores a picked YAML source's content as `sources/yaml-<hash8>.yaml`; no path or filename is kept. */
    suspend fun writeSourceSnapshot(slug: String, hash8: String, bytes: ByteArray): SidecarWriteOutcome

    /** The stored YAML snapshot named by [hash8], or null when absent or unavailable on this platform. */
    suspend fun readSourceSnapshot(slug: String, hash8: String): ByteArray?

    /**
     * Keeps the pre-conversion sheet [bytes] as `<slug>/backup/<sheetUuid>.v1.json` (temp+rename, then a read-back
     * sha check). An existing backup is the original and is never overwritten. `backup/` is invisible to [read].
     */
    suspend fun writeBackup(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String>

    /**
     * Keeps a v2 sheet scene the saver could not write as `<slug>/conflicts/<sheetUuid>.v2.conflict-<hash8>.json`.
     * Unrelated to [writeConflict] (the reconciler's model copy at the slug root). Invisible to [read] and the hash.
     */
    suspend fun writeSheetConflictCopy(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String>

    /** Deletes `sheets/<sheetUuid>.v2.json`; the only way a schema-2 file is removed (converter cleanup, restore). */
    suspend fun deleteV2Sheet(slug: String, sheetUuid: String): Either<DomainError, Unit>

    /** Writes queued bundles once the git sync is idle; returns how many were written. */
    suspend fun flushPending(): Int

    /** Slugs that have a sidecar directory in the graph. */
    suspend fun slugsOnDisk(): List<String>
}

/** [ProjectSidecarStore] over [FileSystem] with temp-and-rename writes; its own mutex, not `GraphWriteLock` (JSON, not markdown). */
class FileProjectSidecarStore(
    private val fs: FileSystem,
    private val graphRoot: String,
    private val writer: AtomicFileWriter,
    private val isGitSyncBusy: () -> Boolean,
) : ProjectSidecarStore {
    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, Map<String, ByteArray>>()

    private fun root() = "${graphRoot.trimEnd('/')}/$ROOT_DIR"
    private fun dir(slug: String) = "${root()}/$slug"
    private fun unavailable() = writer.mode(root()) == AtomicWriteMode.UNSUPPORTED

    override suspend fun read(slug: String): SidecarRead = mutex.withLock {
        if (unavailable()) return SidecarRead.Unavailable
        readLocked(slug)
    }

    /**
     * Reads the model first and then ONLY the sheet files it references. A file under `sheets/` that no `sheetRef`
     * names (a staged `.v2.json`, a leftover) is never opened or classified, so it cannot make the project Corrupt
     * or enter `files` and `bundleHash`; `backup/` and `conflicts/` are never listed.
     */
    private fun readLocked(slug: String): SidecarRead {
        val d = dir(slug)
        val modelPath = "$d/${SidecarCodec.MODEL_PATH}"
        val model = fs.readFileBytes(modelPath)
        // A model deleted by a torn replace (crash between delete and rename) is Absent: the database restores it.
        if (model == null) return SidecarRead.Absent
        val files = linkedMapOf<String, ByteArray>()
        when (val state = SidecarClassifier.classifyJson(model)) {
            SidecarFileState.Absent -> return SidecarRead.Corrupt(CorruptReason.Truncated, modelPath)
            is SidecarFileState.Corrupt -> return SidecarRead.Corrupt(state.reason, modelPath)
            is SidecarFileState.Valid -> files[SidecarCodec.MODEL_PATH] = state.bytes
        }
        val sheetPaths = referencedSheetPaths(model) ?: return SidecarRead.Corrupt(CorruptReason.InvalidJson, modelPath)
        for (rel in sheetPaths) {
            val abs = "$d/$rel"
            when (val state = SidecarClassifier.classifyJson(fs.readFileBytes(abs))) {
                SidecarFileState.Absent -> return SidecarRead.Corrupt(CorruptReason.Truncated, abs)
                is SidecarFileState.Corrupt -> return SidecarRead.Corrupt(state.reason, abs)
                is SidecarFileState.Valid -> files[rel] = state.bytes
            }
        }
        return SidecarRead.Present(files)
    }

    /**
     * Sorted relative paths of the sheet files [modelBytes] references. A `file` that is not the rule path of its
     * uuid (`sheets/<uuid>.json` or `sheets/<uuid>.v2.json`), or a uuid that is not one path segment, yields null:
     * no arbitrary path is read from git-synced input.
     */
    private fun referencedSheetPaths(modelBytes: ByteArray): List<String>? = try {
        val refs = Json.parseToJsonElement(modelBytes.decodeToString()).jsonObject["sheets"]?.jsonArray.orEmpty()
        refs.map { ref ->
            val o = ref.jsonObject
            val uuid = o.getValue("uuid").jsonPrimitive.content
            if (!SAFE_SEGMENT.matches(uuid)) return null
            val default = "${SidecarCodec.SHEETS_DIR}/$uuid.json"
            val file = o["file"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: default
            if (file != default && file != "${SidecarCodec.SHEETS_DIR}/$uuid.v2.json") return null
            file
        }.distinct().sorted()
    } catch (_: Exception) {
        null
    }

    override suspend fun write(slug: String, files: Map<String, ByteArray>): SidecarWriteOutcome = mutex.withLock {
        if (unavailable()) return SidecarWriteOutcome.Unavailable
        if (isGitSyncBusy()) {
            pending[slug] = files
            return SidecarWriteOutcome.Deferred
        }
        writeLocked(slug, files)
    }

    private fun writeLocked(slug: String, files: Map<String, ByteArray>): SidecarWriteOutcome {
        val d = dir(slug)
        ensureIgnoreRule()?.let { return SidecarWriteOutcome.Failed(it) }
        fs.createDirectory(root())
        fs.createDirectory(d)
        fs.createDirectory("$d/${SidecarCodec.SHEETS_DIR}")
        // Sheets first, the model last: it names each sheet's hash, so a torn bundle reads as Corrupt, never as valid.
        val ordered = files.entries.sortedBy { it.key == SidecarCodec.MODEL_PATH }
        for ((rel, bytes) in ordered) {
            val err = replace("$d/$rel", bytes)
            if (err != null) return SidecarWriteOutcome.Failed(err)
        }
        // Only unreferenced schema-1 files are swept. A schema-2 file (incl. a staged `<uuid>.v2.json`), a newer or an
        // unparseable one is left alone: deleteV2Sheet is the only remover. `backup/` and `conflicts/` are never touched.
        for (name in fs.listFiles("$d/${SidecarCodec.SHEETS_DIR}").filter { it.endsWith(".json") }) {
            if ("${SidecarCodec.SHEETS_DIR}/$name" in files) continue
            val path = "$d/${SidecarCodec.SHEETS_DIR}/$name"
            if (SidecarClassifier.schemaOf(fs.readFileBytes(path)) == 1L) fs.deleteFile(path)
        }
        return SidecarWriteOutcome.Written
    }

    /** Skips identical bytes so an unchanged file never dirties the git tree. */
    private fun replace(path: String, bytes: ByteArray): DomainError? {
        val existing = fs.readFileBytes(path)
        if (existing != null && existing.contentEquals(bytes)) return null
        return writer.write(path, bytes, original = existing).leftOrNull()
    }

    override suspend fun writeConflict(slug: String, files: Map<String, ByteArray>): Either<DomainError, String> = mutex.withLock {
        if (unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(dir(slug)).left()
        val model = files[SidecarCodec.MODEL_PATH]
            ?: return DomainError.FileSystemError.WriteFailed(dir(slug), "no local model to keep").left()
        val path = "${dir(slug)}/project-model.conflict-${SidecarCodec.bundleHash(files).take(HASH8)}.json"
        fs.createDirectory(dir(slug))
        replace(path, model)?.let { return it.left() }
        path.right()
    }

    override suspend fun writeBackup(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = mutex.withLock {
        val path = "${dir(slug)}/$BACKUP_DIR/$sheetUuid.v1.json"
        if (unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
        if (!SAFE_SEGMENT.matches(sheetUuid)) return DomainError.FileSystemError.WriteFailed(path, "unsafe sheet uuid").left()
        if (fs.fileExists(path)) return path.right()
        writeVerified(path, bytes)?.let { return it.left() }
        path.right()
    }

    override suspend fun writeSheetConflictCopy(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = mutex.withLock {
        val path = "${dir(slug)}/$CONFLICTS_DIR/$sheetUuid.v2.conflict-${ContentHasher.sha256(bytes).take(HASH8)}.json"
        if (unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
        if (!SAFE_SEGMENT.matches(sheetUuid)) return DomainError.FileSystemError.WriteFailed(path, "unsafe sheet uuid").left()
        writeVerified(path, bytes)?.let { return it.left() }
        path.right()
    }

    override suspend fun deleteV2Sheet(slug: String, sheetUuid: String): Either<DomainError, Unit> = mutex.withLock {
        val path = "${dir(slug)}/${SidecarCodec.SHEETS_DIR}/$sheetUuid.v2.json"
        if (unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
        if (!SAFE_SEGMENT.matches(sheetUuid)) return DomainError.FileSystemError.WriteFailed(path, "unsafe sheet uuid").left()
        if (fs.fileExists(path) && !fs.deleteFile(path)) return DomainError.FileSystemError.WriteFailed(path, "delete failed").left()
        Unit.right()
    }

    /** Creates the parent directories, writes through temp+rename, then re-reads and compares the sha. */
    private fun writeVerified(path: String, bytes: ByteArray): DomainError? {
        val dir = path.substringBeforeLast('/')
        fs.createDirectory(root())
        fs.createDirectory(dir.substringBeforeLast('/'))
        fs.createDirectory(dir)
        replace(path, bytes)?.let { return it }
        val back = fs.readFileBytes(path)
        if (back == null || ContentHasher.sha256(back) != ContentHasher.sha256(bytes)) {
            return DomainError.FileSystemError.WriteFailed(path, "read-back sha mismatch")
        }
        return null
    }

    override suspend fun writeSourceSnapshot(slug: String, hash8: String, bytes: ByteArray): SidecarWriteOutcome = mutex.withLock {
        if (unavailable()) return SidecarWriteOutcome.Unavailable
        if (isGitSyncBusy()) return SidecarWriteOutcome.Deferred
        val d = "${dir(slug)}/sources"
        fs.createDirectory(root())
        fs.createDirectory(dir(slug))
        fs.createDirectory(d)
        replace("$d/yaml-$hash8.yaml", bytes)?.let { return SidecarWriteOutcome.Failed(it) }
        SidecarWriteOutcome.Written
    }

    override suspend fun readSourceSnapshot(slug: String, hash8: String): ByteArray? = mutex.withLock {
        if (unavailable() || !YamlSourceEntry.isValidHash(hash8)) null else fs.readFileBytes("${dir(slug)}/sources/yaml-$hash8.yaml")
    }

    override suspend fun flushPending(): Int = mutex.withLock {
        if (pending.isEmpty() || isGitSyncBusy() || unavailable()) return 0
        val queued = pending.entries.toList()
        pending.clear()
        var written = 0
        for ((slug, files) in queued) {
            if (writeLocked(slug, files) == SidecarWriteOutcome.Written) written++ else pending[slug] = files
        }
        written
    }

    override suspend fun slugsOnDisk(): List<String> = mutex.withLock {
        if (unavailable()) emptyList() else fs.listDirectories(root()).sorted()
    }

    /** Adds `assets/stelekit-sketch/**/*.tmp` to the graph's `.gitignore` so a crash leftover is never committed. */
    private fun ensureIgnoreRule(): DomainError? {
        val path = "${graphRoot.trimEnd('/')}/.gitignore"
        val existing = fs.readFileBytes(path)
        val text = existing?.decodeToString().orEmpty()
        if (text.lineSequence().any { it.trim() == IGNORE_RULE }) return null
        val sep = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        return writer.write(path, (text + sep + IGNORE_RULE + "\n").encodeToByteArray(), original = existing).leftOrNull()
    }

    companion object {
        const val ROOT_DIR = "assets/stelekit-sketch"
        const val IGNORE_RULE = "assets/stelekit-sketch/**/*.tmp"
        const val HASH8 = 8
        const val BACKUP_DIR = "backup"
        const val CONFLICTS_DIR = "conflicts"
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
    }
}
