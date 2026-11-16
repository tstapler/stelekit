// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.image.ImageHeaderProbe
import dev.stapler.stelekit.sketch.v2.image.ReferencedAssets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One image file of a sketch: its name under the graph's `assets/` folder (`<fileId>.<ext>`) and bytes. */
class SketchAsset(val fileName: String, val bytes: ByteArray)

/**
 * A whole sketch project as files: [files] are relative to `assets/stelekit-sketch/<slug>/` (the model and the sheets),
 * [assets] are the images its v2 sheets reference, and [missingAssets] are referenced `fileId`s the source did not have.
 */
class SketchBundle(
    val slug: String,
    val files: Map<String, ByteArray>,
    val assets: List<SketchAsset>,
    val missingAssets: List<String>,
) {
    /** The same bundle under another slug; the model's own `slug` field is rewritten, nothing else changes. */
    fun renamed(newSlug: String): Either<DomainError, SketchBundle> {
        val model = files[SidecarCodec.MODEL_PATH] ?: return DomainError.FileSystemError.NotFound(SidecarCodec.MODEL_PATH).left()
        val rewritten = try {
            val obj = Json.parseToJsonElement(model.decodeToString()) as JsonObject
            val next = JsonObject(obj + ("slug" to JsonPrimitive(newSlug)))
            (PRETTY.encodeToString(JsonElement.serializer(), next) + "\n").encodeToByteArray()
        } catch (e: IllegalArgumentException) {
            return DomainError.FileSystemError.ReadFailed(SidecarCodec.MODEL_PATH, e.message ?: "model is not JSON").left()
        } catch (e: ClassCastException) {
            return DomainError.FileSystemError.ReadFailed(SidecarCodec.MODEL_PATH, e.message ?: "model is not an object").left()
        }
        return SketchBundle(newSlug, files + (SidecarCodec.MODEL_PATH to rewritten), assets, missingAssets).right()
    }

    private companion object {
        val PRETTY = Json { prettyPrint = true }
    }
}

/** What a [SketchBundleWriter] did. A refusal writes nothing. */
sealed interface SketchBundleOutcome {
    data class Created(val assetsWritten: Int, val assetsReused: Int) : SketchBundleOutcome

    /** The target already has a sketch under this slug; no existing file was touched. */
    data class SlugExists(val slug: String) : SketchBundleOutcome

    /** The target has an image of this name with different bytes; it is never modified. */
    data class AssetConflict(val fileName: String) : SketchBundleOutcome
}

/**
 * A [TargetWriter] that can also create a sketch project, the way `OwnedPageWriter` is the optional capability for digest
 * pages. Both the active and the off-graph writer implement it; the router calls it under `GraphWriteLock(target)`.
 */
interface SketchBundleWriter {
    suspend fun writeSketchBundle(bundle: SketchBundle): Either<DomainError, SketchBundleOutcome>
}

/**
 * Creates a sketch bundle under a graph root with file writes only (Story 6.1.2). Never modifies an existing file: a
 * slug that already exists refuses before anything is written, an image whose name exists with other bytes refuses,
 * an identical one is reused, and the model file is written last so a failure part-way leaves no sketch the
 * reconciler could pick up; whatever this call created is removed again.
 */
class SketchBundleFiles(private val fs: FileSystem, graphRoot: String) {
    private val root = graphRoot.trimEnd('/')
    private val assetsDir = "$root/assets"
    private val sketchRoot = "$root/${FileProjectSidecarStore.ROOT_DIR}"

    fun write(bundle: SketchBundle): Either<DomainError, SketchBundleOutcome> {
        refusal(bundle)?.let { return it.left() }
        val dir = "$sketchRoot/${bundle.slug}"
        if (fs.directoryExists(dir) || fs.fileExists("$dir/${SidecarCodec.MODEL_PATH}")) return SketchBundleOutcome.SlugExists(bundle.slug).right()
        val conflict = bundle.assets.firstOrNull { a -> fs.readFileBytes("$assetsDir/${a.fileName}")?.let { !it.contentEquals(a.bytes) } == true }
        if (conflict != null) return SketchBundleOutcome.AssetConflict(conflict.fileName).right()

        val created = ArrayList<String>()
        val createdDirs = ArrayList<String>()
        fun fail(path: String): Either<DomainError, SketchBundleOutcome> {
            created.asReversed().forEach { fs.deleteFile(it) }
            createdDirs.asReversed().forEach { fs.deleteFile(it) }
            return DomainError.FileSystemError.WriteFailed(path, "could not write the sketch bundle").left()
        }
        fun put(path: String, bytes: ByteArray): Boolean {
            val parent = path.substringBeforeLast('/')
            if (!fs.directoryExists(parent)) {
                if (!fs.createDirectory(parent)) return false
                createdDirs += parent
            }
            if (!fs.writeFileBytes(path, bytes)) return false
            created += path
            return fs.readFileBytes(path)?.contentEquals(bytes) == true
        }

        var written = 0
        var reused = 0
        for (asset in bundle.assets) {
            val path = "$assetsDir/${asset.fileName}"
            if (fs.fileExists(path)) { reused++; continue }
            if (!put(path, asset.bytes)) return fail(path)
            written++
        }
        val ordered = bundle.files.entries.sortedBy { if (it.key == SidecarCodec.MODEL_PATH) 1 else 0 }
        for ((rel, bytes) in ordered) {
            if (!put("$dir/$rel", bytes)) return fail("$dir/$rel")
        }
        return SketchBundleOutcome.Created(written, reused).right()
    }

