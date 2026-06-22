// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.compositionLocalOf
import dev.stapler.stelekit.platform.FileSystem

/**
 * Provides the absolute path to the currently-open graph's root directory.
 * Used by [SteleKitAssetFetcher] to resolve relative `../assets/` image paths.
 * Provide this at the page-screen level via [androidx.compose.runtime.CompositionLocalProvider].
 */
val LocalGraphRootPath = compositionLocalOf<String?> { null }

/**
 * Provides the active [FileSystem] so that [SteleKitAssetMapper] can resolve SAF-backed
 * asset paths to content:// URIs on Android. Null when no graph is loaded.
 */
val LocalFileSystem = compositionLocalOf<FileSystem?> { null }
