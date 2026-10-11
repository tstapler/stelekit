// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.export.GraphZipExporter

/**
 * Returns a platform-specific [GraphZipExporter], or null where this Epic doesn't implement one
 * yet. Mirrors [rememberShareProvider]'s expect/actual shape.
 * - Android: real [dev.stapler.stelekit.export.AndroidGraphZipExporter] (Story 2.2.1, Task 2.2.1e).
 * - JVM/iOS/wasmJs: null — Desktop/iOS have unrestricted filesystem access already (no `AppOwned`
 *   option is offered there, see [dev.stapler.stelekit.platform.FileSystem.supportsAppOwnedStorage]),
 *   and Web's own zip export is Epic 2.3's Task 2.3.3c, not this one.
 */
@Composable
expect fun rememberGraphZipExporter(): GraphZipExporter?
