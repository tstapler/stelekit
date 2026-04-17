// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.compositionLocalOf

/**
 * Provides the absolute path to the currently-open graph's root directory.
 * Used by [SteleKitAssetFetcher] to resolve relative `../assets/` image paths.
 * Provide this at the page-screen level via [androidx.compose.runtime.CompositionLocalProvider].
 */
val LocalGraphRootPath = compositionLocalOf<String?> { null }