    /** A bundle that names a path outside its own folders is refused before any write. */
    private fun refusal(bundle: SketchBundle): DomainError? {
        if (!SAFE_SEGMENT.matches(bundle.slug)) return WriteRefusedReason.InvalidPageName(bundle.slug).let { DomainError.MergeError.WriteRefused(it) }
        val unsafeFile = bundle.files.keys.firstOrNull { !SAFE_RELATIVE.matches(it) || ".." in it.split('/') }
        val unsafeAsset = bundle.assets.firstOrNull { !SAFE_ASSET.matches(it.fileName) }
        val bad = unsafeFile ?: unsafeAsset?.fileName ?: return null
        return DomainError.MergeError.WriteRefused(WriteRefusedReason.PathOutsideGraph(bad))
    }

    private companion object {
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
        val SAFE_RELATIVE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*(/[A-Za-z0-9][A-Za-z0-9_.-]*)*")
        val SAFE_ASSET = Regex("[0-9a-f]+\\.[a-z0-9]+")
    }
}

/** Why a sketch copy did not happen. Nothing is left behind in any case. */
sealed interface SketchCopyError {
    /** The target already has this slug; the dialog offers Rename and copy or Cancel. */
    data class SlugExists(val slug: String) : SketchCopyError

    /** An image of the same name but different bytes is in the target's assets. */
    data class AssetConflict(val fileName: String) : SketchCopyError

    /** The source platform has no file storage for v2 sketches (UX-135, D-118). */
    data object V2StorageUnavailable : SketchCopyError

    data class SourceMissing(val slug: String) : SketchCopyError

    data class SourceCorrupt(val path: String) : SketchCopyError

    /** The router or a writer refused or failed; [error] says why (an unwritable target is listed disabled before this). */
    data class Failed(val error: DomainError) : SketchCopyError
}

/** Where "Open" goes after a successful copy. */
data class SketchOpenTarget(val graph: GraphId, val slug: String)

data class SketchCopyResult(
    val slug: String,
    val destination: GraphId,
    val destinationName: String,
    val open: SketchOpenTarget,
    /** Referenced images the source did not have; reported, never left dangling silently. */
    val missingAssets: List<String>,
)

/**
 * Copies a whole sketch project to another graph through [TargetWriterRouter] (Story 6.1.2): the one router, under
 * `GraphWriteLock(target)`, writes by the active or the off-graph writer; this class never holds a second lock. It
 * reads the source bundle first (outside any lock), so the lock is held only for the file writes.
 */
