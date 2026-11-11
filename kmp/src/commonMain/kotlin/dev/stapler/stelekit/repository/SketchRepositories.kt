// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

/** The three sketch repositories of one graph; they share one [SketchRevisionTokens]. */
data class SketchRepositories(
    val projects: SketchProjectRepository,
    val sheets: SketchSheetRepository,
    val dimensions: SketchDimensionRepository,
)
