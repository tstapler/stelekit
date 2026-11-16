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

    /**
     * An opaque (v2) sheet file on disk no longer has the sha the caller planned against, so another writer changed it.
     * Nothing was written; the caller re-reads and re-plans.
     */
    data object StaleOpaque : SidecarWriteOutcome
}

/**
 * The in-graph sidecar bundle of each sketch project, under `assets/stelekit-sketch/<slug>/`. Sidecar files are
 * assets, not pages: they are written here, never through `GraphWriter`.
 */
interface ProjectSidecarStore {
    suspend fun read(slug: String): SidecarRead
    /**
     * Writes [files]. [opaque] maps each v2 sheet path to the sha the caller planned against: those paths are never
     * replaced or deleted here, and a mismatch with the bytes on disk (checked under the store mutex, and again when a
     * parked write is replayed) writes nothing and returns [SidecarWriteOutcome.StaleOpaque].
     */
    suspend fun write(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String> = emptyMap()): SidecarWriteOutcome

    /** Like [write] but never parks: while a git sync is in flight it returns [SidecarWriteOutcome.Deferred] and keeps nothing. */
    suspend fun writeNow(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String> = emptyMap()): SidecarWriteOutcome

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

    /**
     * Prunes `conflicts/` copies of [sheetUuid] per [RetentionPolicy] (newest kept, [unresolved] file names never
     * deleted); returns how many were deleted. `backup/` is never pruned.
     */
    suspend fun pruneConflictCopies(slug: String, sheetUuid: String, unresolved: Set<String>): Either<DomainError, Int>

    /** File counts and bytes held by this project's `backup/` and `conflicts/` (for Settings; Task 6.2.6c). */
    suspend fun retentionSize(slug: String): RetentionPolicy.Size

    /** Deletes `sheets/<sheetUuid>.v2.json`; the only way a schema-2 file is removed (converter cleanup, restore). */
    suspend fun deleteV2Sheet(slug: String, sheetUuid: String): Either<DomainError, Unit>

    /**
     * Reads `sheets/<sheetUuid>.v2.json` whether or not the model references it (the converter's staged file, which
     * [read] never opens); null when it is absent, the uuid is unsafe or the platform cannot store it.
     */
    suspend fun readV2SheetFile(slug: String, sheetUuid: String): ByteArray?

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
    private class PendingWrite(val files: Map<String, ByteArray>, val opaque: Map<String, String>)

    private val pending = LinkedHashMap<String, PendingWrite>()

    /** How many parked writes [flushPending] dropped as stale since this store was created (diagnostics and tests). */
    var staleOpaqueDropped = 0
        private set

    private val disk = SidecarDisk(fs, graphRoot, writer)

    override suspend fun read(slug: String): SidecarRead = mutex.withLock {
        if (disk.unavailable()) return SidecarRead.Unavailable
        readLocked(slug)
    }

    /**
     * Reads the model first and then ONLY the sheet files it references. A file under `sheets/` that no `sheetRef`
     * names (a staged `.v2.json`, a leftover) is never opened or classified, so it cannot make the project Corrupt
     * or enter `files` and `bundleHash`; `backup/` and `conflicts/` are never listed.
     */
    private fun readLocked(slug: String): SidecarRead {
        val d = disk.dir(slug)
        val modelPath = "$d/${SidecarCodec.MODEL_PATH}"
        val model = fs.readFileBytes(modelPath)
        // A model deleted by a torn replace (crash between delete and rename) is Absent: the database restores it.
        if (model == null) return SidecarRead.Absent
        val files = linkedMapOf<String, ByteArray>()
        when (val state = SidecarClassifier.classifyJson(model, SidecarFileKind.Model)) {
            SidecarFileState.Absent -> return SidecarRead.Corrupt(CorruptReason.Truncated, modelPath)
            is SidecarFileState.Corrupt -> return SidecarRead.Corrupt(state.reason, modelPath)
            is SidecarFileState.Valid -> files[SidecarCodec.MODEL_PATH] = state.bytes
        }
        val sheetPaths = ModelSheetRefs.referencedSheetPaths(model) ?: return SidecarRead.Corrupt(CorruptReason.InvalidJson, modelPath)
        for (rel in sheetPaths) {
            val abs = "$d/$rel"
            when (val state = SidecarClassifier.classifyJson(fs.readFileBytes(abs), SidecarFileKind.Sheet)) {
                SidecarFileState.Absent -> return SidecarRead.Corrupt(CorruptReason.Truncated, abs)
                is SidecarFileState.Corrupt -> return SidecarRead.Corrupt(state.reason, abs)
                is SidecarFileState.Valid -> files[rel] = state.bytes
            }
        }
        return SidecarRead.Present(files)
    }

