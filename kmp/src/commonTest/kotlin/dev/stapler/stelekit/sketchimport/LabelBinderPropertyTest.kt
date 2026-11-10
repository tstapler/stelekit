// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelBinderPropertyTest {
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry("niche.width", DimensionKind.LENGTH, "w", DimensionAliases(svgLabel = listOf("Total Width"))),
            DimensionEntry("rod.height", DimensionKind.LENGTH, "r", DimensionAliases(svgLabel = listOf("Rod"))),
        ),
    )
    private val base = """<svg width="900" height="900"><g transform="scale(10,-10) translate(0,-50)">
        <path d="M0,0 L40,0"/></g>
        <text x="200" y="100" text-anchor="middle">Total Width: 40"</text>
        <text x="200" y="120" text-anchor="middle">36" Rod</text>
        <text x="200" y="140" text-anchor="middle">Ceiling Heights</text></svg>"""

    @Test
    fun inlineSheetBindsAliasesAndSkipsCaptions() {
        val r = LabelBinder(registry, RefTable()).bind(readSvg(base), "inline")
        assertEquals(listOf("niche.width", "rod.height"), r.bound.map { it.dimensionKey })
        assertEquals(listOf("Ceiling Heights"), r.skipped)
    }

    @Test
    fun mutatedInputNeverThrowsAndRefsStayOpaque() = runTest {
        checkAll(200, Arb.int(0..base.length), Arb.int(0..40), Arb.string(0..6)) { start, len, insert ->
            val mutated = base.substring(0, start) + insert + base.substring(minOf(base.length, start + len))
            val r = LabelBinder(registry, RefTable()).bind(readSvg(mutated), "m")
            assertTrue((r.bound + r.unbound).all { it.ref.startsWith("svg:") })
            assertTrue(r.bound.all { it.dimensionKey != null && it.confidence >= 0.0 })
            assertTrue(r.unbound.all { it.dimensionKey == null })
        }
    }

    @Test
    fun spanConfidenceIsBoundedMonotoneAndZeroWhenFar() = runTest {
        checkAll(200, Arb.double(1.0, 200.0).filter { it.isFinite() }, Arb.int(0..2000)) { label, driftBasisPoints ->
            val drift = driftBasisPoints / 10000.0
            val near = BindingConfidence.forSpan(label, label)
            val c = BindingConfidence.forSpan(label * (1 + drift), label)
            assertTrue(c in 0.0..BindingConfidence.ALIAS)
            assertTrue(c <= near)
            if (drift > BindingConfidence.MAX_SPAN_DISAGREEMENT + 1e-6) assertEquals(0.0, c)
        }
    }

    @Test
    fun nonPositiveInputsGiveZero() {
        assertEquals(0.0, BindingConfidence.forSpan(0.0, 10.0))
        assertEquals(0.0, BindingConfidence.forSpan(10.0, 0.0))
        assertNull(measureLabel("Ceiling Heights"))
        assertNull(measureLabel("Section 3"))
    }
}
