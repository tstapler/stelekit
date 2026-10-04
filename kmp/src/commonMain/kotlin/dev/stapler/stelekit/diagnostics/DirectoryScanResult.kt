// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.diagnostics

import kotlinx.serialization.Serializable

/** Result of a wiki subdirectory candidate scan. Represents a single nested directory that contains Logseq content. */
@Serializable
data class DirectoryScanResult(
    val path: String,                    // Full path to the candidate directory
    val pages: Boolean,                  // True if directory contains pages/ subdirectory
    val journals: Boolean,                // True if directory contains journals/ subdirectory
    val name: String,                    // Human-readable name (directory basename)
) {
    /** Computed property for backward compatibility - matches the usage in GraphLoader.kt */
    val hasPages: Boolean get() = pages
    val hasJournals: Boolean get() = journals
}