// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceBindingTest {
    private val table = SourceBinding(SourceBindingKind.TABLE, "page-b", "h1")
    private val prose = SourceBinding(SourceBindingKind.PROSE, "page-a", "h2")

    @Test
    fun bindingsRoundTripAndSortDeterministically() {
        val s = SourceBindings.with(SourceBindings.with(JsonObject(emptyMap()), table)!!, prose)!!
        assertEquals(listOf(prose, table), SourceBindings.of(s))
    }

    @Test
    fun rebindingSamePageReplacesTheHash() {
        val s = SourceBindings.with(SourceBindings.with(JsonObject(emptyMap()), table)!!, table.copy(contentHash = "new"))!!
        assertEquals(listOf(table.copy(contentHash = "new")), SourceBindings.of(s))
    }

    @Test
    fun otherSettingsAndNonPageSourcesSurvive() {
        val yaml = JsonObject(mapOf("kind" to JsonPrimitive("yaml"), "snapshot" to JsonPrimitive("ab12cd34")))
        val base = JsonObject(mapOf("keep" to JsonPrimitive(1), SourceBindings.KEY to JsonArray(listOf(yaml))))
        val bound = SourceBindings.with(base, table)!!
        assertEquals(JsonPrimitive(1), bound["keep"])
        assertTrue((bound[SourceBindings.KEY] as JsonArray).contains(yaml))
        val removed = SourceBindings.without(bound, table.kind, table.pageUuid)
        assertEquals(base, removed)
        assertEquals(emptyList(), SourceBindings.of(removed))
    }

    @Test
    fun removingTheLastBindingDropsTheKey() {
        val s = SourceBindings.with(JsonObject(emptyMap()), table)!!
        assertFalse(SourceBindings.without(s, table.kind, table.pageUuid).containsKey(SourceBindings.KEY))
    }

    @Test
    fun malformedEntriesAreIgnored() {
        val bad = JsonArray(listOf(
            JsonPrimitive("x"),
            JsonObject(mapOf("kind" to JsonPrimitive("svg"), "pageUuid" to JsonPrimitive("p"), "contentHash" to JsonPrimitive("h"))),
            JsonObject(mapOf("kind" to JsonPrimitive("table"), "pageUuid" to JsonPrimitive(" "), "contentHash" to JsonPrimitive("h"))),
            JsonObject(mapOf("kind" to JsonPrimitive("table"), "pageUuid" to JsonPrimitive("p"))),
        ))
        assertEquals(emptyList(), SourceBindings.of(JsonObject(mapOf(SourceBindings.KEY to bad))))
    }

    @Test
    fun bindingLimitIsEnforced() {
        var s = JsonObject(emptyMap())
        repeat(SourceBindings.MAX_BINDINGS) { s = assertNotNull(SourceBindings.with(s, SourceBinding(SourceBindingKind.TABLE, "p$it", "h"))) }
        assertNull(SourceBindings.with(s, SourceBinding(SourceBindingKind.TABLE, "extra", "h")))
        assertNotNull(SourceBindings.with(s, SourceBinding(SourceBindingKind.TABLE, "p0", "changed")))
    }

    @Test
    fun encodedBindingCarriesOnlyIdsAndAHash() {
        assertEquals(setOf("kind", "pageUuid", "contentHash"), SourceBindings.encode(table).keys)
        assertEquals(SourceBindingKind.PROSE, SourceBindingKind.fromWire("prose"))
        assertNull(SourceBindingKind.fromWire(null))
    }

    @Test
    fun pickerTextNamesKindsAndFlagsMissingPages() {
        assertEquals("table", SourcePickerText.kindLabel(SourceBindingKind.TABLE))
        assertEquals("notes", SourcePickerText.kindLabel(SourceBindingKind.PROSE))
        assertEquals("Plan (table)", SourcePickerText.boundLabel(BoundSourceRow(SourceBindingKind.TABLE, "p", "Plan")))
        assertEquals("Missing page (notes)", SourcePickerText.boundLabel(BoundSourceRow(SourceBindingKind.PROSE, "p", null)))
    }
}
