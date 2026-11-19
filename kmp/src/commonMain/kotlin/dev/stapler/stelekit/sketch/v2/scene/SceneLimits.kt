// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import kotlin.math.abs

/**
 * The one set of numeric caps of the sketch engine (plan Story 1.2.4, ADR-002 addendum). Every boundary
 * (codec, importer, clipboard, text, image tool, library, layers, solver, history, semantics, diagnostics,
 * notices, Go to element, retention, zoom clamp, wasm preload) reads its bound from here; `SceneLimitsGrepTest`
 * fails on a cap literal anywhere else under the engine sources. Sizes are binary megabytes (1 MB = 1,048,576 bytes).
 * Defaults are owner-confirmable (D-5, D-7, D-31, D-47); coordinates are scene units (1 unit = 1/16 inch).
 */
data class SceneLimits(
    val maxElements: Int = 5_000,
    val maxConstraints: Int = 200,
    val maxCoord: Double = MAX_COORD,
    val maxPointsPerElement: Int = 2_000,
    val maxJsonDepth: Int = 64,
    val maxSheetBytes: Long = 16 * MB,
    val maxTextChars: Int = 10_000,
    val maxNameChars: Int = 80,
    val maxImportBytes: Long = 20 * MB,
    /** The largest JSON span the importer decodes in one call (Story 5.1.1, Task 5.1.1e). */
    val maxImportSpanBytes: Long = 2 * MB,
    /** Compressed image file bytes. */
    val maxImageBytes: Long = 60 * MB,
    val maxImageSidePx: Int = 8_192,
    val maxImagePixels: Int = 16_000_000,
    val maxDecodedImageBytesPerSheet: Long = 64 * MB,
    val maxParseMillis: Long = 5_000,
    val maxLibraryItems: Int = 200,
    /** The stored library summed over its items; each item is one import span of at most [maxImportSpanBytes]. */
    val maxLibraryBytes: Long = 16 * MB,
    val maxLayers: Int = 64,
    val maxLayerIdChars: Int = 64,
    val softElementThreshold: Int = 4_000,
    val softConstraintThreshold: Int = 160,
    val maxPngSidePx: Int = 16_000,
    /** Width times height of one PNG export; 100 MP is a 400 MB ARGB bitmap, checked before anything is allocated. */
    val maxPngPixels: Long = 100_000_000,
    val slowSolveIndicatorMs: Long = 100,
    val maxHistoryEntries: Int = 100,
    val maxSemanticsNodes: Int = 200,
    val maxDiagnosticsEntries: Int = 20,
    val maxVisibleNotices: Int = 3,
    val maxGoToResults: Int = 50,
    val maxConflictCopiesPerSheet: Int = 10,
    val minZoomPercent: Int = 1,
    val maxZoomPercent: Int = 3_000,
    /** Sum of file sizes under the sidecar root the wasm preload may read; over it the status is `Unavailable(PreloadTooLarge)`. */
    val maxPreloadBytes: Long = 128 * MB,
    /** Distinct top-level members of one JSON document (a sheet or an Excalidraw scene); a real one has under ten. */
    val maxJsonRootMembers: Int = 64,
) {
    /** True for a finite coordinate within `abs(v) <= maxCoord`; NaN, infinities and larger values are rejected (ADR-002). */
    fun isValidCoord(v: Double): Boolean = v.isFinite() && abs(v) <= maxCoord

    /** Every field by name, in declaration order; the structure test compares it with the canonical list. */
    fun asMap(): Map<String, Number> = linkedMapOf(
        "maxElements" to maxElements, "maxConstraints" to maxConstraints, "maxCoord" to maxCoord,
        "maxPointsPerElement" to maxPointsPerElement, "maxJsonDepth" to maxJsonDepth, "maxSheetBytes" to maxSheetBytes,
        "maxTextChars" to maxTextChars, "maxNameChars" to maxNameChars, "maxImportBytes" to maxImportBytes,
        "maxImportSpanBytes" to maxImportSpanBytes, "maxImageBytes" to maxImageBytes, "maxImageSidePx" to maxImageSidePx,
        "maxImagePixels" to maxImagePixels, "maxDecodedImageBytesPerSheet" to maxDecodedImageBytesPerSheet,
        "maxParseMillis" to maxParseMillis, "maxLibraryItems" to maxLibraryItems, "maxLibraryBytes" to maxLibraryBytes,
        "maxLayers" to maxLayers, "maxLayerIdChars" to maxLayerIdChars, "softElementThreshold" to softElementThreshold,
        "softConstraintThreshold" to softConstraintThreshold, "maxPngSidePx" to maxPngSidePx, "maxPngPixels" to maxPngPixels,
        "slowSolveIndicatorMs" to slowSolveIndicatorMs, "maxHistoryEntries" to maxHistoryEntries,
        "maxSemanticsNodes" to maxSemanticsNodes, "maxDiagnosticsEntries" to maxDiagnosticsEntries,
        "maxVisibleNotices" to maxVisibleNotices, "maxGoToResults" to maxGoToResults,
        "maxConflictCopiesPerSheet" to maxConflictCopiesPerSheet, "minZoomPercent" to minZoomPercent,
        "maxZoomPercent" to maxZoomPercent, "maxPreloadBytes" to maxPreloadBytes, "maxJsonRootMembers" to maxJsonRootMembers,
    )

    companion object {
        private const val MB = 1_048_576L

        /** 2^28 scene units (ADR-002); every lifted v1 sixteenths value is exact below it. */
        const val MAX_COORD = 268_435_456.0

        val Default = SceneLimits()
    }
}
