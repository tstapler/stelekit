// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins every production `@OptIn(DirectSqlWrite::class)` line (CLAUDE.md "Write enforcement"). A new site
 * must be added here deliberately, with the reviewer seeing it: the baseline was 40 lines when this test
 * was created, and `SqlConsoleWriter.rawExec` (the console's `sql!` path) is the one site added since.
 */
class DirectSqlWriteOptInAllowlistTest {
    private val expected = mapOf(
        "commonMain/dev/stapler/stelekit/asset/AssetMoveService.kt" to 3,
        "commonMain/dev/stapler/stelekit/db/FilePathRootMigration.kt" to 1,
        "commonMain/dev/stapler/stelekit/db/OperationLogger.kt" to 2,
        "commonMain/dev/stapler/stelekit/db/ShareWritePathMigration.kt" to 1,
        "commonMain/dev/stapler/stelekit/db/misplaced/PageFilePathRepointer.kt" to 1,
        "commonMain/dev/stapler/stelekit/db/SqlConsoleWriter.kt" to 1,
        "commonMain/dev/stapler/stelekit/db/DirectSqlWrite.kt" to 1,
        "commonMain/dev/stapler/stelekit/db/GraphManager.kt" to 2,
        "commonMain/dev/stapler/stelekit/db/UuidMigration.kt" to 1,
        "commonMain/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt" to 1,
        "commonMain/dev/stapler/stelekit/migration/ChangelogRepository.kt" to 1,
        "commonMain/dev/stapler/stelekit/git/SqlDelightGitConfigRepository.kt" to 2,
        "commonMain/dev/stapler/stelekit/performance/DebugFlagRepository.kt" to 2,
        "commonMain/dev/stapler/stelekit/performance/HistogramWriter.kt" to 1,
        "commonMain/dev/stapler/stelekit/performance/QueryStatsRepository.kt" to 2,
        "commonMain/dev/stapler/stelekit/performance/HistogramRetentionJob.kt" to 1,
        "commonMain/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt" to 8,
        "commonMain/dev/stapler/stelekit/repository/SqlDelightSpanRepository.kt" to 4,
        "commonMain/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt" to 6,
    )

    private fun optInLines(text: String) = text.lines().count { "OptIn(DirectSqlWrite" in it }

    @Test
    fun optIns_should_MatchTheAllowlist_When_ProductionSourcesScanned() {
        val actual = WiringInputs.productionSources
            .mapValues { optInLines(it.value) }
            .filterValues { it > 0 }
        assertTrue(actual.isNotEmpty(), "scan found no production sources")
        assertEquals(expected.toSortedMap(), actual.toSortedMap(), "a DirectSqlWrite opt-in site was added or removed; update the allowlist deliberately")
    }

    @Test
    fun consoleWriter_should_HaveExactlyOneOptInSite_When_RawSqlReachesTheDatabase() {
        val writer = WiringInputs.productionSources.entries.single { it.key.endsWith("db/SqlConsoleWriter.kt") }.value
        assertEquals(1, optInLines(writer))
        assertTrue("private suspend fun rawExec" in writer)
    }

    @Test
    fun baseline_should_BeFortyOne_When_FortyPlusTheConsoleWriterSite() {
        assertEquals(41, expected.values.sum())
    }
}
