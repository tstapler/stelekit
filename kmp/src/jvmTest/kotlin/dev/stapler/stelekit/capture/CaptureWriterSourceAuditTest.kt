// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source audit for backlog acceptance criterion AC2 (desktop-quick-capture): `CaptureWriter`
 * must reuse the existing commonMain write chain rather than inventing a second write
 * mechanism. A structural/behavioral test can't observe "which SQL path was used" directly,
 * so this test inspects the source text itself for the banned/required calls — see
 * `implementation/validation.md`'s Requirement -> Test Mapping table for AC2.
 */
class CaptureWriterSourceAuditTest {

    private val source: String by lazy {
        val path = "src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureWriter.kt"
        val candidates = listOf(File(path), File("kmp/$path"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("CaptureWriter.kt not found at any of: ${candidates.map { it.path }}")
        file.readText()
    }

    @Test
    fun captureWriterSource_should_ContainNoDirectSqlWriteOptIn_And_MatchExistingWriteChain() {
        // Required: the existing write chain, verbatim.
        assertTrue(source.contains("ensureTodayJournal()"), "expected a call to journalService.ensureTodayJournal()")
        assertTrue(source.contains("writeActor.saveBlock"), "expected the writeActor.saveBlock(...) branch")
        assertTrue(
            source.contains("@OptIn(DirectRepositoryWrite::class)"),
            "expected the existing DirectRepositoryWrite fallback (ported from CaptureViewModel's Bug 1 mitigation)",
        )
        assertTrue(source.contains(".saveBlock(block)"), "expected the DirectRepositoryWrite fallback's saveBlock call")
        assertTrue(source.contains("GraphWriter("), "expected a GraphWriter(...).savePage(...) flush")
        assertTrue(source.contains(".savePage("), "expected a GraphWriter(...).savePage(...) flush")

        // Banned: no second, bespoke write mechanism.
        assertFalse(
            source.contains("@OptIn(DirectSqlWrite::class)"),
            "CaptureWriter must not use @OptIn(DirectSqlWrite::class) -- it must reuse the existing " +
                "write chain, not a direct SQL mutator call",
        )
        assertFalse(
            source.contains("SteleDatabaseQueries"),
            "CaptureWriter must not reference SteleDatabaseQueries directly",
        )
    }
}
