package dev.stapler.stelekit.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FtsQueryBuilderTest {

    private fun build(q: String) = FtsQueryBuilder.build(q)

    // ── Empty / blank ─────────────────────────────────────────────────────

    @Test fun emptyInputReturnsEmpty() = assertEquals("", build(""))
    @Test fun blankInputReturnsEmpty() = assertEquals("", build("   "))

    // ── Single token ──────────────────────────────────────────────────────

    @Test fun singleTokenGetsPrefixWildcard() = assertEquals("taxes*", build("taxes"))
    @Test fun singleTokenCasePreserved() = assertEquals("Taxes*", build("Taxes"))
    @Test fun singleTokenStripsColon() = assertEquals("taxes*", build("taxes:"))

    // ── Multi-token → OR semantics ────────────────────────────────────────

    @Test fun twoTokensJoinedWithOr() = assertEquals("2025 OR taxes*", build("2025 taxes"))
    @Test fun threeTokensJoinedWithOr() = assertEquals("meeting OR notes OR 2025*", build("meeting notes 2025"))
    @Test fun extraWhitespaceNormalized() = assertEquals("2025 OR taxes*", build("  2025   taxes  "))

    // ── Phrase segments (balanced quotes) ────────────────────────────────

    @Test fun balancedPhrasePreserved() = assertEquals("\"meeting notes\"", build("\"meeting notes\""))
    @Test fun phraseWithFollowingToken() = assertEquals("\"meeting notes\" OR taxes*", build("\"meeting notes\" taxes"))
    @Test fun phraseWithPrecedingToken() = assertEquals("meeting* OR \"exact phrase\"", build("meeting \"exact phrase\"")) // last *token* (not phrase) gets prefix

    // ── Unbalanced quotes ────────────────────────────────────────────────

    @Test fun unbalancedOpeningQuoteTreatedAsTokens() {
        val result = build("\"unclosed taxes")
        // The unbalanced content should become tokens, last gets *
        assertTrue(result.endsWith("taxes*"), "Expected last token to have wildcard, got: $result")
    }

    @Test fun unbalancedQuoteContentNotWrapped() {
        val result = build("\"unclosed")
        // Should not contain a " in the output (stripped)
        assertTrue(!result.contains('"'), "Unbalanced quote should be stripped, got: $result")
    }

    // ── Leading operator stripping ────────────────────────────────────────

    @Test fun leadingOrStripped() = assertEquals("taxes*", build("OR taxes"))
    @Test fun leadingAndStripped() = assertEquals("taxes*", build("AND taxes"))
    @Test fun leadingNotStripped() = assertEquals("taxes*", build("NOT taxes"))
    @Test fun leadingOrAndStripped() = assertEquals("taxes*", build("OR AND taxes"))

    // ── Dangerous character stripping ─────────────────────────────────────

    @Test fun colonStripped() = assertEquals("taxesproperty*", build("taxes:property"))
    @Test fun caretStripped() = assertEquals("taxes2*", build("taxes^2"))
    @Test fun bracketStripped() = assertEquals("taxes0*", build("taxes[0]"))

    // ── Injection attempts ────────────────────────────────────────────────

    @Test fun sqlCommentStripped() {
        val result = build("taxes -- DROP TABLE")
        // Hyphens are stripped so '--' tokens become empty and are removed
        assertTrue(!result.contains("--"), "Hyphen-stripped tokens should not produce '--': $result")
        assertTrue(result.contains("taxes"), "Valid token should remain: $result")
    }

    @Test fun semicolonNotBreaking() {
        val result = build("taxes; DROP TABLE pages")
        // Semicolon is not an FTS operator; it stays but shouldn't cause issues
        assertTrue(result.contains("taxes"), "Should still contain valid token: $result")
    }
}
