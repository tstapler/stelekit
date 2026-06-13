// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.ui.Modifier

actual fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier = this
