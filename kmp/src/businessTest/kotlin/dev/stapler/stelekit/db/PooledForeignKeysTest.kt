// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Migrations bracket table rebuilds with `PRAGMA foreign_keys=OFF/ON`; on a FIFO connection pool the OFF can land on
 * a connection that never sees the ON. Every pooled connection must come out of driver creation with cascades enforced.
 */
class PooledForeignKeysTest {
    @Test
    fun everyPooledConnectionEnforcesForeignKeysAfterMigrations() {
        val dir = Files.createTempDirectory("pool-fk").toFile()
        val driver = DriverFactory().createDriver("jdbc:sqlite:${dir.resolve("graph.db").absolutePath}")
        try {
            // Eight sequential statements visit each of the eight pooled connections once.
            val states = (1..POOL_SIZE).map {
                driver.executeQuery(null, "PRAGMA foreign_keys", { c -> c.next(); QueryResult.Value(c.getLong(0)) }, 0).value
            }
            assertEquals(List(POOL_SIZE) { 1L }, states)
        } finally {
            driver.close()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val POOL_SIZE = 8
    }
}
