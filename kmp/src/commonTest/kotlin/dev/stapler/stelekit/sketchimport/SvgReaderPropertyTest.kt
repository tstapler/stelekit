// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SvgReaderPropertyTest {
    private val base = """<svg width="9" height="9"><g transform="translate(1,2) scale(3)"><path d="M0,0 L1,1 H4 V5 Z"/>
        <rect x="1" y="1" width="2" height="2"/></g><text x="1" y="2">12"</text></svg>"""

    @Test
    fun mutatedInputNeverThrows() = runTest {
        checkAll(200, Arb.int(0..base.length), Arb.int(0..40), Arb.string(0..6)) { start, len, insert ->
            val end = minOf(base.length, start + len)
            val mutated = base.substring(0, start) + insert + base.substring(end)
            val doc = readSvg(mutated)
            assertTrue(doc.elementsVisited <= mutated.length)
        }
    }

    @Test
    fun arbitraryStringsNeverThrow() = runTest {
        checkAll(200, Arb.string(0..80)) { s -> readSvg(s) }
    }
}
