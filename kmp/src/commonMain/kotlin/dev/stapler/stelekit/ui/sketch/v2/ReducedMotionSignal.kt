// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable

/**
 * True when the platform asks for no animation (UX-47): an Android animator duration scale of 0, the web's
 * `prefers-reduced-motion: reduce`. Desktop and iOS report false until they read their own setting. Read it through
 * `rememberReducedMotion`, never directly, so a test can override it.
 */
@Composable
expect fun platformReducedMotion(): Boolean
