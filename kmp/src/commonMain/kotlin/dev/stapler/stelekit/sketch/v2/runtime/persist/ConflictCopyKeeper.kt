// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/**
 * Keeps the editor's scene beside a conflicted sheet so nothing is discarded without a copy. It remembers the newest
 * submitted version it already holds, so a scene is written once however often the saver asks.
 */
internal class ConflictCopyKeeper(
    private val target: SheetTarget,
    private val ports: SheetSaverPorts,
    private val limits: SceneLimits,
) {
    private var keptVersion = NONE

    /** Records that the copy written at conflict time already holds [version]. */
    fun noteKept(version: Long) {
        keptVersion = version
    }

    /** Writes [doc] as a conflict copy unless [version] is already kept; the path of the copy, or null when nothing was needed. */
    suspend fun keep(version: Long, doc: SketchDocumentV2): Either<DomainError, String?> {
        if (version == keptVersion) return (null as String?).right()
        val bytes = ports.encoder(doc, limits).getOrNull()
            ?: return DomainError.FileSystemError.WriteFailed(target.slug, "scene could not be encoded").left()
        return SheetSaveGuard.writeConflictCopy(ports.store, target.slug, target.sheet, bytes).map { path ->
            keptVersion = version
            path
        }
    }

    private companion object {
        const val NONE = -1L
    }
}
