// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

/**
 * Why a scene or command operation was refused. Story 1.2.1 introduces the scene-level cases; Story 1.3.1a
 * adds the command-level ones to this same interface.
 */
sealed interface CommandError {
    /** The scene already holds `SceneLimits.maxElements` elements. */
    data class CapacityExceeded(val limit: Int) : CommandError

    data class DuplicateElement(val id: String) : CommandError

    data class UnknownElement(val id: String) : CommandError

    /** A transaction was committed with no net change to the document; nothing is recorded in history. */
    data object NoChange : CommandError

    /** A patch or history entry no longer matches the document it was applied to (`id` names the element, or the part of the document). */
    data class PatchConflict(val id: String) : CommandError
}
