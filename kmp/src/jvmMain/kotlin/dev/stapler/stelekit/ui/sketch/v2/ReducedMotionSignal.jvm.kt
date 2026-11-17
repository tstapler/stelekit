// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable

/** The desktop has no cross-platform reduce-motion setting to read yet. */
@Composable
actual fun platformReducedMotion(): Boolean = false
