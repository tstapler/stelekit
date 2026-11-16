// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.projection

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.CorruptReason
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A v2 sheet projected onto v1 primitives, in memory only (Story 1.4.5, ADR-002). [sha] is the sha256 of the file the
 * projection came from; [droppedCount] is the number of elements v1 cannot hold (rotated rectangles, curves, text, ...).
 */
class SheetProjection(
    val sha: String,
    val primitives: List<SketchPrimitive>,
    val layers: List<SketchLayer>,
    val droppedCount: Int,
    /** The decoded v2 scene the projection came from; the embed preview draws it directly (Story 6.1.1). */
    val scene: SketchSceneV2 = SketchSceneV2.empty(),
)

/** What reading a v2 sheet gave: the projection, a file that needs a newer app, or any other failure. */
sealed interface SheetProjectionRead {
    class Loaded(val projection: SheetProjection) : SheetProjectionRead

    /** The sheet, or the model that lists it, declares a schema this build does not know (never a crash). */
    data object NewerSchema : SheetProjectionRead

    class Failed(val error: DomainError) : SheetProjectionRead
}

/**
 * Loads a v2 sheet's scene from the sidecar file, projects it with [V2ToV1] and hands the primitives to the v1 consumers
 * (preview, CSV, SVG, fusion). The projection is never written to `sketch_primitives`, so a consumer can never read a
 * stale v1 row for a converted sheet. One projection per sheet is kept, keyed by the file's sha, so a repeat read of an
 * unchanged file skips the decode; an edited file replaces the entry.
 */
class SheetProjectionSource(private val storeProvider: () -> ProjectSidecarStore?) {
    private val mutex = Mutex()
    private val cache = HashMap<SheetUuid, SheetProjection>()

    suspend fun project(slug: String, sheet: SheetUuid): Either<DomainError, SheetProjection> =
        when (val read = read(slug, sheet)) {
            is SheetProjectionRead.Loaded -> read.projection.right()
            SheetProjectionRead.NewerSchema -> DomainError.FileSystemError.ReadFailed(SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2), "sheet needs a newer version").left()
            is SheetProjectionRead.Failed -> read.error.left()
        }

    /** Like [project] but says when the file is from a newer app, so a caller can show "Update SteleKit to view". */
    suspend fun read(slug: String, sheet: SheetUuid): SheetProjectionRead {
        val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)
        val store = storeProvider() ?: return failed(DomainError.FileSystemError.ReadFailed(path, "no sidecar store on this platform"))
        val bytes = when (val read = store.read(slug)) {
            is SidecarRead.Present -> read.files[path] ?: return failed(DomainError.FileSystemError.NotFound(path))
            SidecarRead.Absent -> return failed(DomainError.FileSystemError.NotFound(path))
            SidecarRead.Unavailable -> return failed(DomainError.FileSystemError.ReadFailed(path, "sidecar unavailable"))
            is SidecarRead.Corrupt ->
                return if (read.reason == CorruptReason.NewerSchema) SheetProjectionRead.NewerSchema else failed(DomainError.FileSystemError.ReadFailed(path, "sidecar corrupt"))
        }
        val sha = ContentHasher.sha256(bytes)
        return mutex.withLock {
            cache[sheet]?.takeIf { it.sha == sha }?.let { SheetProjectionRead.Loaded(it) } ?: decode(path, sha, bytes).also { read ->
                if (read is SheetProjectionRead.Loaded) cache[sheet] = read.projection
            }
        }
    }

    private fun failed(error: DomainError): SheetProjectionRead = SheetProjectionRead.Failed(error)

    private fun decode(path: String, sha: String, bytes: ByteArray): SheetProjectionRead =
        SheetCodecV2.decode(bytes).fold(
            { error ->
                val newer = (error as? SheetError.UnsupportedSchema)?.schema?.let { it > SheetPaths.SCHEMA_V2 } == true
                if (newer) SheetProjectionRead.NewerSchema else failed(DomainError.FileSystemError.ReadFailed(path, "sheet not readable: ${error::class.simpleName}"))
            },
            { doc ->
                val projection = V2ToV1.projectScene(doc.scene)
                SheetProjectionRead.Loaded(SheetProjection(sha, projection.primitives, projection.layers, projection.droppedCount, doc.scene))
            },
        )
}
