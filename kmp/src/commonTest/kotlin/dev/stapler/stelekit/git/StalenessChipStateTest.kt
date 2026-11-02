// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalenessChipStateTest {

    private val minute = 60_000L
    private val now = 10_000_000_000L

    private fun staleness(
        merged: Long? = null,
        behind: Int? = 0,
        reason: FetchOnlyReason? = null,
        firstSeen: Long? = null,
        history: List<BackgroundOutcome> = emptyList(),
    ) = SyncStaleness(lastMergedAtMs = merged, behindCount = behind, fetchOnlyReason = reason, firstSeenAtMs = firstSeen, history = history)

    @Test
    fun `a recent merge with nothing behind reads healthy`() {
        val chip = stalenessChipState(staleness(merged = now - 5 * minute), now, 15)
        assertEquals("last merged 5 min ago · behind 0", chip.text)
        assertEquals(StalenessSeverity.Healthy, chip.severity)
    }

    @Test
    fun `behind commits older than twice the interval turn amber`() {
        assertEquals(StalenessSeverity.Healthy, stalenessChipState(staleness(merged = now - 29 * minute, behind = 2), now, 15).severity)
        assertEquals(StalenessSeverity.Amber, stalenessChipState(staleness(merged = now - 31 * minute, behind = 2), now, 15).severity)
        assertEquals(StalenessSeverity.Healthy, stalenessChipState(staleness(merged = now - 300 * minute, behind = 0), now, 15).severity)
    }

    @Test
    fun `a graph that never merged says so and never reads last merged 0 min ago`() {
        val young = stalenessChipState(staleness(firstSeen = now - 10 * minute, behind = 3), now, 15)
        assertEquals("no merge yet · behind 3", young.text)
        assertEquals(StalenessSeverity.Healthy, young.severity)
        assertEquals(StalenessSeverity.Amber, stalenessChipState(staleness(firstSeen = now - 31 * minute, behind = 3), now, 15).severity)
        assertEquals("last merged just now · behind 0", stalenessChipState(staleness(merged = now - 5_000), now, 15).text)
    }

    @Test
    fun `an unknown behind count reads checking and stays neutral`() {
        val chip = stalenessChipState(staleness(merged = now - minute, behind = null), now, 15)
        assertEquals("last merged 1 min ago · checking…", chip.text)
        assertEquals(StalenessSeverity.Neutral, chip.severity)
    }

    @Test
    fun `three failed outcomes in a row turn the chip amber`() {
        val errors = List(3) { BackgroundOutcome(now - it * minute, BackgroundOutcomeKind.Error, 0, "FetchFailed") }
        assertEquals(StalenessSeverity.Amber, stalenessChipState(staleness(merged = now - minute, history = errors), now, 15).severity)
        val mixed = errors.dropLast(1) + BackgroundOutcome(now, BackgroundOutcomeKind.Fetched, 0)
        assertEquals(StalenessSeverity.Healthy, stalenessChipState(staleness(merged = now - minute, history = mixed), now, 15).severity)
    }

    @Test
    fun `a fetch-only graph names the reason and is amber while updates wait`() {
        val saf = stalenessChipState(staleness(reason = FetchOnlyReason.Saf, behind = 4), now, 15)
        assertEquals("fetch-only (SAF) · behind 4", saf.text)
        assertEquals(StalenessSeverity.Amber, saf.severity)
        assertEquals("fetch-only (app closed) · behind 0", stalenessChipState(staleness(reason = FetchOnlyReason.AppClosed), now, 15).text)
    }

    @Test
    fun `chip state is the specified function of age, behind, interval, outcomes and reason for any input`() = runTest {
        val outcome = Arb.element(BackgroundOutcomeKind.entries).map { BackgroundOutcome(now, it, 0) }
        checkAll(
            Arb.long(0L..2_000L).orNull(),
            Arb.int(0..50).orNull(),
            Arb.int(1..120),
            Arb.element(FetchOnlyReason.entries).orNull(),
            Arb.list(outcome, 0..6),
            Arb.boolean(),
        ) { mergedAgoMin, behind, interval, reason, history, neverMerged ->
            val merged = if (neverMerged) null else mergedAgoMin?.let { now - it * minute }
            val s = staleness(merged = merged, behind = behind, reason = reason, firstSeen = now - (mergedAgoMin ?: 0L) * minute, history = history)
            val chip = stalenessChipState(s, now, interval)

            if (reason != null) {
                assertFalse(chip.severity == StalenessSeverity.Healthy, "fetch-only is never green")
                assertTrue(chip.text.startsWith("fetch-only ("))
            }
            val anchorAgeMin = (merged ?: s.firstSeenAtMs)?.let { (now - it) / minute } ?: 0L
            val failing = history.takeLast(3).let { it.size == 3 && it.all { o -> o.kind == BackgroundOutcomeKind.Error } }
            val stale = behind != null && behind > 0 && anchorAgeMin * minute > 2L * interval * minute
            if (reason == null && (stale || failing)) assertEquals(StalenessSeverity.Amber, chip.severity)
            if (reason == null && !stale && !failing && behind != null) assertEquals(StalenessSeverity.Healthy, chip.severity)
            assertTrue(chip.text.endsWith(if (behind == null) "checking…" else "behind $behind"))
        }
    }

    @Test
    fun `age text never reports zero minutes and steps through hours and days`() {
        assertEquals("just now", ageText(0))
        assertEquals("just now", ageText(59_999))
        assertEquals("1 min ago", ageText(60_000))
        assertEquals("2 h ago", ageText(125 * minute))
        assertEquals("3 d ago", ageText(3 * 24 * 60 * minute))
    }
}
