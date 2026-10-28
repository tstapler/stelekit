package dev.stapler.stelekit.wiring

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renewal cap, renewal-reason, owner-gated and stale-entry rules of `wiring-allowlist.txt` (Task 1.1b). */
class WiringAllowlistRenewalTest {
    private val fooSource = mapOf("a/FooPlugin.kt" to "class FooPlugin : AssetPipelinePlugin\n")
    private val emptyRoot = mapOf("ui/GraphContentXSetup.kt" to "fun a() {}")
    private val afterExpiry = LocalDate.parse("2027-03-01")

    private fun eval(allowlist: String, today: LocalDate = afterExpiry, sources: Map<String, String> = fooSource, roots: Map<String, String> = emptyRoot) =
        WiringAudit.evaluate(sources, roots, allowlist, today)

    @Test
    fun allowlist_should_FailThirdExpiryWithRenewalLimitMessage_And_RejectLoweredRenewals_When_Evaluated() {
        val capped = eval("FooPlugin  awaiting-unit U8  2026-10-27  renewals=2")
        assertTrue("renewal limit reached: FooPlugin awaiting U8" in capped, capped.toString())
        val renewable = eval("FooPlugin  awaiting-unit U8  2026-10-27  renewals=1")
        assertTrue(renewable.any { it.startsWith("expired allowlist entry: FooPlugin") }, renewable.toString())
        assertTrue(renewable.none { "renewal limit" in it })
        val before = WiringAudit.parseAllowlist("FooPlugin  awaiting-unit U8  2026-10-27  renewals=2").entries
        val lowered = WiringAudit.parseAllowlist("FooPlugin  awaiting-unit U8  2026-10-27  renewals=1").entries
        assertEquals(listOf("renewals lowered for FooPlugin: 2 -> 1"), WiringAudit.checkRenewalsNotLowered(before, lowered))
        assertEquals(emptyList(), WiringAudit.checkRenewalsNotLowered(lowered, before))
    }

    @Test
    fun allowlist_should_RejectRenewalWithoutAnOpenItem_And_MoreThanTwoRenewals() {
        val noItem = eval("FooPlugin  temporary reason  2026-10-27  renewals=1", today = LocalDate.parse("2026-11-01"))
        assertTrue(noItem.any { "renewed without naming an open item" in it }, noItem.toString())
        val named = eval("FooPlugin  awaiting OD-3  2026-10-27  renewals=1", today = LocalDate.parse("2026-11-01"))
        assertEquals(emptyList(), named)
        val three = eval("FooPlugin  awaiting-unit U8  2026-10-27  renewals=3", today = LocalDate.parse("2026-11-01"))
        assertTrue(three.any { "exceeds the cap" in it }, three.toString())
    }

    @Test
    fun allowlist_should_ConvertOwnerGatedEntryToNeverExpiringBlockedOnOwner_Only_WhenItNamesAnOwnerItem() {
        val ok = eval("FooPlugin  blocked-on-owner OD-5  2026-10-27  renewals=0", today = LocalDate.parse("2030-01-01"))
        assertEquals(emptyList(), ok)
        val noItem = eval("FooPlugin  blocked-on-owner someday  2026-10-27", today = LocalDate.parse("2030-01-01"))
        assertTrue(noItem.any { "must name OD-1..OD-6" in it }, noItem.toString())
        val renewed = eval("FooPlugin  blocked-on-owner pass 12.5  2026-10-27  renewals=1", today = LocalDate.parse("2030-01-01"))
        assertTrue(renewed.any { "takes no renewals" in it }, renewed.toString())
    }

    @Test
    fun allowlist_should_FailStaleEntry_When_ListedClassIsConstructedInARootOrNoLongerExists() {
        val line = "FooPlugin  awaiting-unit U8  2026-10-27  renewals=0"
        val now = LocalDate.parse("2026-11-01")
        val constructed = eval(line, now, roots = mapOf("ui/GraphContentXSetup.kt" to "fun a() { FooPlugin() }"))
        assertTrue(constructed.any { "stale allowlist entry: FooPlugin is now constructed" in it }, constructed.toString())
        val gone = eval(line, now, sources = mapOf("a/Other.kt" to "class Other\n"))
        assertTrue(gone.any { "stale allowlist entry: FooPlugin no longer exists" in it }, gone.toString())
        assertEquals(emptyList(), eval(line, now))
    }

    @Test
    fun allowlist_should_RejectMalformedLines_And_Duplicates() {
        val bad = eval("FooPlugin awaiting-unit U8 2026-10-27", today = LocalDate.parse("2026-11-01"))
        assertTrue(bad.any { "expected 'Class  reason  date" in it }, bad.toString())
        val twice = eval("FooPlugin  awaiting-unit U8  2026-10-27\nFooPlugin  awaiting-unit U8  2026-10-27", today = LocalDate.parse("2026-11-01"))
        assertTrue(twice.any { "duplicate allowlist entry: FooPlugin" in it }, twice.toString())
    }

    @Test
    fun allowlist_should_KeepVerifiedFalsePositivesAndEntryPoints_WithoutExpiry_But_RequireEvidence() {
        val farFuture = LocalDate.parse("2035-01-01")
        val ok = eval("FooPlugin  false-positive used in-file by the currentSpan extension  2026-10-27", today = farFuture)
        assertEquals(emptyList(), ok)
        val entry = eval("FooPlugin  entry-point run by ./gradlew :kmp:jvmBenchmark  2026-10-27", today = farFuture)
        assertEquals(emptyList(), entry)

        val noEvidence = eval("FooPlugin  entry-point  2026-10-27", today = farFuture)
        assertTrue(noEvidence.any { "must cite its evidence" in it }, noEvidence.toString())
        val never = eval("FooPlugin  false-positive because it is used  never", today = farFuture)
        assertTrue(never.any { "records its verification date" in it }, never.toString())
        val renewed = eval("FooPlugin  false-positive because it is used  2026-10-27  renewals=1", today = farFuture)
        assertTrue(renewed.any { "takes no renewals" in it }, renewed.toString())
        // Unverified reasons still expire.
        val temporary = eval("FooPlugin  temporary reason  2026-10-27", today = farFuture)
        assertTrue(temporary.any { it.startsWith("expired allowlist entry: FooPlugin") }, temporary.toString())
    }
}
