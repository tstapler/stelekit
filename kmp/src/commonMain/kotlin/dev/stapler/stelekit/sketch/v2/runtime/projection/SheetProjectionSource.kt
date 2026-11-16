// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.projection

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.sketch.SketchLayer
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
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
)

/**
 * Loads a v2 sheet's scene from the sidecar file, projects it with [V2ToV1] and hands the primitives to the v1 consumers
 * (preview, CSV, SVG, fusion). The projection is never written to `sketch_primitives`, so a consumer can never read a
 * stale v1 row for a converted sheet. One projection per sheet is kept, keyed by the file's sha, so a repeat read of an
 * unchanged file skips the decode; an edited file replaces the entry.
 */
class SheetProjectionSource(private val storeProvider: () -> ProjectSidecarStore?) {
    private val mutex = Mutex()
    private val cache = HashMap<SheetUuid, SheetProjection>()

    suspend fun project(slug: String, sheet: SheetUuid): Either<DomainError, SheetProjection> {
        val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)
        val store = storeProvider() ?: return DomainError.FileSystemError.ReadFailed(path, "no sidecar store on this platform").left()
        val bytes = when (val read = store.read(slug)) {
            is SidecarRead.Present -> read.files[path] ?: return DomainError.FileSystemError.NotFound(path).left()
            SidecarRead.Absent -> return DomainError.FileSystemError.NotFound(path).left()
            SidecarRead.Unavailable -> return DomainError.FileSystemError.ReadFailed(path, "sidecar unavailable").left()
            is SidecarRead.Corrupt -> return DomainError.FileSystemError.ReadFailed(path, "sidecar corrupt").left()
        }
        val sha = ContentHasher.sha256(bytes)
        return mutex.withLock {
            cache[sheet]?.takeIf { it.sha == sha }?.right() ?: decode(path, sha, bytes).onRight { cache[sheet] = it }
        }
    }

    private fun decode(path: String, sha: String, bytes: ByteArray): Either<DomainError, SheetProjection> =
        SheetCodecV2.decode(bytes).fold(
            { DomainError.FileSystemError.ReadFailed(path, "sheet not readable: ${it::class.simpleName}").left() },
            { doc ->
                val projection = V2ToV1.projectScene(doc.scene)
                SheetProjection(sha, projection.primitives, projection.layers, projection.droppedCount).right()
            },
        )
}
