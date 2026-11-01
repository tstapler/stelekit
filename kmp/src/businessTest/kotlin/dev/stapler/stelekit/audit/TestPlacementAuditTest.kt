package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLAUDE.md "Testing best practices": Compose-behavior tests belong in `androidUnitTest`
 * (Robolectric, headless); `jvmTest` is for pixels and desktop-only code and must say which with a
 * `// jvmTest-reason: <pixels|desktop-only>` comment. Pre-existing offenders are in
 * `test-placement-baseline.txt` and may only shrink.
 */
class TestPlacementAuditTest {
    private val offender = "jvmTest/dev/x/FooTest.kt"

    @Test
    fun testPlacementAudit_should_FailNamingFile_When_JvmTestImportsComposeTestWithoutReason() {
        val sources = mapOf(offender to "import androidx.compose.ui.test.junit4.createComposeRule\nclass FooTest\n")
        assertEquals(listOf(offender), TestAuditRules.placementViolations(sources))
        val commentedImport = mapOf(offender to "// import androidx.compose.ui.test.junit4.createComposeRule\nclass FooTest\n")
        assertEquals(emptyList(), TestAuditRules.placementViolations(commentedImport))
    }

    @Test
    fun testPlacementAudit_should_Pass_When_JvmTestCarriesPixelsOrDesktopOnlyReason() {
        for (reason in listOf("pixels", "desktop-only")) {
            val sources = mapOf(offender to "// jvmTest-reason: $reason\nimport androidx.compose.ui.test.junit4.createComposeRule\n")
            assertEquals(emptyList(), TestAuditRules.placementViolations(sources), reason)
        }
        val wrongReason = mapOf(offender to "// jvmTest-reason: because\nimport androidx.compose.ui.test.junit4.createComposeRule\n")
        assertEquals(listOf(offender), TestAuditRules.placementViolations(wrongReason))
        val androidUnit = mapOf("androidUnitTest/FooTest.kt" to "import androidx.compose.ui.test.junit4.createComposeRule\n")
        assertEquals(emptyList(), TestAuditRules.placementViolations(androidUnit), "only jvmTest is audited")
    }

    @Test
    fun testPlacementAudit_should_OnlyAllowBaselineOffenders_And_RequireBaselineToShrink_When_RunAtHead() {
        val baseline = WiringInputs.dataFile("test-placement-baseline.txt").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val actual = TestAuditRules.placementViolations(TestAuditRules.audited(WiringInputs.testSources)).toSet()
        assertTrue(actual.isNotEmpty() || baseline.isEmpty(), "audit found no jvmTest files at all; the loader is likely broken")
        val new = actual - baseline
        assertTrue(
            new.isEmpty(),
            "jvmTest files import androidx.compose.ui.test without '// jvmTest-reason: <pixels|desktop-only>' " +
                "(Compose-behavior tests belong in androidUnitTest/Robolectric):\n  ${new.sorted().joinToString("\n  ")}",
        )
        val fixed = baseline - actual
        assertTrue(fixed.isEmpty(), "remove these fixed files from test-placement-baseline.txt (ratchet only shrinks):\n  ${fixed.sorted().joinToString("\n  ")}")
    }
}
