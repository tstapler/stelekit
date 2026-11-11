// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.export

import arrow.core.Either
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.AtomicFileWriter
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.Zone
import dev.stapler.stelekit.sketch.ZoneSet
import dev.stapler.stelekit.ui.sketch.exportStatusText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SketchExporterTest {
    private class Fs(private val tempRename: Boolean = true) : FakeRelocationFileSystem() {
        override val supportsTempRenameWrites get() = tempRename
    }

    private val doc = SketchDocument(zones = ZoneSet(listOf(Zone("zone.a", "12\""))))
    private fun exporter(fs: Fs) = SketchExporter(fs, AtomicFileWriter(fs, { it }, ".tmp") { 1L }, "/g/assets/stelekit-sketch-export")

    @Test
    fun export_should_WriteSvgAndCsvThroughTheAtomicWriter_AndLeaveNoTempFiles() {
        val fs = Fs()
        val paths = exporter(fs).export(doc).getOrNull()!!
        assertEquals(listOf("/g/assets/stelekit-sketch-export/sketch.svg", "/g/assets/stelekit-sketch-export/sketch-dimensions.csv"), paths)
        assertTrue(fs.readFile(paths[0])!!.contains(SketchSvgWriter.MARKER))
        assertTrue(fs.readFile(paths[1])!!.startsWith(SketchCsvWriter.HEADER))
        assertEquals(emptyList(), fs.allFilePaths().filter { it.endsWith(".tmp") })
    }

    @Test
    fun export_should_BeIdempotent() {
        val fs = Fs()
        val first = exporter(fs).export(doc)
        val second = exporter(fs).export(doc)
        assertEquals(first, second)
    }

    @Test
    fun export_should_DegradeWithoutWriting_WhenTempRenameIsUnsupported() {
        val fs = Fs(tempRename = false)
        val result = exporter(fs).export(doc)
        assertIs<DomainError.FileSystemError.AtomicWriteUnsupported>(result.leftOrNull())
        assertEquals(emptyList(), fs.allFilePaths())
    }

    @Test
    fun exportStatusText_should_ReportCountOrReason() {
        assertEquals("Exported 2 files", exportStatusText(Either.Right(listOf("a", "b"))))
        assertEquals(
            "Export failed: Safe file writes are not supported here",
            exportStatusText(Either.Left(DomainError.FileSystemError.AtomicWriteUnsupported("x"))),
        )
    }
}
