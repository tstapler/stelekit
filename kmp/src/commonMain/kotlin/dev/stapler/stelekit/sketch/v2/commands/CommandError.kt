// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import dev.stapler.stelekit.sketch.v2.constraints.ElementKind
import dev.stapler.stelekit.sketch.v2.scene.ValueError

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
    /** A constraint targeted an element of a kind `ConstraintEligibility` refuses; the user text is `SketchReasonText.NotConstrainable`. */
    data class NotConstrainable(val kind: ElementKind) : CommandError

    /** The sheet already holds `SceneLimits.maxConstraints` constraints; the diagnostics line is `SketchReasonText.constraintLimitReached`. */
    data class ConstraintCapExceeded(val limit: Int) : CommandError

    data class DuplicateConstraint(val id: String) : CommandError

    data class UnknownConstraint(val id: String) : CommandError

    /** A constraint that cannot be built: wrong operand count or mix, a missing or stray value, or an unusable element (`reason` is a fixed label, never user text). */
    data class InvalidConstraint(val id: String, val reason: String) : CommandError

    /** Another driving dimension of the same kind already measures [existingId]'s target; the UI opens that label for editing instead (Story 4.2.7). */
    data class DuplicateDimension(val existingId: String) : CommandError

    /** The solver could not satisfy the constraints with [id]'s new value; [conflictIds] are the constraints it names, possibly none. Nothing changed. */
    data class Unsolvable(val id: String, val conflictIds: List<String>) : CommandError

    /** A coordinate, size or angle a command computed is not a valid scene value (non-finite or beyond `SceneLimits.maxCoord`). */
    data class ValueRejected(val error: ValueError) : CommandError

    /** The command does not apply to this element kind (`operation` names the command, never user text). */
    data class Unsupported(val id: String, val operation: String) : CommandError

    /** A frame membership command named an element that is not a frame, or tried to put a frame in a frame. */
    data class NotAFrame(val id: String) : CommandError

    /** Grouping needs at least two elements. */
    data class GroupTooSmall(val count: Int) : CommandError

    /** A paste or duplicate had no new id for a source element. */
    data class MissingRemap(val id: String) : CommandError

    /** A new group id collides with a group that already exists on the sheet. */
    data class DuplicateGroup(val id: String) : CommandError

    data class UnknownLayer(val id: String) : CommandError

    data class DuplicateLayer(val id: String) : CommandError

    /** The layer id is blank or longer than `SceneLimits.maxLayerIdChars`. */
    data class InvalidLayerId(val id: String) : CommandError

    /** The sheet already holds `SceneLimits.maxLayers` layers. */
    data class TooManyLayers(val limit: Int) : CommandError

    /** A layer name is longer than `SceneLimits.maxNameChars`. */
    data class NameTooLong(val limit: Int) : CommandError

    /** Another layer already has this name; the panel shows "A layer with this name exists" and keeps editing. */
    data class DuplicateLayerName(val name: String) : CommandError

    /** Another element already has this name; the list shows "An element with this name exists" and keeps editing. */
    data class NameTaken(val name: String) : CommandError

    /** A label is longer than `SceneLimits.maxTextChars`; it is refused, never truncated (UX-23). */
    data class TextTooLong(val limit: Int) : CommandError

    /** The bundled font has not loaded, so nothing can be measured and nothing is persisted. */
    data object FontNotLoaded : CommandError

    /** The default layer cannot be deleted (ADR-006: it exists on every sheet). */
    data object DefaultLayerProtected : CommandError
}
