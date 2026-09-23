// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

/**
 * Chromium's documented `width * height` canvas-area ceiling (16,777,216px). Browsers silently
 * return a blank/degraded canvas past this rather than throwing, so it must be checked before any
 * canvas call, not inferred from a later failure. No single cross-browser number exists; this is
 * used as the defensive floor.
 */
internal const val MAX_CANVAS_AREA_PX = 16_777_216L

/** Pure so it's testable from `commonTest` without a browser — [ImageEncoder.wasmJs.kt] is the only caller today. */
internal fun isValidBitmapSize(width: Int, height: Int): Boolean = width > 0 && height > 0

/** Pure so it's testable from `commonTest` without a browser — [ImageEncoder.wasmJs.kt] is the only caller today. */
internal fun exceedsCanvasAreaCeiling(width: Int, height: Int): Boolean =
    width.toLong() * height.toLong() > MAX_CANVAS_AREA_PX
