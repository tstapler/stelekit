// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.export.GraphZipExporter

// Web's own zip export (a hand-rolled stored-only writer, per ADR-003's Amendment) is Epic 2.3's
// Task 2.3.3c, not this one — null here until that lands. See PlatformGraphZipExporter.kt's
// expect doc.
@Composable
actual fun rememberGraphZipExporter(): GraphZipExporter? = null
