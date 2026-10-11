// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.export

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem

/**
 * One-shot zip export of a graph's markdown content, for the "Export as .zip" affordance in the
 * plain-graph `AppOwned` warning (ADR-003, `design/ux.md` Surface 11). Only implemented on
 * Android today ([dev.stapler.stelekit.export.AndroidGraphZipExporter], `java.util.zip.ZipOutputStream`) —
 * ADR-003's Amendment gives Web its own hand-rolled stored-only writer in Epic 2.3, which is why
 * this lives behind [dev.stapler.stelekit.ui.rememberGraphZipExporter] returning null on every
 * other platform rather than as a single shared implementation.
 */
interface GraphZipExporter {
    /**
     * Recursively reads every file under [graphPath] via [fileSystem], zips it, and hands the
     * result to the platform's native share/save-file mechanism. [graphName] seeds the suggested
     * file/share name. Right(Unit) covers both "shared successfully" and "user cancelled the
     * share sheet" — per `design/ux.md` Surface 11, export is a convenience, never a gate on
     * graph creation, so callers only need to distinguish "no hard failure" from
     * [DomainError.ExportError.ShareFailed].
     */
    suspend fun export(fileSystem: FileSystem, graphPath: String, graphName: String): Either<DomainError, Unit>
}
