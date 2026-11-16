// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 1.1.3d: both `replaceProjectContents` implementations store a v2 sheet as a row with no primitives. */
class ReplaceProjectContentsV2Test {
    private val p = SketchTestSupport.P
    private val v2 = SheetUuid("p1-v2")

    private fun mixed() = SketchTestSupport.bundle(sheets = 1).let { base ->
        base.copy(
            sheets = base.sheets + SketchTestSupport.sheet("p1-v2", position = 1).copy(schemaVersion = 2),
            sheetPayloads = mapOf(v2 to SheetPayload.V2Opaque.of("{\"schema\":2}".encodeToByteArray())),
        )
    }

    private fun forEachBackend(body: suspend (SketchBackend) -> Unit) =
        SketchTestSupport.backends().forEach { b -> runBlocking { body(b) } }

    @Test
    fun replace_should_StoreTheV2SheetAsARowWithNoPrimitives_AndKeepTheV1SheetData() = forEachBackend { b ->
        b.repos.projects.replaceProjectContents(p, mixed()).getOrNull()!!

        val sheets = b.repos.sheets.getSheets(p).first().getOrNull()!!.items.associateBy { it.uuid }
        assertEquals(2L, sheets.getValue(v2).schemaVersion, b.name)
        assertEquals(1L, sheets.getValue(SheetUuid("p1-s0")).schemaVersion, b.name)
        assertEquals(emptyList(), b.repos.sheets.getPrimitives(v2).first().getOrNull(), b.name)
        assertEquals(2, b.repos.sheets.getPrimitives(SheetUuid("p1-s0")).first().getOrNull()!!.size, b.name)
        assertEquals(1, b.repos.dimensions.getDimensions(p).first().getOrNull()!!.size, b.name)
    }

    @Test
    fun replace_should_NotRewriteAnotherSheetsDataWhenTheBundleIsReplacedAgain() = forEachBackend { b ->
        b.repos.projects.replaceProjectContents(p, mixed()).getOrNull()!!
        val before = b.repos.sheets.getPrimitives(SheetUuid("p1-s0")).first().getOrNull()!!

        val noV2Primitives = mixed().copy(primitives = mixed().primitives.filter { it.sheetUuid != v2 })
        b.repos.projects.replaceProjectContents(p, noV2Primitives).getOrNull()!!

        assertEquals(before, b.repos.sheets.getPrimitives(SheetUuid("p1-s0")).first().getOrNull(), b.name)
        assertTrue(b.repos.sheets.getSheets(p).first().getOrNull()!!.items.any { it.uuid == v2 && it.schemaVersion == 2L }, b.name)
    }

    @Test
    fun replace_should_RejectAPrimitiveOfASheetOutsideTheBundle() = forEachBackend { b ->
        val stray = mixed().copy(primitives = mixed().primitives + SketchTestSupport.primitive("x", "elsewhere"))
        assertTrue(b.repos.projects.replaceProjectContents(p, stray).isLeft(), b.name)
    }
}
