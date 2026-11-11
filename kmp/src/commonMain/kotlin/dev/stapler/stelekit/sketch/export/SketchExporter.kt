// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.export

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.AtomicFileWriter
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.platform.AtomicWriteMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.sketch.SketchDocument

/**
 * Writes a sheet's SVG and CSV into the project's `exports/` folder under the sidecar area
 * (`assets/stelekit-sketch/<slug>/exports`) through [AtomicFileWriter]. Names are `<slug>-<kind>-r<revision>`
 * so they never carry a page title. Platforms without temp-and-rename writes (wasmJs, iOS) get
 * `Left(AtomicWriteUnsupported)` and nothing is written. These files are never graph markdown.
 */
class SketchExporter(
    private val fs: FileSystem,
    private val writer: AtomicFileWriter,
    private val graphRoot: String,
) {
    /** Returns the paths written, SVG first. */
    fun export(doc: SketchDocument, slug: String, revision: Long): Either<DomainError, List<String>> = either {
        val dir = exportDir(slug)
        if (!SLUG.matches(slug)) raise(DomainError.FileSystemError.WriteFailed(dir, "invalid project slug"))
        if (writer.mode(dir) == AtomicWriteMode.UNSUPPORTED) raise(DomainError.FileSystemError.AtomicWriteUnsupported(dir))
        val root = "${graphRoot.trimEnd('/')}/${FileProjectSidecarStore.ROOT_DIR}"
        listOf(root, "$root/$slug", dir).forEach { fs.createDirectory(it) }
        if (!fs.directoryExists(dir)) raise(DomainError.FileSystemError.WriteFailed(dir, "could not create the export folder"))
        val base = "$dir/$slug-${doc.sheet.kind.wire}-r$revision"
        val svgPath = "$base.svg"
        val csvPath = "$base.csv"
        replace(svgPath, SketchSvgWriter.write(doc).encodeToByteArray()).bind()
        replace(csvPath, SketchCsvWriter.write(doc.exportDimensions()).encodeToByteArray()).bind()
        listOf(svgPath, csvPath)
    }

    private fun replace(path: String, bytes: ByteArray): Either<DomainError, Unit> {
        val existing = fs.readFileBytes(path)
        if (existing != null && existing.contentEquals(bytes)) return Unit.right()
        return writer.write(path, bytes, original = existing).fold({ it.left() }, { Unit.right() })
    }

    private fun exportDir(slug: String) = "${graphRoot.trimEnd('/')}/${FileProjectSidecarStore.ROOT_DIR}/$slug/$EXPORTS"

    private companion object {
        const val EXPORTS = "exports"
        val SLUG = Regex("[A-Za-z0-9-]{1,64}")
    }
}
