// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.GraphId

/** UI state for the desktop quick-capture popup, owned by [CaptureController]. */
sealed class CapturePopupState {
    data object Hidden : CapturePopupState()

    /**
     * [targetGraphId] is where Save writes (null only when no graph is registered). The chooser
     * is offered only when [graphChoices] has more than one entry.
     */
    data class Shown(
        val text: String,
        val saveState: SaveState,
        val captureResult: CaptureResult? = null,
        val targetGraphId: GraphId? = null,
        val graphChoices: List<GraphChoice> = emptyList(),
        val chooserOpen: Boolean = false,
        /** Set with [SaveState.Queued]: the "Queued for <graph>" message. */
        val statusMessage: String? = null,
    ) : CapturePopupState() {
        val targetGraphName: String? get() = graphChoices.firstOrNull { it.id == targetGraphId }?.name
    }

    /** Esc pressed with a non-blank draft: asks before discarding. [draft] is restored by Keep editing. */
    data class ConfirmDiscard(val draft: Shown) : CapturePopupState()
}

data class GraphChoice(val id: GraphId, val name: String)

enum class SaveState { Idle, Saving, Saved, Error, Queued }