class SketchBundleCopy(
    private val router: TargetWriterRouter,
    private val sourceStore: ProjectSidecarStore,
    private val sourceFs: FileSystem,
    sourceRoot: String,
    private val v2StorageAvailable: () -> Boolean,
    private val graphName: (GraphId) -> String,
) {
    private val sourceAssets = "${sourceRoot.trimEnd('/')}/assets"

    /** Copies [slug] to [target], as [asSlug] when given (Rename and copy). */
    suspend fun copy(slug: String, target: GraphId, asSlug: String = slug): Either<SketchCopyError, SketchCopyResult> {
        if (!v2StorageAvailable()) return SketchCopyError.V2StorageUnavailable.left()
        val read = readBundle(slug).fold({ return it.left() }, { it })
        val bundle = if (asSlug == slug) read else read.renamed(asSlug).fold({ return SketchCopyError.Failed(it).left() }, { it })
        val outcome = router.withWriter(target) { writer ->
            (writer as? SketchBundleWriter)?.writeSketchBundle(bundle)
                ?: DomainError.MergeError.Retryable("${target.value} is not writable for sketches right now; try again.").left()
        }.fold({ return SketchCopyError.Failed(it).left() }, { it })
        return when (outcome) {
            is SketchBundleOutcome.Created -> SketchCopyResult(asSlug, target, graphName(target), SketchOpenTarget(target, asSlug), bundle.missingAssets).right()
            is SketchBundleOutcome.SlugExists -> SketchCopyError.SlugExists(outcome.slug).left()
            is SketchBundleOutcome.AssetConflict -> SketchCopyError.AssetConflict(outcome.fileName).left()
        }
    }

    private suspend fun readBundle(slug: String): Either<SketchCopyError, SketchBundle> {
        val files = when (val read = sourceStore.read(slug)) {
            SidecarRead.Unavailable -> return SketchCopyError.V2StorageUnavailable.left()
            SidecarRead.Absent -> return SketchCopyError.SourceMissing(slug).left()
            is SidecarRead.Corrupt -> return SketchCopyError.SourceCorrupt(read.path).left()
            is SidecarRead.Present -> read.files
        }
        val referenced = LinkedHashSet<String>()
        for ((rel, bytes) in files) {
            if (rel == SidecarCodec.MODEL_PATH) continue
            SheetCodecV2.decode(bytes).onRight { referenced += ReferencedAssets.referencedFileIds(it.scene) }
        }
        val assets = ArrayList<SketchAsset>()
        val missing = ArrayList<String>()
        for (fileId in referenced.sorted()) {
            val file = locate(fileId)
            if (file == null) missing += fileId else assets += file
        }
        return SketchBundle(slug, files, assets, missing).right()
    }

    /** The stored image of [fileId], named by its real format, or null when the source has no readable file for it. */
    private fun locate(fileId: String): SketchAsset? {
        for (format in dev.stapler.stelekit.sketch.v2.image.ImageFormat.entries) {
            val name = "$fileId.${format.extension}"
            val bytes = sourceFs.readFileBytes("$sourceAssets/$name") ?: continue
            val header = ImageHeaderProbe.probe(bytes).getOrNull() ?: continue
            return SketchAsset("$fileId.${header.format.extension}", bytes)
        }
        return null
    }
}

/**
 * The result of a copy as the dialog shows it (Task 6.1.2c, UX-63): the message, and the actions in order. Pure, so the
 * wording is tested without a UI.
 */
data class SketchCopyDialogModel(val title: String, val detail: String?, val actions: List<SketchCopyAction>) {
    companion object {
        fun of(slug: String, destinationName: String, result: Either<SketchCopyError, SketchCopyResult>): SketchCopyDialogModel = result.fold(
            { error -> forError(slug, destinationName, error) },
            { ok ->
                SketchCopyDialogModel(
                    "Copied to ${ok.destinationName}.",
                    ok.missingAssets.takeIf { it.isNotEmpty() }?.let { "${it.size} image(s) were missing in the source and show as placeholders." },
                    listOf(SketchCopyAction.Open(ok.open), SketchCopyAction.Done),
                )
            },
        )

        private fun forError(slug: String, destinationName: String, error: SketchCopyError): SketchCopyDialogModel = when (error) {
            is SketchCopyError.SlugExists ->
                SketchCopyDialogModel("\"${error.slug}\" already exists in $destinationName.", "Nothing was changed.", listOf(SketchCopyAction.RenameAndCopy, SketchCopyAction.Cancel))
            is SketchCopyError.AssetConflict ->
                SketchCopyDialogModel("Couldn't copy", "${destinationName} already has a different image named ${error.fileName}. Nothing was changed.", listOf(SketchCopyAction.Cancel))
            SketchCopyError.V2StorageUnavailable ->
                SketchCopyDialogModel("Couldn't copy", "This device has no file storage for new-editor sketches.", listOf(SketchCopyAction.Cancel))
            is SketchCopyError.SourceMissing, is SketchCopyError.SourceCorrupt ->
                SketchCopyDialogModel("Couldn't copy", "\"$slug\" could not be read.", listOf(SketchCopyAction.Retry, SketchCopyAction.Cancel))
            is SketchCopyError.Failed ->
                SketchCopyDialogModel("Couldn't copy", error.error.message, listOf(SketchCopyAction.Retry, SketchCopyAction.Cancel))
        }
    }
}

sealed interface SketchCopyAction {
    data class Open(val target: SketchOpenTarget) : SketchCopyAction
    data object Done : SketchCopyAction
    data object RenameAndCopy : SketchCopyAction
    data object Retry : SketchCopyAction
    data object Cancel : SketchCopyAction
}
