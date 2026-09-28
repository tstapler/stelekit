// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.stapler.stelekit.export.GraphZipExporter
import dev.stapler.stelekit.export.WasmJsGraphZipExporter

// Web's own zip export (Story 2.3.3, ADR-003's Amendment): a hand-rolled stored-only ZIP writer —
// see WasmJsGraphZipExporter's doc comment.
@Composable
actual fun rememberGraphZipExporter(): GraphZipExporter? = remember { WasmJsGraphZipExporter() }
