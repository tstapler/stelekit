// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class DatabaseOpenDiagnosticsTest {
    @Test
    fun describe_should_ReportForeignKeysAndSize_When_DatabaseIsOpen() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")

        val line = DatabaseOpenDiagnostics.describe(driver)

        assertTrue(line.contains("foreign_keys=1"), line)
        assertTrue(line.contains("journal_mode="), line)
        assertTrue(Regex("sizeBytes=\\d+").containsMatchIn(line), line)
        driver.close()
    }

    @Test
    fun describe_should_NotThrow_When_DatabaseIsClosed() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        driver.close()

        val line = DatabaseOpenDiagnostics.describe(driver)

        assertTrue(line.startsWith("SQLite state"), line)
    }
}
