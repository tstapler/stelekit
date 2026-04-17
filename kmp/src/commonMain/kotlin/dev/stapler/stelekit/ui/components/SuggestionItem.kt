package dev.stapler.stelekit.ui.components

/**
 * Represents a single page-name suggestion within a specific block on screen.
 * Used by [SuggestionNavigatorPanel] to let users browse and resolve all
 * suggestions on the current screen in one workflow.
 */
data class SuggestionItem(
    val blockUuid: String,
    val canonicalName: String,
    /** Start offset (inclusive) of the match within [blockUuid]'s content string. */
    val contentStart: Int,
    /** End offset (exclusive) of the match within [blockUuid]'s content string. */
    val contentEnd: Int,
)
