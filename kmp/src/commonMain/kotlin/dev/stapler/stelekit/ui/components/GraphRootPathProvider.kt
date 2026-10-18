// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.compositionLocalOf
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem

/**
 * Provides the currently-open graph's notes folder (the one holding `pages/`, `journals/` and `assets/`).
 * Used by [SteleKitAssetFetcher] to resolve relative `../assets/` image paths.
 * Provide this at the page-screen level via [androidx.compose.runtime.CompositionLocalProvider].
 */
val LocalNotesPath = compositionLocalOf<EffectiveNotesPath?> { null }

/**
 * Provides the platform [FileSystem] to composables that need to resolve asset URIs.
 * On Android, used by [SteleKitAssetMapper] to convert SAF paths to loadable `content://` URIs.
 */
val LocalFileSystem = compositionLocalOf<FileSystem?> { null }
