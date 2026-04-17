// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

/**
 * Result of applying a [List] of [BlockChange] entries via [ChangeApplier].
 *
 * @property applied number of changes that were successfully persisted
 * @property skipped number of changes that were intentionally skipped (already satisfied)
 * @property failed list of human-readable error messages for changes that failed
 */
data class ChangeSummary(
    val applied: Int,
    val skipped: Int,
    val failed: List<String>,
)
