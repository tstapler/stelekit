// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

// Bazel stand-in for kotlin/.../ui/sketch/v2/SketchFontResources.kt: Bazel does not generate the
// Compose Multiplatform resources class, so bundled fonts are unavailable and the state is Failed
// (sketch text layout then refuses to persist measured sizes, per FontStatus).
@Composable
fun rememberSketchFontState(): State<SketchFontState> = remember { mutableStateOf<SketchFontState>(SketchFontState.Failed) }
