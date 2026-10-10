// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.export.GraphZipExporter

// iOS already has unrestricted filesystem access — no AppOwned option is offered, so this is
// never called. See PlatformGraphZipExporter.kt's expect doc.
@Composable
actual fun rememberGraphZipExporter(): GraphZipExporter? = null
