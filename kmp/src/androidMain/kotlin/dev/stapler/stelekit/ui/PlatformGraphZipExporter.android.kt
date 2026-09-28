// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.stapler.stelekit.export.AndroidGraphZipExporter
import dev.stapler.stelekit.export.GraphZipExporter

@Composable
actual fun rememberGraphZipExporter(): GraphZipExporter? = remember { AndroidGraphZipExporter() }
