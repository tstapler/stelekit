// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.graphics.Color
import dev.stapler.stelekit.ui.theme.stelekitStaticSchemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 3.6.6a (UX-46, UX-87 to UX-89): text at 4.5:1 and every non-text token at 3:1, in each token set; a failure names its token. */
class SketchTokensAuditTest {
    private val sets = stelekitStaticSchemes() + mapOf(
        "high contrast light" to SketchTokens.highContrast(dark = false),
        "high contrast dark" to SketchTokens.highContrast(dark = true),
    )

    private fun failingKeys() = sets.flatMap { (name, scheme) -> SketchTokens.failing(scheme).map { "$name: ${it.name}" } }.toSet()

    @Test
    fun ux46_tokens_should_Meet4_5To1Text_And3To1NonText_InEveryTokenSet() {
        val lines = sets.flatMap { (name, scheme) -> SketchTokens.failures(name, scheme) }
        assertTrue(lines.isEmpty(), lines.joinToString("\n"))
    }

    @Test
    fun highContrastSets_should_HaveNoFindingAtAll() {
        val findings = failingKeys().filter { it.startsWith("high contrast") }
        assertTrue(findings.isEmpty(), findings.joinToString("\n"))
    }

    @Test
    fun theAuditSets_should_CoverLightDarkStoneAndBothHighContrastSets() {
        assertEquals(setOf("light", "dark", "stone", "high contrast light", "high contrast dark"), sets.keys)
    }

    @Test
    fun auditedPairs_should_IncludeTextAndNonTextTokens_EachNamedOnce() {
        val pairs = SketchTokens.pairs(sets.getValue("light"))
        assertTrue(pairs.any { it.kind == TokenKind.Text } && pairs.any { it.kind == TokenKind.NonText })
        assertEquals(pairs.size, pairs.map { it.name }.toSet().size)
    }

    @Test
    fun aPairBelowItsBar_should_FailAndReportItsRatio() {
        val pale = TokenPair("pale hint", TokenKind.Text, Color(0xFFCCCCCC), Color.White)
        assertFalse(pale.passes)
        assertTrue(pale.ratio < TokenKind.Text.bar)
        assertTrue(TokenPair("black on white", TokenKind.Text, Color.Black, Color.White).passes)
    }
}
