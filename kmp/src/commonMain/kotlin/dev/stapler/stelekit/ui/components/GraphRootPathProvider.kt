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
 * Provides the platform [FileSystem] to composables that need to resolve asset URIs.
 * On Android, used by [SteleKitAssetMapper] to convert SAF paths to loadable `content://` URIs.
 */
val LocalFileSystem = compositionLocalOf<FileSystem?> { null }
