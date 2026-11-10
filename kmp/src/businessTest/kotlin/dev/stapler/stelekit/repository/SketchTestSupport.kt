// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.units.Rational

/** One backend under test; [driver] is null for the in-memory backend. */
class SketchBackend(val name: String, val repos: SketchRepositories, val driver: SqlDriver?, val database: SteleDatabase?)

object SketchTestSupport {
    fun backends(): List<SketchBackend> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        return listOf(
            SketchBackend("in-memory", inMemorySketchRepositories(), null, null),
            SketchBackend("sqldelight", sqlDelightSketchRepositories(database), driver, database),
        )
    }

    val P = ProjectUuid("p1")

    fun project(uuid: String = "p1", slug: String = "proj-0a1b2c", createdAt: Long = 1L) =
        SketchProject(uuid = ProjectUuid(uuid), slug = slug, name = "Kitchen", createdAt = createdAt)

    fun sheet(uuid: String, project: String = "p1", position: Long = 0) = SketchSheet(
        uuid = SheetUuid(uuid), projectUuid = ProjectUuid(project), kind = "plan", viewDirection = "top",
        unit = "in", scaleSixteenthsPerUnit = 16, layers = listOf("walls"), position = position,
    )

    fun primitive(uuid: String, sheet: String, z: Long = 0) =
        SketchPrimitive(uuid, SheetUuid(sheet), "line", 0, 0, 608, 0, "n", "walls", z)

    /** 37.9 inches, exactly as an observation would hold it. */
    fun dimension(uuid: String, project: String = "p1", key: String = "width", sourceRef: String = "r1") = SketchDimension(
        uuid = uuid, projectUuid = ProjectUuid(project), key = key, kind = "length", value = Rational(379, 10),
        precision = Rational(1, 20), sourceKind = "sheet", sourceRef = sourceRef, tolerance = Rational(0, 1),
    )

    fun bundle(p: String = "p1", sheets: Int = 1, primitivesPerSheet: Int = 2) = ProjectBundle(
        project = project(p),
        sheets = (0 until sheets).map { sheet("$p-s$it", p, it.toLong()) },
        primitives = (0 until sheets).flatMap { s -> (0 until primitivesPerSheet).map { primitive("$p-s$s-pr$it", "$p-s$s", it.toLong()) } },
        dimensions = listOf(dimension("$p-d1", p)),
    )

    fun countRows(driver: SqlDriver, table: String): Long =
        driver.executeQuery(null, "SELECT COUNT(*) FROM $table", { c ->
            c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!)
        }, 0).value
}
