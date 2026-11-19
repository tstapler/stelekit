// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectSqlWrite::class, DirectRepositoryWrite::class)

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchBackend
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.SketchTestSupport.P
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pre-mortem P1 #1: `insertSketchSheet` is `INSERT OR REPLACE`, so any writer that forgets `schema_version`
 * silently turns a v2 sheet back into "v1". One test per writer of `sketch_sheets`, over both backends.
 *
 * The writers were enumerated by grepping `sketch_sheets` and `insertSketchSheet` (SteleDatabase.sq, the
 * `RestrictedDatabaseQueries` stubs, `SqlDelightSketchRepositories`, `InMemorySketchRepositories`): insert, the
 * schema-version update, the two deletes, `saveSheet`, and `replaceProjectContents`. A new writer that is not
 * listed in [writers] is a reason to extend this test.
 */
class SketchSheetWritePathsPreserveSchemaTest {
    private val writers = listOf(
        "RestrictedDatabaseQueries.insertSketchSheet",
        "SketchSheetRepository.saveSheet (layer, zones, position edits)",
        "SketchProjectRepository.replaceProjectContents",
        "SketchDimensionRepository.saveDimension (project-level edit)",
        "RestrictedDatabaseQueries.updateSketchSheetSchemaVersion",
    )

    private fun v2Sheet(uuid: String = "s1", position: Long = 0) =
        SketchTestSupport.sheet(uuid, position = position).copy(schemaVersion = 2)

    private suspend fun SketchBackend.versionOf(uuid: String): Long {
        val model = repos.sheets.getSheets(P).first().getOrNull()!!.items.first { it.uuid == SheetUuid(uuid) }.schemaVersion
        driver?.let { d ->
            val raw = d.executeQuery(null, "SELECT schema_version FROM sketch_sheets WHERE uuid = '$uuid'", { c ->
                c.next(); QueryResult.Value(c.getLong(0)!!)
            }, 0).value
            assertEquals(raw, model, "$name: model and raw column agree")
        }
        return model
    }

    private suspend fun SketchBackend.seedV2() {
        repos.projects.replaceProjectContents(P, ProjectBundle(SketchTestSupport.project(), listOf(v2Sheet()), emptyList(), emptyList()))
            .getOrNull() ?: error("seed failed for $name")
    }

    @Test
    fun everyWriter_should_BeEnumerated() {
        assertEquals(5, writers.size)
    }

    @Test
    fun savePath_should_KeepTheMirror_When_LayerZonesAndPositionAreEdited() = runBlocking {
        SketchTestSupport.backends().forEach { b ->
            b.seedV2()
            val token = b.repos.projects.loadProject(P).getOrNull()!!.token
            val loaded = b.repos.sheets.getSheets(P).first().getOrNull()!!.items.single()
            val edits = listOf(
                loaded.copy(layers = listOf("walls", "doors")),
                loaded.copy(zones = JsonArray(listOf(JsonPrimitive("z1")))),
                loaded.copy(position = 7),
            )
            edits.forEach { assertEquals(Unit, b.repos.sheets.saveSheet(it, token).getOrNull(), b.name) }
            assertEquals(2L, b.versionOf("s1"), b.name)
        }
    }

    @Test
    fun replaceProjectContents_should_StoreTheBundleSchemaVersion() = runBlocking {
        SketchTestSupport.backends().forEach { b ->
            b.seedV2()
            assertEquals(2L, b.versionOf("s1"), b.name)
            val mixed = ProjectBundle(
                SketchTestSupport.project(),
                listOf(v2Sheet("s1"), SketchTestSupport.sheet("s2", position = 1)),
                emptyList(), emptyList(),
            )
            b.repos.projects.replaceProjectContents(P, mixed).getOrNull()
            assertEquals(2L, b.versionOf("s1"), b.name)
            assertEquals(1L, b.versionOf("s2"), b.name)
        }
    }

    @Test
    fun dimensionSave_should_NotTouchTheSheetMirror() = runBlocking {
        SketchTestSupport.backends().forEach { b ->
            b.seedV2()
            val token = b.repos.projects.loadProject(P).getOrNull()!!.token
            b.repos.dimensions.saveDimension(SketchTestSupport.dimension("d1"), token).getOrNull()
            assertEquals(2L, b.versionOf("s1"), b.name)
        }
    }

    @Test
    fun insertStub_should_CarryTheColumn_AndTheMirrorUpdateStubSetsIt() = runBlocking {
        val b = SketchTestSupport.backends().single { it.driver != null }
        val s = RestrictedDatabaseQueries(b.database!!.steleDatabaseQueries)
        s.insertSketchProject("p1", "proj-0a1b2c", "n", 0, "{}", "{}", "h", "d", 1, 1, null)
        s.insertSketchSheet("s1", "p1", "plan", "top", "in", 16, null, "[]", "[]", 0, 2)
        assertEquals(2L, b.versionOf("s1"))
        s.insertSketchSheet("s1", "p1", "plan", "top", "in", 16, null, "[]", "[]", 3, 2)
        assertEquals(2L, b.versionOf("s1"), "re-insert with the column keeps 2")
        s.updateSketchSheetSchemaVersion(1, "s1")
        assertEquals(1L, b.versionOf("s1"))
    }
}
