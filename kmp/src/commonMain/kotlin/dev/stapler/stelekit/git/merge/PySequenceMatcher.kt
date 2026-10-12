// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

/** One `difflib.SequenceMatcher.get_opcodes()` entry; ranges are half-open `[i1, i2)` into a, `[j1, j2)` into b. */
internal data class PyOpcode(val tag: Tag, val i1: Int, val i2: Int, val j1: Int, val j2: Int) {
    enum class Tag { Equal, Replace, Delete, Insert }
}

/**
 * Port of CPython's `difflib.SequenceMatcher(None, a, b)` (default `isjunk=None`, `autojunk=True`).
 *
 * Not interchangeable with [diff] (an LCS): difflib's longest-block-first heuristic and its
 * autojunk "popular element" purge (only when `b.size >= 200`) produce different alignments, and
 * [LogseqUnionMergeDriver] must reproduce the python driver's output byte for byte.
 */
internal class PySequenceMatcher(private val a: List<String>, private val b: List<String>) {
    private val b2j: Map<String, List<Int>> = buildB2j()

    private fun buildB2j(): Map<String, List<Int>> {
        val map = HashMap<String, MutableList<Int>>()
        b.forEachIndexed { i, elt -> map.getOrPut(elt) { ArrayList() }.add(i) }
        if (b.size >= AUTOJUNK_MIN_SIZE) {
            val ntest = b.size / 100 + 1
            map.entries.removeAll { it.value.size > ntest }
        }
        return map
    }

    private data class Match(val i: Int, val j: Int, val size: Int)

    // With isjunk=None `bjunk` is empty, so only the plain (non-junk) extension loops of the
    // original can fire; popular elements still extend a match, they just never seed one.
    private fun findLongestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Match {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = k
                if (k > bestsize) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    bestsize = k
                }
            }
            j2len = newj2len
        }
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--
            bestj--
            bestsize++
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) {
            bestsize++
        }
        return Match(besti, bestj, bestsize)
    }

    private fun matchingBlocks(): List<Match> {
        val la = a.size
        val lb = b.size
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, la, 0, lb))
        val found = mutableListOf<Match>()
        while (queue.isNotEmpty()) {
            val range = queue.removeLast()
            val alo = range[0]
            val ahi = range[1]
            val blo = range[2]
            val bhi = range[3]
            val m = findLongestMatch(alo, ahi, blo, bhi)
            if (m.size > 0) {
                found.add(m)
                if (alo < m.i && blo < m.j) queue.addLast(intArrayOf(alo, m.i, blo, m.j))
                if (m.i + m.size < ahi && m.j + m.size < bhi) {
                    queue.addLast(intArrayOf(m.i + m.size, ahi, m.j + m.size, bhi))
                }
            }
        }
        found.sortWith(compareBy<Match>({ it.i }, { it.j }, { it.size }))
        val collapsed = mutableListOf<Match>()
        var i1 = 0
        var j1 = 0
        var k1 = 0
        for (m in found) {
            if (i1 + k1 == m.i && j1 + k1 == m.j) {
                k1 += m.size
            } else {
                if (k1 > 0) collapsed.add(Match(i1, j1, k1))
                i1 = m.i
                j1 = m.j
                k1 = m.size
            }
        }
        if (k1 > 0) collapsed.add(Match(i1, j1, k1))
        collapsed.add(Match(la, lb, 0))
        return collapsed
    }

    fun opcodes(): List<PyOpcode> {
        var i = 0
        var j = 0
        val answer = mutableListOf<PyOpcode>()
        for (block in matchingBlocks()) {
            val tag = when {
                i < block.i && j < block.j -> PyOpcode.Tag.Replace
                i < block.i -> PyOpcode.Tag.Delete
                j < block.j -> PyOpcode.Tag.Insert
                else -> null
            }
            if (tag != null) answer.add(PyOpcode(tag, i, block.i, j, block.j))
            i = block.i + block.size
            j = block.j + block.size
            if (block.size > 0) answer.add(PyOpcode(PyOpcode.Tag.Equal, i - block.size, i, j - block.size, j))
        }
        return answer
    }

    private companion object {
        const val AUTOJUNK_MIN_SIZE = 200
    }
}
