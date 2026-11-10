// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectSqlWrite::class)

package dev.stapler.stelekit.db

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 2.2: every sketch INSERT/DELETE has a `@DirectSqlWrite` forwarding stub, and each one writes. */
class SketchStubGatingTest {
    @Test
    fun stubs_should_InsertAndDeleteEveryTable_When_Exercised() = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        val q = db.steleDatabaseQueries
        val s = RestrictedDatabaseQueries(q)

        fun count(table: String): Long = driver.executeQuery(null, "SELECT COUNT(*) FROM $table", { c ->
            c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!)
        }, 0).value

        s.insertSketchProject("p1", "proj-0a1b2c", "n", 0, "{}", "{}", "h", "d", 1, 1, null)
        s.insertSketchSheet("s1", "p1", "plan", "top", "in", 16, null, "[]", "[]", 0)
        s.insertSketchPrimitive("a", "s1", "line", 0, 0, 1, 1, "", "", 0)
        s.insertSketchPrimitive("b", "s1", "line", 0, 0, 1, 1, "", "", 1)
        s.insertSketchPrimitive("c", "s1", "line", 0, 0, 1, 1, "", "", 2)
        s.insertSketchDimension("d1", "p1", "w", "length", 379, 10, 1, 20, "sheet", "r", 0, 1, 0)
        s.insertSketchDimension("d2", "p1", "h", "length", 1, 1, null, null, "sheet", "r", 0, 1, 1)
        assertEquals(listOf(1L, 1L, 3L, 2L), listOf("sketch_projects", "sketch_sheets", "sketch_primitives", "sketch_dimensions").map(::count))

        s.deleteSketchPrimitive("a")
        assertEquals(2L, count("sketch_primitives"))
        s.deleteSketchPrimitivesBySheet("s1")
        assertEquals(0L, count("sketch_primitives"))
        s.insertSketchPrimitive("a", "s1", "line", 0, 0, 1, 1, "", "", 0)
        s.deleteSketchPrimitivesByProject("p1")
        assertEquals(0L, count("sketch_primitives"))
        s.deleteSketchDimension("d1")
        assertEquals(1L, count("sketch_dimensions"))
        s.deleteSketchDimensionsByProject("p1")
        assertEquals(0L, count("sketch_dimensions"))
        s.deleteSketchSheet("s1")
        assertEquals(0L, count("sketch_sheets"))
        s.insertSketchSheet("s2", "p1", "plan", "top", "in", 16, null, "[]", "[]", 0)
        s.deleteSketchSheetsByProject("p1")
        assertEquals(0L, count("sketch_sheets"))
        s.deleteSketchProject("p1")
        assertEquals(0L, count("sketch_projects"))
        driver.close()
    }
}
