// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

/** The retained entries, oldest first, at most one per row. */
data class SketchDiagnostics(val entries: List<BannerCandidate> = emptyList()) {
    /** True when an entry is a blocking error, which is the only kind announced assertively. */
    val hasBlocking: Boolean get() = entries.any { it.announcesAssertively }
}
