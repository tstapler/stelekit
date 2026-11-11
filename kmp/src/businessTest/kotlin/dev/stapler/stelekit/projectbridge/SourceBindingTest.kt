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
    private val table = SourceBinding(SourceBindingKind.TABLE, "0000000b", "h1")
    private val prose = SourceBinding(SourceBindingKind.PROSE, "0000000a", "h2")

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
        val removed = SourceBindings.without(bound, table.bindingId)
        assertEquals(base, removed)
        assertEquals(emptyList(), SourceBindings.of(removed))
    }

    @Test
    fun removingTheLastBindingDropsTheKey() {
        val s = SourceBindings.with(JsonObject(emptyMap()), table)!!
        assertFalse(SourceBindings.without(s, table.bindingId).containsKey(SourceBindings.KEY))
    }

    @Test
    fun malformedEntriesAreIgnored() {
        val bad = JsonArray(listOf(
            JsonPrimitive("x"),
            JsonObject(mapOf("kind" to JsonPrimitive("svg"), "bindingId" to JsonPrimitive("00000001"), "contentHash" to JsonPrimitive("h"))),
            JsonObject(mapOf("kind" to JsonPrimitive("table"), "bindingId" to JsonPrimitive("not hex"), "contentHash" to JsonPrimitive("h"))),
            JsonObject(mapOf("kind" to JsonPrimitive("table"), "bindingId" to JsonPrimitive("00000001"))),
        ))
        assertEquals(emptyList(), SourceBindings.of(JsonObject(mapOf(SourceBindings.KEY to bad))))
    }

    @Test
    fun bindingLimitIsEnforced() {
        var s = JsonObject(emptyMap())
        repeat(SourceBindings.MAX_BINDINGS) { s = assertNotNull(SourceBindings.with(s, SourceBinding(SourceBindingKind.TABLE, it.toString().padStart(8, '0'), "h"))) }
        assertNull(SourceBindings.with(s, SourceBinding(SourceBindingKind.TABLE, "ffffffff", "h")))
        assertNotNull(SourceBindings.with(s, SourceBinding(SourceBindingKind.TABLE, "00000000", "changed")))
    }

    @Test
    fun encodedBindingCarriesOnlyIdsAndAHash() {
        assertEquals(setOf("kind", "bindingId", "contentHash"), SourceBindings.encode(table).keys)
        assertEquals(SourceBindingKind.PROSE, SourceBindingKind.fromWire("prose"))
        assertNull(SourceBindingKind.fromWire(null))
    }

    @Test
    fun pickerTextNamesKindsAndFlagsMissingPages() {
        assertEquals("table", SourcePickerText.kindLabel(SourceBindingKind.TABLE))
        assertEquals("notes", SourcePickerText.kindLabel(SourceBindingKind.PROSE))
        assertEquals("Plan (table)", SourcePickerText.boundLabel(BoundSourceRow(SourceBindingKind.TABLE, "00000001", "Plan")))
        assertEquals("Missing page (notes)", SourcePickerText.boundLabel(BoundSourceRow(SourceBindingKind.PROSE, "00000001", null)))
    }

    @Test
    fun rebindHintShowsForUnresolvedRows() {
        val row = BoundSourceRow(SourceBindingKind.TABLE, "00000001", null, needsRebind = true)
        assertTrue(SourcePickerText.boundLabel(row).startsWith(SourcePickerText.REBIND_HINT))
    }

    @Test
    fun bindingIdsAreOpaqueEightHex() {
        assertTrue(BindingIds.isValid(BindingIds.generate(kotlin.random.Random(3))))
        assertEquals(8, BindingIds.generate(kotlin.random.Random(0)).length)
        assertFalse(BindingIds.isValid("xyz"))
    }

    @Test
    fun tableCodecRoundTripsAndIgnoresJunk() {
        val m = mapOf("0000000a" to "uuid-a", "0000000b" to "uuid-b")
        assertEquals(m, BindingTableCodec.parse(BindingTableCodec.format(m)))
        assertEquals(emptyMap(), BindingTableCodec.parse("junk\n=x\nnothex=1\n0000000a="))
    }

    @Test
    fun localTableIsPerSlug() {
        val t = LocalBindingTable(BridgeMapSettings())
        t.put("proj-000001", "0000000a", "u1")
        assertEquals("u1", t.pageUuid("proj-000001", "0000000a"))
        assertNull(t.pageUuid("proj-000002", "0000000a"))
        t.remove("proj-000001", "0000000a")
        assertNull(t.pageUuid("proj-000001", "0000000a"))
    }

    @Test
    fun resolverFlagsUnknownBindingsAndMatchesByHashOncePerPage() {
        val a = SourceBinding(SourceBindingKind.TABLE, "0000000a", "h")
        val b = SourceBinding(SourceBindingKind.PROSE, "0000000b", "h")
        val r = BindingResolver.resolve(listOf(a, b)) { if (it == "0000000a") "u1" else null }
        assertEquals(BindingResolution.Resolved(a, "u1"), r[0])
        assertEquals(BindingResolution.NeedsRebind(b), r[1])
        assertEquals(mapOf("0000000a" to "p1", "0000000b" to "p2"), BindingResolver.matchByHash(listOf(a, b), mapOf("p1" to "h", "p2" to "h")))
        assertEquals(mapOf("0000000a" to "p1"), BindingResolver.matchByHash(listOf(a, b), mapOf("p1" to "h", "p2" to "other")))
    }
}
