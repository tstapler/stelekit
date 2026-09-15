// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.sections

/**
 * Rendering decision for a `[[PageName]]` wikilink in view mode (FR-14).
 *
 * When a SectionFilter is active and the target page is absent from the local DB
 * (i.e. it lives in a REMOVED section that was never downloaded), the link must
 * render as plain text + a subtle "?" badge.
 *
 * FR-14 privacy: [UnavailableLink] carries ONLY [displayName].  It must never
 * contain a section id, section display name, file path, or any other metadata
 * that would reveal which section the missing page belongs to.
 */
sealed class WikiLinkRenderDecision {

    /**
     * The target page exists in the local DB (ACTIVE or HIDDEN section).
     * Render as a normal navigable link.
     */
    data class NavigableLink(
        val displayName: String,
        val target: String,
    ) : WikiLinkRenderDecision()

    /**
     * The target page is absent from the local DB.
     * Render as plain text + "?" badge.  The only user-visible tooltip is
     * [UNAVAILABLE_TOOLTIP] — no section name, no path.
     */
    data class UnavailableLink(
        val displayName: String,
    ) : WikiLinkRenderDecision()

    companion object {
        /** Fixed tooltip shown on tap/hover for [UnavailableLink]. */
        const val UNAVAILABLE_TOOLTIP = "Content not available on this device"

        /**
         * Decides how to render a wikilink given the local page availability.
         *
         * Returns [UnavailableLink] only when [hasSectionFilter] is `true` AND
         * [target] is absent from [localPageNames].  When there is no section
         * filter (plain local graph), all links are [NavigableLink] — this keeps
         * the classic single-section behaviour unchanged.
         *
         * @param target       The raw `[[target]]` string (before alias resolution).
         * @param alias        Optional display alias from `[[target|alias]]` syntax.
         * @param localPageNames Set of page names that exist in the local DB.
         * @param hasSectionFilter `true` when a [SectionFilter] is active for this graph.
         */
        fun resolve(
            target: String,
            alias: String?,
            localPageNames: Set<String>,
            hasSectionFilter: Boolean,
        ): WikiLinkRenderDecision {
            val displayName = alias ?: target
            return if (hasSectionFilter && target !in localPageNames) {
                UnavailableLink(displayName = displayName)
            } else {
                NavigableLink(displayName = displayName, target = target)
            }
        }
    }
}