    override suspend fun write(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome = mutex.withLock {
        if (disk.unavailable()) return SidecarWriteOutcome.Unavailable
        if (isGitSyncBusy()) {
            pending[slug] = PendingWrite(files, opaque)
            return SidecarWriteOutcome.Deferred
        }
        writeLocked(slug, files, opaque)
    }

    override suspend fun writeNow(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String>): SidecarWriteOutcome = mutex.withLock {
        if (disk.unavailable()) return SidecarWriteOutcome.Unavailable
        if (isGitSyncBusy()) return SidecarWriteOutcome.Deferred
        writeLocked(slug, files, opaque)
    }

    /**
     * A parked write is an intent built from the model it read: it is stale when an opaque file changed or when the
     * model on disk now names a different file for a sheet the parked model also names (a conversion or restore
     * flipped it), so a replay can never revert that flip or delete a v2 file.
     */
    private fun parkedIsStale(slug: String, parked: PendingWrite): Boolean {
        if (disk.opaqueChanged(slug, parked.opaque)) return true
        val parkedModel = parked.files[SidecarCodec.MODEL_PATH]?.let { ModelSheetRefs.sheetFiles(it) } ?: return false
        val diskModel = fs.readFileBytes("${disk.dir(slug)}/${SidecarCodec.MODEL_PATH}")?.let { ModelSheetRefs.sheetFiles(it) } ?: return false
        return parkedModel.any { (uuid, file) -> diskModel[uuid]?.let { it != file } == true }
    }

    private fun writeLocked(slug: String, files: Map<String, ByteArray>, opaque: Map<String, String> = emptyMap()): SidecarWriteOutcome {
        if (disk.opaqueChanged(slug, opaque)) return SidecarWriteOutcome.StaleOpaque
        val d = disk.dir(slug)
        disk.ensureIgnoreRule()?.let { return SidecarWriteOutcome.Failed(it) }
        fs.createDirectory(disk.root())
        fs.createDirectory(d)
        fs.createDirectory("$d/${SidecarCodec.SHEETS_DIR}")
        // Sheets first, the model last: it names each sheet's hash, so a torn bundle reads as Corrupt, never as valid.
        val ordered = files.entries.filter { it.key !in opaque }.sortedBy { it.key == SidecarCodec.MODEL_PATH }
        for ((rel, bytes) in ordered) {
            val err = disk.replace("$d/$rel", bytes)
            if (err != null) return SidecarWriteOutcome.Failed(err)
        }
        // Only unreferenced schema-1 files are swept. A schema-2 file (incl. a staged `<uuid>.v2.json`), a newer or an
        // unparseable one is left alone: deleteV2Sheet is the only remover. `backup/` and `conflicts/` are never touched.
        for (name in fs.listFiles("$d/${SidecarCodec.SHEETS_DIR}").filter { it.endsWith(".json") }) {
            val rel = "${SidecarCodec.SHEETS_DIR}/$name"
            if (rel in files || rel in opaque) continue
            val path = "$d/${SidecarCodec.SHEETS_DIR}/$name"
            if (SidecarClassifier.schemaOf(fs.readFileBytes(path)) == 1L) fs.deleteFile(path)
        }
        return SidecarWriteOutcome.Written
    }

    override suspend fun writeConflict(slug: String, files: Map<String, ByteArray>): Either<DomainError, String> = mutex.withLock {
        if (disk.unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(disk.dir(slug)).left()
        val model = files[SidecarCodec.MODEL_PATH]
            ?: return DomainError.FileSystemError.WriteFailed(disk.dir(slug), "no local model to keep").left()
        val path = "${disk.dir(slug)}/project-model.conflict-${SidecarCodec.bundleHash(files).take(HASH8)}.json"
        fs.createDirectory(disk.dir(slug))
        disk.replace(path, model)?.let { return it.left() }
        path.right()
    }

    override suspend fun writeBackup(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = mutex.withLock {
        val path = "${disk.dir(slug)}/$BACKUP_DIR/$sheetUuid.v1.json"
        if (disk.unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
        if (!SAFE_SEGMENT.matches(sheetUuid)) return DomainError.FileSystemError.WriteFailed(path, "unsafe sheet uuid").left()
        if (fs.fileExists(path)) return path.right()
        disk.writeVerified(path, bytes)?.let { return it.left() }
        path.right()
    }

    override suspend fun writeSheetConflictCopy(slug: String, sheetUuid: String, bytes: ByteArray): Either<DomainError, String> = mutex.withLock {
        val path = "${disk.dir(slug)}/$CONFLICTS_DIR/$sheetUuid.v2.conflict-${ContentHasher.sha256(bytes).take(HASH8)}.json"
        if (disk.unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
        if (!SAFE_SEGMENT.matches(sheetUuid)) return DomainError.FileSystemError.WriteFailed(path, "unsafe sheet uuid").left()
        disk.writeVerified(path, bytes)?.let { return it.left() }
        path.right()
    }

    override suspend fun pruneConflictCopies(slug: String, sheetUuid: String, unresolved: Set<String>): Either<DomainError, Int> = mutex.withLock {
        val dir = "${disk.dir(slug)}/$CONFLICTS_DIR"
        if (disk.unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(dir).left()
        if (!SAFE_SEGMENT.matches(sheetUuid)) return DomainError.FileSystemError.WriteFailed(dir, "unsafe sheet uuid").left()
        val copies = fs.listFiles(dir).filter { RetentionPolicy.sheetOfConflictCopy(it) == sheetUuid }
            .map { RetentionPolicy.ConflictCopy(it, fs.getLastModifiedTime("$dir/$it") ?: 0L) }
        var deleted = 0
        for (name in RetentionPolicy.conflictCopiesToPrune(copies, unresolved)) {
            if (!fs.deleteFile("$dir/$name")) return DomainError.FileSystemError.WriteFailed("$dir/$name", "delete failed").left()
            deleted++
        }
        deleted.right()
    }

    override suspend fun retentionSize(slug: String): RetentionPolicy.Size = mutex.withLock {
        if (disk.unavailable()) return RetentionPolicy.Size(0, 0, 0, 0)
        fun measure(sub: String): Pair<Int, Long> {
            val dir = "${disk.dir(slug)}/$sub"
            val names = fs.listFiles(dir)
            return names.size to names.sumOf { fs.getFileSize("$dir/$it") ?: 0L }
        }
        val (bf, bb) = measure(BACKUP_DIR)
        val (cf, cb) = measure(CONFLICTS_DIR)
        RetentionPolicy.Size(bf, bb, cf, cb)
    }

    override suspend fun deleteV2Sheet(slug: String, sheetUuid: String): Either<DomainError, Unit> = mutex.withLock {
        val path = "${disk.dir(slug)}/${SidecarCodec.SHEETS_DIR}/$sheetUuid.v2.json"
        if (disk.unavailable()) return DomainError.FileSystemError.AtomicWriteUnsupported(path).left()
        if (!SAFE_SEGMENT.matches(sheetUuid)) return DomainError.FileSystemError.WriteFailed(path, "unsafe sheet uuid").left()
        if (fs.fileExists(path) && !fs.deleteFile(path)) return DomainError.FileSystemError.WriteFailed(path, "delete failed").left()
        Unit.right()
    }

    override suspend fun readV2SheetFile(slug: String, sheetUuid: String): ByteArray? = mutex.withLock {
        if (disk.unavailable() || !SAFE_SEGMENT.matches(sheetUuid)) null
        else fs.readFileBytes("${disk.dir(slug)}/${SidecarCodec.SHEETS_DIR}/$sheetUuid.v2.json")
    }

    override suspend fun writeSourceSnapshot(slug: String, hash8: String, bytes: ByteArray): SidecarWriteOutcome = mutex.withLock {
        if (disk.unavailable()) return SidecarWriteOutcome.Unavailable
        if (isGitSyncBusy()) return SidecarWriteOutcome.Deferred
        val d = "${disk.dir(slug)}/sources"
        fs.createDirectory(disk.root())
        fs.createDirectory(disk.dir(slug))
        fs.createDirectory(d)
        disk.replace("$d/yaml-$hash8.yaml", bytes)?.let { return SidecarWriteOutcome.Failed(it) }
        SidecarWriteOutcome.Written
    }

    override suspend fun readSourceSnapshot(slug: String, hash8: String): ByteArray? = mutex.withLock {
        if (disk.unavailable() || !YamlSourceEntry.isValidHash(hash8)) null else fs.readFileBytes("${disk.dir(slug)}/sources/yaml-$hash8.yaml")
    }

    override suspend fun flushPending(): Int = mutex.withLock {
        if (pending.isEmpty() || isGitSyncBusy() || disk.unavailable()) return 0
        val queued = pending.entries.toList()
        pending.clear()
        var written = 0
        for ((slug, parked) in queued) {
            // A parked write is an intent, not a byte map: a stale one is dropped and the reconciler re-plans.
            if (parkedIsStale(slug, parked)) { staleOpaqueDropped++; continue }
            when (writeLocked(slug, parked.files, parked.opaque)) {
                SidecarWriteOutcome.Written -> written++
                SidecarWriteOutcome.StaleOpaque -> staleOpaqueDropped++
                else -> pending[slug] = parked
            }
        }
        written
    }

    override suspend fun slugsOnDisk(): List<String> = mutex.withLock {
        if (disk.unavailable()) emptyList() else fs.listDirectories(disk.root()).sorted()
    }

    companion object {
        const val ROOT_DIR = "assets/stelekit-sketch"
        const val IGNORE_RULE = "assets/stelekit-sketch/**/*.tmp"
        const val HASH8 = 8
        const val BACKUP_DIR = "backup"
        const val CONFLICTS_DIR = "conflicts"
    }
}

private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

/** Reads the sheet references out of a project model's JSON; pure, no I/O. */
private object ModelSheetRefs {
    /**
     * Sorted relative paths of the sheet files [modelBytes] references. A `file` that is not the rule path of its
     * uuid (`sheets/<uuid>.json` or `sheets/<uuid>.v2.json`), or a uuid that is not one path segment, yields null:
     * no arbitrary path is read from git-synced input.
     */
    fun referencedSheetPaths(modelBytes: ByteArray): List<String>? {
        return try {
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
    }

    /** uuid to referenced sheet file (rule default when no `file`), or null when [modelBytes] is not a model. */
    fun sheetFiles(modelBytes: ByteArray): Map<String, String>? = try {
        Json.parseToJsonElement(modelBytes.decodeToString()).jsonObject["sheets"]?.jsonArray.orEmpty().associate { ref ->
            val o = ref.jsonObject
            val uuid = o.getValue("uuid").jsonPrimitive.content
            uuid to (o["file"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: "${SidecarCodec.SHEETS_DIR}/$uuid.json")
        }
    } catch (_: Exception) {
        null
    }
}

/** File-level operations of [FileProjectSidecarStore]: path layout, temp-and-rename writes, the `.gitignore` rule. */
private class SidecarDisk(private val fs: FileSystem, private val graphRoot: String, private val writer: AtomicFileWriter) {
    fun root() = "${graphRoot.trimEnd('/')}/${FileProjectSidecarStore.ROOT_DIR}"
    fun dir(slug: String) = "${root()}/$slug"
    fun unavailable() = writer.mode(root()) == AtomicWriteMode.UNSUPPORTED

    /** True when any opaque path's bytes on disk no longer hash to the sha the writer planned against. */
    fun opaqueChanged(slug: String, opaque: Map<String, String>): Boolean = opaque.any { (rel, sha) ->
        fs.readFileBytes("${dir(slug)}/$rel")?.let { ContentHasher.sha256(it) } != sha
    }

    /** Skips identical bytes so an unchanged file never dirties the git tree. */
    fun replace(path: String, bytes: ByteArray): DomainError? {
        val existing = fs.readFileBytes(path)
        if (existing != null && existing.contentEquals(bytes)) return null
        return writer.write(path, bytes, original = existing).leftOrNull()
    }

    /** Creates the parent directories, writes through temp+rename, then re-reads and compares the sha. */
    fun writeVerified(path: String, bytes: ByteArray): DomainError? {
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

    /**
     * Adds `assets/stelekit-sketch/**/*.tmp` (a crash leftover is never committed) and the device-local `conflicts/`
     * rule to the graph's `.gitignore`. Only missing lines are appended; an existing line is never rewritten.
     */
    fun ensureIgnoreRule(): DomainError? {
        val path = "${graphRoot.trimEnd('/')}/.gitignore"
        val existing = fs.readFileBytes(path)
        val text = existing?.decodeToString().orEmpty()
        val present = text.lineSequence().map { it.trim() }.toSet()
        val missing = listOf(FileProjectSidecarStore.IGNORE_RULE, RetentionPolicy.CONFLICTS_IGNORE_RULE).filter { it !in present }
        if (missing.isEmpty()) return null
        val sep = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        return writer.write(path, (text + sep + missing.joinToString("") { it + "\n" }).encodeToByteArray(), original = existing).leftOrNull()
    }
}
