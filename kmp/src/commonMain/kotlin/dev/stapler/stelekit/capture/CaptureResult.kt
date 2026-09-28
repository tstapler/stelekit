// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.Page

/** Outcome of a quick-capture write attempt — see [CaptureWriter]. */
sealed class CaptureResult {
    data class Saved(val page: Page) : CaptureResult()
    data class Failed(val message: String) : CaptureResult()
    data object NoActiveGraph : CaptureResult()
    data object GraphLocked : CaptureResult()
}
