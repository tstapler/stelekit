// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

/** UI state for the desktop quick-capture popup, owned by [CaptureController]. */
sealed class CapturePopupState {
    data object Hidden : CapturePopupState()

    data class Shown(
        val text: String,
        val saveState: SaveState,
        val captureResult: CaptureResult? = null,
    ) : CapturePopupState()
}

enum class SaveState { Idle, Saving, Saved, Error }
