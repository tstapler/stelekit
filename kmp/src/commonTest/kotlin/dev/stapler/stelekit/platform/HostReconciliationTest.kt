package dev.stapler.stelekit.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [classifyReconciliation] and [classifyReconciliationBytes] — the pure four-way
 * classifier backing the reconciliation pass (Phase 3) of the web-local-folder-livesync feature.
 * See Story 1.4.1 in `project_plans/web-local-folder-livesync/implementation/plan.md` and the
 * corresponding rows in validation.md.
 */
class HostReconciliationTest {

    // ── classifyReconciliation (String) ──────────────────────────────────────

    @Test
    fun classifyReconciliation_should_ReturnIdentical_When_HostAndCacheContentMatch() {
        val hostContent = "# Foo\nbar"
        val cacheContent = "# Foo\nbar"

        val result = classifyReconciliation(hostContent, cacheContent)

        assertEquals(ReconciliationOutcome.Identical, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnIdentical_When_BothSidesAreEmptyStringNotNull() {
        val result = classifyReconciliation("", "")

        assertEquals(ReconciliationOutcome.Identical, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnHostChangedConflict_When_BothSidesNonNullAndDiffer() {
        val hostContent = "# Foo\nedited on disk"
        val cacheContent = "# Foo\nedited in browser"

        val result = classifyReconciliation(hostContent, cacheContent)

        assertEquals(ReconciliationOutcome.HostChangedConflict, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnHostOnlyNew_When_CacheContentIsNull() {
        val result = classifyReconciliation("# NewPage", null)

        assertEquals(ReconciliationOutcome.HostOnlyNew, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnBrowserOnlyNeedsPush_When_HostContentIsNull() {
        val result = classifyReconciliation(null, "# Created in browser")

        assertEquals(ReconciliationOutcome.BrowserOnlyNeedsPush, result)
    }

    // ── classifyReconciliationBytes (ByteArray) ─────────────────────────────

    @Test
    fun classifyReconciliationBytes_should_UseContentEqualsNotReferenceEquality_When_ByteArraysAreEqualButDifferentInstances() {
        val hostBytes = byteArrayOf(1, 2, 3, 4)
        val cacheBytes = byteArrayOf(1, 2, 3, 4) // equal content, distinct instance

        val result = classifyReconciliationBytes(hostBytes, cacheBytes)

        assertEquals(ReconciliationOutcome.Identical, result)
    }

    @Test
    fun classifyReconciliationBytes_should_ReturnHostChangedConflict_When_ByteArraysDifferAndBothNonNull() {
        val hostBytes = byteArrayOf(1, 2, 3, 4)
        val cacheBytes = byteArrayOf(9, 9, 9, 9)

        val result = classifyReconciliationBytes(hostBytes, cacheBytes)

        assertEquals(ReconciliationOutcome.HostChangedConflict, result)
    }

    @Test
    fun classifyReconciliationBytes_should_ReturnHostOnlyNew_When_CacheBytesIsNull() {
        val hostBytes = byteArrayOf(1, 2, 3, 4)

        val result = classifyReconciliationBytes(hostBytes, null)

        assertEquals(ReconciliationOutcome.HostOnlyNew, result)
    }

    @Test
    fun classifyReconciliationBytes_should_ReturnBrowserOnlyNeedsPush_When_HostBytesIsNull() {
        val cacheBytes = byteArrayOf(1, 2, 3, 4)

        val result = classifyReconciliationBytes(null, cacheBytes)

        assertEquals(ReconciliationOutcome.BrowserOnlyNeedsPush, result)
    }

    // ── String/Bytes agreement ───────────────────────────────────────────────

    @Test
    fun classifyReconciliation_and_classifyReconciliationBytes_should_AgreeOnOutcome_When_GivenEquivalentTextAndUtf8BytesContent() {
        val hostText = "# Foo\nbar"
        val cacheText = "# Foo\nbar"

        val stringResult = classifyReconciliation(hostText, cacheText)
        val bytesResult = classifyReconciliationBytes(
            hostText.encodeToByteArray(),
            cacheText.encodeToByteArray(),
        )

        assertEquals(stringResult, bytesResult)
        assertEquals(ReconciliationOutcome.Identical, stringResult)
    }
}
