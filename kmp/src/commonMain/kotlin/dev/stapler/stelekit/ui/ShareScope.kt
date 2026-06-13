// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

/**
 * Defines the scope of content to include in a share/export operation.
 */
enum class ShareScope {
    /** Export only the currently-open page (default). */
    CurrentPage,

    /** Export the current page plus all pages it links to via [[WikiLinks]] (one level, cycle-safe). */
    PageAndLinks,

    /** Export only the selected blocks and their subtrees. */
    SelectedBlocks,

    /** Export all journal pages within a user-specified date range. */
    JournalRange,
}
