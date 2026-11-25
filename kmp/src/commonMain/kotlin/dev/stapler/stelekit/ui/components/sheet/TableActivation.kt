// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.runtime.staticCompositionLocalOf

/** Which inline table on the page is active: activating one resets the previous one, so only one strip shows. */
internal class TableActivation {
    private var active: InlineTableState? = null

    fun activate(state: InlineTableState) {
        val previous = active
        active = state
        if (previous != null && previous !== state) previous.commitAndClear()
    }

    fun release(state: InlineTableState) {
        if (active === state) active = null
    }
}

@Suppress("CompositionLocalAllowlist") // one holder per page; every inline table on it reads the same one
internal val LocalTableActivation = staticCompositionLocalOf { TableActivation() }
