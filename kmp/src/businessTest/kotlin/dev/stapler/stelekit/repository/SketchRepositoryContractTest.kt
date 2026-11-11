// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 2.3: the same contract against the in-memory and the SQLDelight backend. */
class SketchRepositoryContractTest {
    private val P = SketchTestSupport.P

    private fun <T> Either<DomainError, T>.ok(): T = getOrNull() ?: error("expected Right but was $this")

    private fun forEachBackend(body: suspend (SketchBackend) -> Unit) =
        SketchTestSupport.backends().forEach { b -> runBlocking { body(b) } }

    @Test
    fun observation_should_RoundTripExactly_When_37_9InchesSavedOnEitherBackend() = forEachBackend { b ->
        val r = b.repos
        r.projects.saveProject(SketchTestSupport.project()).ok()
        val token = r.projects.loadProject(P).ok()!!.token
        r.dimensions.saveDimension(SketchTestSupport.dimension("d1"), token).ok()
        val got = r.dimensions.getDimensions(P).first().ok().single()
        assertEquals(Rational(379, 10), got.value, b.name)
        assertEquals(Rational(1, 20), got.precision, b.name)
    }

    @Test
    fun count_should_KeepNullPrecision_When_SavedWithoutOne() = forEachBackend { b ->
        val r = b.repos
        r.projects.saveProject(SketchTestSupport.project()).ok()
        val token = r.projects.loadProject(P).ok()!!.token
        r.dimensions.saveDimension(SketchTestSupport.dimension("d1").copy(kind = "count", precision = null, value = Rational(4)), token).ok()
        assertNull(r.dimensions.getDimensions(P).first().ok().single().precision, b.name)
    }

    @Test
    fun saveDimension_should_ReplaceTheClashingRow_When_UniqueKeyRepeats() = forEachBackend { b ->
        val r = b.repos
        r.projects.saveProject(SketchTestSupport.project()).ok()
        val token = r.projects.loadProject(P).ok()!!.token
        r.dimensions.saveDimension(SketchTestSupport.dimension("d1"), token).ok()
        r.dimensions.saveDimension(SketchTestSupport.dimension("d2").copy(value = Rational(40)), token).ok()
        val rows = r.dimensions.getDimensions(P).first().ok()
        assertEquals(listOf("d2"), rows.map { it.uuid }, b.name)
    }

    @Test
    fun getSheets_should_ReportHasMore_When_MoreThanLimitExist() = forEachBackend { b ->
        val r = b.repos
        r.projects.saveProject(SketchTestSupport.project()).ok()
        val token = r.projects.loadProject(P).ok()!!.token
        (0 until 5).forEach { r.sheets.saveSheet(SketchTestSupport.sheet("s$it", position = it.toLong()), token).ok() }
        val page = r.sheets.getSheets(P, limit = 3).first().ok()
        assertEquals(listOf("s0", "s1", "s2"), page.items.map { it.uuid.value }, b.name)
        assertTrue(page.hasMore, b.name)
        val all = r.sheets.getSheets(P, limit = 5).first().ok()
        assertEquals(5, all.items.size, b.name)
        assertFalse(all.hasMore, b.name)
    }

    @Test
    fun getSheets_should_DefaultToFifty_When_NoLimitGiven() = forEachBackend { b ->
        val r = b.repos
        r.projects.saveProject(SketchTestSupport.project()).ok()
        val token = r.projects.loadProject(P).ok()!!.token
        (0 until 51).forEach { r.sheets.saveSheet(SketchTestSupport.sheet("s%02d".format(it), position = it.toLong()), token).ok() }
        val page = r.sheets.getSheets(P).first().ok()
        assertEquals(50, page.items.size, b.name)
        assertTrue(page.hasMore, b.name)
    }

    @Test
    fun getPrimitives_should_ClampToFiveHundredAndPageByOffset_When_SheetIsLarge() = forEachBackend { b ->
        val r = b.repos
        r.projects.saveProject(SketchTestSupport.project()).ok()
        val token = r.projects.loadProject(P).ok()!!.token
        r.sheets.saveSheet(SketchTestSupport.sheet("s1"), token).ok()
        r.sheets.savePrimitives((0 until 620).map { SketchTestSupport.primitive("pr%04d".format(it), "s1", z = it.toLong()) }, token).ok()
        val first = r.sheets.getPrimitives(SheetUuid("s1"), limit = 10_000, offset = 0).first().ok()
        assertEquals(500, first.size, b.name)
        val second = r.sheets.getPrimitives(SheetUuid("s1"), limit = 500, offset = 500).first().ok()
        assertEquals(120, second.size, b.name)
        assertEquals("pr0500", second.first().uuid, b.name)
    }

    @Test
    fun deleteSheet_should_RemoveItsPrimitives_And_deletePrimitive_ItsOne() = forEachBackend { b ->
        val r = b.repos
        r.projects.saveProject(SketchTestSupport.project()).ok()
        val token = r.projects.loadProject(P).ok()!!.token
        r.sheets.saveSheet(SketchTestSupport.sheet("s1"), token).ok()
        r.sheets.savePrimitives(listOf(SketchTestSupport.primitive("a", "s1"), SketchTestSupport.primitive("b", "s1", 1)), token).ok()
        r.sheets.deletePrimitive("a", token).ok()
        assertEquals(listOf("b"), r.sheets.getPrimitives(SheetUuid("s1")).first().ok().map { it.uuid }, b.name)
        r.sheets.deleteSheet(SheetUuid("s1"), token).ok()
        assertTrue(r.sheets.getPrimitives(SheetUuid("s1")).first().ok().isEmpty(), b.name)
        assertTrue(r.sheets.getSheets(P).first().ok().items.isEmpty(), b.name)
    }

    @Test
    fun getProjects_should_PageNewestFirst_And_loadProject_ReturnNullWhenAbsent() = forEachBackend { b ->
        val r = b.repos
        (1..3).forEach { r.projects.saveProject(SketchTestSupport.project("p$it", "proj-00000$it", createdAt = it.toLong())).ok() }
        assertEquals(listOf("p3", "p2"), r.projects.getProjects(2, 0).first().ok().map { it.uuid.value }, b.name)
        assertEquals(listOf("p1"), r.projects.getProjects(2, 2).first().ok().map { it.uuid.value }, b.name)
        assertNull(r.projects.loadProject(ProjectUuid("nope")).getOrNull(), b.name)
        assertNotNull(r.projects.getProjectByUuid(ProjectUuid("p1")).first().getOrNull(), b.name)
    }

    @Test
    fun projectJson_should_RoundTrip_When_SettingsAndZonesCarryValues() = forEachBackend { b ->
        val r = b.repos
        val settings = kotlinx.serialization.json.JsonObject(mapOf("unit" to kotlinx.serialization.json.JsonPrimitive("in")))
        r.projects.saveProject(SketchTestSupport.project().copy(settings = settings)).ok()
        assertEquals(settings, r.projects.loadProject(P).ok()!!.project.settings, b.name)
    }
}
