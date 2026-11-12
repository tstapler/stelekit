// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.platform.AtomicFileWriter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.sketch.export.SketchExporter

/** The exporter for the open graph, or null when there is no file system or graph path yet. */
fun sketchExporterFor(fileSystem: FileSystem?, graphPath: String): SketchExporter? =
    if (fileSystem == null || graphPath.isBlank()) null
    else SketchExporter(fileSystem, AtomicFileWriter(fileSystem, { it }, ".tmp"), graphPath)
