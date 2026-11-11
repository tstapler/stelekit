// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.model.ProjectUuid
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectManagementTest {
    @Test
    fun everySlugIsOpaqueAndWellFormed() = runTest {
        checkAll(Arb.int()) { seed -> assertTrue(ProjectSlugs.isValid(ProjectSlugs.fromBits(seed))) }
    }

    @Test
    fun slugBitsMapToHex() {
        assertEquals("proj-000000", ProjectSlugs.fromBits(0))
        assertEquals("proj-abcdef", ProjectSlugs.fromBits(0xABCDEF))
        assertEquals("proj-123456", ProjectSlugs.fromBits(0x7F123456))
    }

    @Test
    fun slugValidationRejectsNames() {
        assertFalse(ProjectSlugs.isValid("Closet"))
        assertFalse(ProjectSlugs.isValid("proj-closet-1"))
        assertFalse(ProjectSlugs.isValid("proj-ABCDEF"))
        assertFalse(ProjectSlugs.isValid("proj-abcde"))
    }

    @Test
    fun uniqueSlugSkipsTakenValues() {
        val first = ProjectSlugs.generate(Random(7))
        val next = ProjectSlugs.generateUnique({ it == first }, Random(7))
        assertTrue(ProjectSlugs.isValid(next))
        assertFalse(next == first)
    }

    @Test
    fun uniqueSlugGivesUpWhenEverythingIsTaken() {
        assertFailsWith<IllegalStateException> { ProjectSlugs.generateUnique({ true }, Random(1)) }
    }

    @Test
    fun nameIsTrimmedCollapsedAndBounded() {
        assertEquals("Hall closet", ProjectNaming.normalizeName("  Hall   closet \n"))
        assertNull(ProjectNaming.normalizeName("   "))
        assertNull(ProjectNaming.normalizeName("x".repeat(ProjectNaming.MAX_NAME + 1)))
        assertEquals(ProjectNaming.MAX_NAME, ProjectNaming.normalizeName("x".repeat(ProjectNaming.MAX_NAME))?.length)
    }

    @Test
    fun digestTitleMustBeAPlainPageName() {
        assertEquals("Closet Digest", ProjectNaming.normalizeDigestTitle(" Closet Digest "))
        listOf("", "  ", "a[[b]]", "a#b", "a|b", "a\\b", "a\nb", "x".repeat(ProjectNaming.MAX_TITLE + 1))
            .forEach { assertNull(ProjectNaming.normalizeDigestTitle(it), "should reject <$it>") }
    }

    @Test
    fun factoryBuildsAnEmptySettingsProjectAndRejectsNonOpaqueSlugs() {
        val p = ProjectFactory.create("Closet", "proj-0a1b2c", ProjectUuid("u1"), 42L)
        assertEquals("proj-0a1b2c", p.slug)
        assertEquals(42L, p.createdAt)
        assertTrue(p.settings.isEmpty(), "settings_json starts empty: no path, URI or title")
        assertFailsWith<IllegalArgumentException> { ProjectFactory.create("Closet", "closet", ProjectUuid("u1"), 0L) }
    }

    @Test
    fun analyseToggleDecisionTable() {
        val text = AnalyseToggleRules.emptyPreview("proj-0a1b2c")
        assertEquals(AnalyseToggleDecision.Disable, AnalyseToggleRules.decide(false, true, text, true))
        assertEquals(AnalyseToggleDecision.NotOffered, AnalyseToggleRules.decide(true, false, text, true))
        assertEquals(AnalyseToggleDecision.Enable, AnalyseToggleRules.decide(true, true, text, true))
        assertEquals(AnalyseToggleDecision.ShowPreview(text), AnalyseToggleRules.decide(true, true, text, false))
        assertIs<AnalyseToggleDecision.ShowPreview>(AnalyseToggleRules.decide(true, true, text, false))
    }

    @Test
    fun emptyPreviewCarriesOnlyTheOpaqueSlug() {
        val text = AnalyseToggleRules.emptyPreview("proj-0a1b2c")
        assertTrue("proj-0a1b2c" in text)
        assertFalse(text.contains("/"))
    }

    @Test
    fun anyProjectReflectsTheFirstPage() {
        assertFalse(hasAnyProject(emptyList()))
        assertTrue(hasAnyProject(listOf(ProjectFactory.create("A", "proj-000001", ProjectUuid("u"), 0L))))
    }

    @Test
    fun privateTermsDedupeIgnoringCaseAndPersistPerGraph() {
        val s = ProjectBridgeSettings(BridgeMapSettings())
        assertTrue(s.addOutboundPrivateTerm("g1", "  Oak   Street "))
        assertFalse(s.addOutboundPrivateTerm("g1", "oak street"))
        assertFalse(s.addOutboundPrivateTerm("g1", "   "))
        assertTrue(s.addOutboundPrivateTerm("g1", "Maple"))
        assertEquals(listOf("Oak Street", "Maple"), s.outboundPrivateTerms("g1"))
        assertEquals(emptyList(), s.outboundPrivateTerms("g2"))
    }
}
