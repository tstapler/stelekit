// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

/**
 * Which of the wizard's two repository setup paths is active — Step 1's radio choice, threaded
 * through Step 2's field set and the test/save logic. Replaces a `useExistingClone: Boolean` that
 * was branched on throughout the screen (Fowler's Remove Flag Argument).
 */
enum class CloneMode {
    /** Point at a git repository that already exists locally on disk. */
    UseExistingClone,

    /** Clone a remote repository fresh (Story 2.2.2's clone-and-add flow). */
    CloneNewRepository,
}
