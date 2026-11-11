// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaggingModelTest {
    private val registry = DimensionRegistry(dimensions = listOf(DimensionEntry("niche.width", DimensionKind.LENGTH, "Width")))

    @Test
    fun validateNewKey_should_CoverEachRule() {
        assertEquals(KeyError.Blank, Tagging.validateNewKey(registry, "  "))
        assertEquals(KeyError.BadFormat, Tagging.validateNewKey(registry, "Has Space"))
        assertEquals(KeyError.BadFormat, Tagging.validateNewKey(registry, ".lead"))
        assertEquals(KeyError.TooLong, Tagging.validateNewKey(registry, "a".repeat(Tagging.MAX_KEY_LENGTH + 1)))
        assertEquals(KeyError.Duplicate("niche.width"), Tagging.validateNewKey(registry, "niche.width"))
        assertNull(Tagging.validateNewKey(registry, "rod.height-2"))
    }

    @Test
    fun apply_should_AssignExisting_CreateNew_AndSkip() {
        val out = Tagging.apply(
            registry,
            mapOf(
                "svg:a" to TagChoice.Existing("niche.width"),
                "svg:b" to TagChoice.New(" rod.height ", "Rod height"),
                "svg:c" to TagChoice.Skip,
            ),
        ).getOrNull()!!
        assertEquals(mapOf("svg:a" to "niche.width", "svg:b" to "rod.height"), out.assignments)
        assertEquals(DimensionKind.LENGTH, out.registry.entry("rod.height")!!.kind)
        assertEquals("Rod height", out.registry.entry("rod.height")!!.label)
    }

    @Test
    fun apply_should_LetTwoLabelsShareOneNewKey() {
        val out = Tagging.apply(
            registry,
            mapOf("svg:a" to TagChoice.New("shelf.pitch", ""), "svg:b" to TagChoice.New("shelf.pitch", "")),
        ).getOrNull()!!
        assertEquals(setOf("shelf.pitch"), out.assignments.values.toSet())
        assertEquals(2, out.registry.dimensions.size)
        assertEquals("shelf.pitch", out.registry.entry("shelf.pitch")!!.label)
    }

    @Test
    fun apply_should_RejectUnknownKeyAndBadNewKey_ApplyingNothing() {
        assertEquals(KeyError.Unknown("zzz"), Tagging.apply(registry, mapOf("svg:a" to TagChoice.Existing("zzz"))).leftOrNull())
        assertEquals(KeyError.Duplicate("niche.width"), Tagging.apply(registry, mapOf("svg:a" to TagChoice.New("niche.width", ""))).leftOrNull())
    }

    @Test
    fun items_should_ListUnboundLabelsWithCandidates() {
        val label = BoundLabel("svg:x", "50\"", Rational(50, 1), Rational(1, 2), null, 0.3, listOf("a", "b"))
        val items = Tagging.items(LabelBindingResult(unbound = listOf(label)))
        assertEquals(listOf(TaggingItem("svg:x", "50\"", Rational(50, 1), Rational(1, 2), listOf("a", "b"))), items)
    }
}
