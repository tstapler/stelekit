// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.ProjectUuid
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 2.3: deleteProject leaves no orphan rows whether or not the driver enforces foreign keys (A-m4). */
class SketchDeleteProjectTest {
    private val tables = listOf("sketch_projects", "sketch_sheets", "sketch_primitives", "sketch_dimensions")

    private fun check(foreignKeys: Boolean) = runBlocking {
        val b = SketchTestSupport.backends().single { it.driver != null }
        val driver = b.driver!!
        driver.execute(null, "PRAGMA foreign_keys = ${if (foreignKeys) "ON" else "OFF"}", 0).value
        val r = b.repos
        r.projects.replaceProjectContents(ProjectUuid("p1"), SketchTestSupport.bundle("p1", sheets = 2)).getOrNull()
        r.projects.replaceProjectContents(ProjectUuid("p2"), SketchTestSupport.bundle("p2", sheets = 1)).getOrNull()

        r.projects.deleteProject(ProjectUuid("p1")).getOrNull()

        assertEquals(listOf(1L, 1L, 2L, 1L), tables.map { SketchTestSupport.countRows(driver, it) }, "only p2 rows remain (foreign_keys=$foreignKeys)")
        r.projects.deleteProject(ProjectUuid("p2")).getOrNull()
        assertEquals(tables.map { 0L }, tables.map { SketchTestSupport.countRows(driver, it) }, "no orphans (foreign_keys=$foreignKeys)")
    }

    @Test
    fun deleteProject_should_LeaveNoOrphans_When_ForeignKeysOff() = check(false)

    @Test
    fun deleteProject_should_LeaveNoOrphans_When_ForeignKeysOn() = check(true)
}
