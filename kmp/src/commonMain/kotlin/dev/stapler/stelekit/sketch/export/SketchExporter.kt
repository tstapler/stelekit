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
import dev.stapler.stelekit.platform.AtomicWriteMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.sketch.SketchDocument

/**
 * Writes a sheet's SVG and CSV into [dir] through [AtomicFileWriter]. Platforms without temp-and-rename
 * writes (wasmJs, iOS) get `Left(AtomicWriteUnsupported)` and nothing is written. [dir] must not be the
 * sidecar bundle directory and these files are never graph markdown.
 */
class SketchExporter(
    private val fs: FileSystem,
    private val writer: AtomicFileWriter,
    private val dir: String,
) {
    /** Returns the paths written, SVG first. */
    fun export(doc: SketchDocument): Either<DomainError, List<String>> = either {
        if (writer.mode(dir) == AtomicWriteMode.UNSUPPORTED) raise(DomainError.FileSystemError.AtomicWriteUnsupported(dir))
        if (!fs.createDirectory(dir) && !fs.directoryExists(dir)) {
            raise(DomainError.FileSystemError.WriteFailed(dir, "could not create the export folder"))
        }
        val svgPath = "${dir.trimEnd('/')}/$SVG_NAME"
        val csvPath = "${dir.trimEnd('/')}/$CSV_NAME"
        replace(svgPath, SketchSvgWriter.write(doc).encodeToByteArray()).bind()
        replace(csvPath, SketchCsvWriter.write(doc.exportDimensions()).encodeToByteArray()).bind()
        listOf(svgPath, csvPath)
    }

    private fun replace(path: String, bytes: ByteArray): Either<DomainError, Unit> {
        val existing = fs.readFileBytes(path)
        if (existing != null && existing.contentEquals(bytes)) return Unit.right()
        return writer.write(path, bytes, original = existing).fold({ it.left() }, { Unit.right() })
    }

    companion object {
        const val SVG_NAME = "sketch.svg"
        const val CSV_NAME = "sketch-dimensions.csv"
        const val EXPORT_DIR = "assets/stelekit-sketch-export"
    }
}
