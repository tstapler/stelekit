// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 2.3 stale-editor rule (R3-A M1). */
class SketchStaleEditorTest {
    private val P = SketchTestSupport.P

    private fun assertStale(result: arrow.core.Either<DomainError, Unit>, backend: String) {
        val err = result.leftOrNull()
        assertTrue(err is DomainError.DatabaseError.WriteFailed && err.message == "stale-editor", "$backend: expected stale-editor, got $result")
    }

    @Test
    fun saves_should_FailWithStaleEditor_When_ProjectWasRebuiltSinceLoad() = SketchTestSupport.backends().forEach { b ->
        runBlocking {
            val r = b.repos
            r.projects.saveProject(SketchTestSupport.project()).getOrNull()
            val old = r.projects.loadProject(P).getOrNull()!!.token
            r.projects.replaceProjectContents(P, SketchTestSupport.bundle()).getOrNull()

            assertStale(r.sheets.saveSheet(SketchTestSupport.sheet("late"), old), b.name)
            assertStale(r.sheets.savePrimitives(listOf(SketchTestSupport.primitive("late", "p1-s0")), old), b.name)
            assertStale(r.sheets.deletePrimitive("p1-s0-pr0", old), b.name)
            assertStale(r.sheets.deleteSheet(SheetUuid("p1-s0"), old), b.name)
            assertStale(r.dimensions.saveDimension(SketchTestSupport.dimension("late"), old), b.name)
            assertStale(r.dimensions.deleteDimension("p1-d1", old), b.name)

            // Nothing from the stale editor reached the rebuilt rows.
            assertEquals(listOf("p1-s0"), r.sheets.getSheets(P).first().getOrNull()!!.items.map { it.uuid.value }, b.name)
            assertEquals(2, r.sheets.getPrimitives(SheetUuid("p1-s0")).first().getOrNull()!!.size, b.name)

            val fresh = r.projects.loadProject(P).getOrNull()!!.token
            assertTrue(fresh.value > old.value, b.name)
            assertEquals(null, r.sheets.saveSheet(SketchTestSupport.sheet("ok"), fresh).leftOrNull(), b.name)
        }
    }

    @Test
    fun replaceProjectContents_should_EmitInvalidation_And_ReplaceEveryCacheRow() = SketchTestSupport.backends().forEach { b ->
        runBlocking {
            val r = b.repos
            r.projects.saveProject(SketchTestSupport.project()).getOrNull()
            val token = r.projects.loadProject(P).getOrNull()!!.token
            r.sheets.saveSheet(SketchTestSupport.sheet("old"), token).getOrNull()
            r.dimensions.saveDimension(SketchTestSupport.dimension("old-d"), token).getOrNull()

            val seen = CompletableDeferred<String>()
            val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { seen.complete(r.projects.invalidations.first().value) }
            r.projects.replaceProjectContents(P, SketchTestSupport.bundle(sheets = 2)).getOrNull()
            assertEquals("p1", withTimeout(5_000) { seen.await() }, b.name)
            job.cancel()

            assertEquals(listOf("p1-s0", "p1-s1"), r.sheets.getSheets(P).first().getOrNull()!!.items.map { it.uuid.value }, b.name)
            assertEquals(listOf("p1-d1"), r.dimensions.getDimensions(P).first().getOrNull()!!.map { it.uuid }, b.name)
        }
    }

    @Test
    fun replaceProjectContents_should_Fail_When_BundleBelongsToAnotherProject() = SketchTestSupport.backends().forEach { b ->
        runBlocking {
            val r = b.repos
            r.projects.saveProject(SketchTestSupport.project()).getOrNull()
            val bad = SketchTestSupport.bundle(p = "other")
            assertTrue(r.projects.replaceProjectContents(P, bad).isLeft(), b.name)
        }
    }
}
