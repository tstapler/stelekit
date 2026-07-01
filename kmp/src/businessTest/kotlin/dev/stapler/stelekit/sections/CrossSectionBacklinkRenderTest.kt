// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.sections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 6.9 — verifies the FR-14 cross-section backlink rendering decision.
 *
 * These tests operate on [WikiLinkRenderDecision.resolve], which is pure Kotlin
 * and does not require Compose.  They cover:
 *
 *  1. A `[[PersonalNote]]` wikilink whose target UUID is absent from the local DB
 *     resolves to [WikiLinkRenderDecision.UnavailableLink] (renders as plain text + "?" badge).
 *  2. The [WikiLinkRenderDecision.UnavailableLink] carries only the display name — no section
 *     name, no file path, no metadata — satisfying the FR-14 privacy requirement.
 *  3. A `[[WorkNote]]` whose target IS in the DB (Hidden section — has a DB row) resolves to
 *     [WikiLinkRenderDecision.NavigableLink] (normal clickable link).
 *  4. When no section filter is active, all wikilinks resolve to [NavigableLink] regardless of
 *     whether the target is in [localPageNames].
 */
class CrossSectionBacklinkRenderTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Simulated set of page names present in the local DB.
     * PersonalNote is absent (belongs to a REMOVED section).
     * WorkNote is present (belongs to a HIDDEN section — downloaded but not shown).
     */
    private val localPageNames = setOf("WorkNote", "PublicNote")

    // ──────────────────────────────────────────────────────────────────────────
    // Story 6.9 — Test 1: absent target → UnavailableLink
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `PersonalNote absent from local DB resolves to UnavailableLink when section filter active`() {
        val decision = WikiLinkRenderDecision.resolve(
            target = "PersonalNote",
            alias = null,
            localPageNames = localPageNames,
            hasSectionFilter = true,
        )

        assertIs<WikiLinkRenderDecision.UnavailableLink>(decision)
        assertEquals(
            "PersonalNote",
            (decision as WikiLinkRenderDecision.UnavailableLink).displayName,
            "displayName must be the raw page name",
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Story 6.9 — Test 2: UnavailableLink carries no section/path metadata
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `UnavailableLink exposes only displayName — no section name or path`() {
        val decision = WikiLinkRenderDecision.resolve(
            target = "PersonalNote",
            alias = null,
            localPageNames = localPageNames,
            hasSectionFilter = true,
        ) as WikiLinkRenderDecision.UnavailableLink

        // FR-14: only the display name may be present — assert no section/path leakage.
        assertEquals("PersonalNote", decision.displayName)

        // The only allowed tooltip is the fixed UNAVAILABLE_TOOLTIP constant.
        assertEquals(
            "Content not available on this device",
            WikiLinkRenderDecision.UNAVAILABLE_TOOLTIP,
        )

        // Validate the displayName itself contains no path separators or section identifiers.
        assertFalse(decision.displayName.contains("/"), "displayName must not contain path separators")
        assertFalse(decision.displayName.contains("@"), "displayName must not contain section identifiers")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Story 6.9 — Test 3: present target (Hidden section) → NavigableLink
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `WorkNote present in local DB resolves to NavigableLink even with section filter active`() {
        // WorkNote exists in the DB (its section is HIDDEN — downloaded but invisible in sidebar).
        val decision = WikiLinkRenderDecision.resolve(
            target = "WorkNote",
            alias = null,
            localPageNames = localPageNames,   // "WorkNote" is in the set
            hasSectionFilter = true,
        )

        assertIs<WikiLinkRenderDecision.NavigableLink>(decision)
        val navigable = decision as WikiLinkRenderDecision.NavigableLink
        assertEquals("WorkNote", navigable.displayName)
        assertEquals("WorkNote", navigable.target)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Additional — no section filter → always NavigableLink (backward compat)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `absent target with no section filter resolves to NavigableLink`() {
        val decision = WikiLinkRenderDecision.resolve(
            target = "PersonalNote",
            alias = null,
            localPageNames = emptySet(),
            hasSectionFilter = false,   // ← classic single-section graph
        )

        assertIs<WikiLinkRenderDecision.NavigableLink>(decision)
    }

    @Test
    fun `alias syntax uses alias as displayName for UnavailableLink`() {
        val decision = WikiLinkRenderDecision.resolve(
            target = "PersonalNote",
            alias = "my notes",
            localPageNames = localPageNames,
            hasSectionFilter = true,
        )

        assertIs<WikiLinkRenderDecision.UnavailableLink>(decision)
        assertEquals(
            "my notes",
            (decision as WikiLinkRenderDecision.UnavailableLink).displayName,
            "alias overrides the target as displayName",
        )
    }

    @Test
    fun `alias syntax uses alias as displayName for NavigableLink`() {
        val decision = WikiLinkRenderDecision.resolve(
            target = "WorkNote",
            alias = "work",
            localPageNames = localPageNames,
            hasSectionFilter = true,
        )

        assertIs<WikiLinkRenderDecision.NavigableLink>(decision)
        val navigable = decision as WikiLinkRenderDecision.NavigableLink
        assertEquals("work", navigable.displayName, "alias should be the display name")
        assertEquals("WorkNote", navigable.target, "target must remain the raw page name")
    }
}
