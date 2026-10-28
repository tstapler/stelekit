package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flake-class audit (pitfalls E4): no `Thread.sleep`, no `GlobalScope`, no `CoroutineScope(Dispatchers.IO...)`
 * without an `@AfterTest` cancel, and no real-clock `delay(` in a file that uses no virtual time
 * (`runTest`/`TestScope`/test dispatcher). A file-level text heuristic: it cannot tell a `delay` inside
 * `runTest` from one beside it in the same file. Existing violations are in `test-hygiene-baseline.txt`
 * (`path<TAB>rule<TAB>count`) and may only shrink.
 */
class TestHygieneAuditTest {
    @Test
    fun testHygieneAudit_should_Fail_When_NewTestUsesThreadSleepGlobalScopeOrRealDelay() {
        val sources = mapOf(
            "jvmTest/A.kt" to "class A { fun t() { Thread.sleep(10) } }",
            "jvmTest/B.kt" to "class B { fun t() { GlobalScope.launch { } } }",
            "jvmTest/C.kt" to "class C { val s = CoroutineScope(SupervisorJob() + Dispatchers.IO) }",
            "jvmTest/D.kt" to "class D { fun t() = runBlocking { delay(100) } }",
        )
        val counts = TestAuditRules.hygieneCounts(sources)
        assertEquals(
            mapOf(
                "jvmTest/A.kt\tThread.sleep" to 1,
                "jvmTest/B.kt\tGlobalScope" to 1,
                "jvmTest/C.kt\tio-scope-without-aftertest-cancel" to 1,
                "jvmTest/D.kt\treal-clock-delay" to 1,
            ),
            counts,
        )
        val diff = TestAuditRules.diff(counts, emptyMap())
        assertEquals(4, diff.newViolations.size)
    }

    @Test
    fun testHygieneAudit_should_Pass_When_ScopeIsCancelledInAfterTestAndDelayIsVirtual() {
        val sources = mapOf(
            "jvmTest/E.kt" to "class E { val s = CoroutineScope(Dispatchers.IO)\n @AfterTest fun tearDown() { s.cancel() } }",
            "jvmTest/F.kt" to "class F { fun t() = runTest { delay(100) } }",
            "jvmTest/G.kt" to "class G { // Thread.sleep(1) in a comment\n val s = \"GlobalScope\" }",
        )
        assertEquals(emptyMap(), TestAuditRules.hygieneCounts(sources))
    }

    @Test
    fun testHygieneAudit_should_FlagIoScopeWhenAfterTestDoesNotCancel() {
        val sources = mapOf("jvmTest/H.kt" to "class H { val s = CoroutineScope(Dispatchers.IO)\n @AfterTest fun tearDown() { } }")
        assertEquals(1, TestAuditRules.hygieneCounts(sources).size)
    }

    @Test
    fun testHygieneAudit_should_RatchetBaseline_When_ViolationsIncreaseOrBaselineGoesStale() {
        val baseline = mapOf("jvmTest/A.kt\tThread.sleep" to 2)
        val worse = TestAuditRules.diff(mapOf("jvmTest/A.kt\tThread.sleep" to 3), baseline)
        assertEquals(1, worse.newViolations.size)
        val better = TestAuditRules.diff(mapOf("jvmTest/A.kt\tThread.sleep" to 1), baseline)
        assertEquals(0, better.newViolations.size)
        assertEquals(1, better.tightenable.size)
        assertEquals(baseline, TestAuditRules.parseBaseline(TestAuditRules.renderBaseline(baseline)))
    }

    @Test
    fun testHygieneAudit_should_OnlyAllowBaselineViolations_When_RunAtHead() {
        val audited = TestAuditRules.audited(WiringInputs.testSources)
        assertTrue(audited.size > 100, "audit loaded only ${audited.size} test files; the loader is likely broken (mode=${WiringInputs.mode})")
        val actual = TestAuditRules.hygieneCounts(audited)
        val baseline = TestAuditRules.parseBaseline(WiringInputs.dataFile("test-hygiene-baseline.txt"))
        val diff = TestAuditRules.diff(actual, baseline)
        assertTrue(
            diff.newViolations.isEmpty(),
            "new test-hygiene violations (use runTest virtual time, an owned scope cancelled in @AfterTest, no Thread.sleep/GlobalScope):\n  " +
                diff.newViolations.joinToString("\n  ") + "\nfull current counts, for regenerating the baseline:\n" + TestAuditRules.renderBaseline(actual),
        )
        assertTrue(
            diff.tightenable.isEmpty(),
            "test-hygiene-baseline.txt is stale; lower or delete these entries (ratchet only shrinks):\n  " + diff.tightenable.joinToString("\n  "),
        )
    }
}
