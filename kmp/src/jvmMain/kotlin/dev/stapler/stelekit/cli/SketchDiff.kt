// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cli

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2

/**
 * A textual diff of two sheets: both are described in words (`describe`, without the character map) and the lines are compared, so a
 * moved wall or a changed label shows as one `-` line and one `+` line an agent or a person can read. Deterministic.
 */
internal object SketchDiff {
    private const val CONTEXT_LINES = 3

    /** The middle (after the common head and tail) beyond this many lines on a side is shown as one replaced block, not aligned. */
    private const val ALIGN_BUDGET = 3_000

    private sealed interface Op {
        val line: String

        data class Same(override val line: String) : Op

        data class Del(override val line: String) : Op

        data class Add(override val line: String) : Op
    }

    /** Unified diff of the two sheets' descriptions, or an empty string when they describe the same. */
    fun sheets(a: SketchDocumentV2, b: SketchDocumentV2, labelA: String, labelB: String): String =
        unified(SketchDescribe.describe(a, withMap = false), SketchDescribe.describe(b, withMap = false), labelA, labelB)

    fun unified(a: String, b: String, labelA: String, labelB: String): String {
        val ops = script(a.trimEnd().lines(), b.trimEnd().lines())
        if (ops.none { it !is Op.Same }) return ""
        val out = StringBuilder("--- $labelA\n+++ $labelB\n")
        for (hunk in hunks(ops)) {
            val first = hunk.first()
            val aStart = ops.take(first).count { it !is Op.Add } + 1
            val bStart = ops.take(first).count { it !is Op.Del } + 1
            val slice = ops.subList(first, hunk.last() + 1)
            out.append("@@ -$aStart,${slice.count { it !is Op.Add }} +$bStart,${slice.count { it !is Op.Del }} @@\n")
            for (op in slice) {
                out.append(
                    when (op) {
                        is Op.Same -> " "
                        is Op.Del -> "-"
                        is Op.Add -> "+"
                    },
                ).append(op.line).append('\n')
            }
        }
        return out.toString()
    }

    /** Index ranges of the changed ops, each widened by [CONTEXT_LINES] and merged when they touch. */
    private fun hunks(ops: List<Op>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        for ((i, op) in ops.withIndex()) {
            if (op is Op.Same) continue
            val lo = maxOf(0, i - CONTEXT_LINES)
            val hi = minOf(ops.lastIndex, i + CONTEXT_LINES)
            val last = ranges.lastOrNull()
            if (last != null && lo <= last.last + 1) ranges[ranges.lastIndex] = last.first..maxOf(last.last, hi) else ranges += lo..hi
        }
        return ranges
    }

    private fun script(a: List<String>, b: List<String>): List<Op> {
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val midA = a.subList(head, a.size - tail)
        val midB = b.subList(head, b.size - tail)
        val ops = ArrayList<Op>()
        a.take(head).forEach { ops += Op.Same(it) }
        ops += if (midA.size > ALIGN_BUDGET || midB.size > ALIGN_BUDGET) midA.map(Op::Del) + midB.map(Op::Add) else align(midA, midB)
        a.takeLast(tail).forEach { ops += Op.Same(it) }
        return ops
    }

    /** Longest-common-subsequence alignment of the two middles; ties prefer deleting first, so a changed line reads `-` then `+`. */
    private fun align(a: List<String>, b: List<String>): List<Op> {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val ops = ArrayList<Op>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> ops += Op.Same(a[i++]).also { j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> ops += Op.Del(a[i++])
                else -> ops += Op.Add(b[j++])
            }
        }
        while (i < a.size) ops += Op.Del(a[i++])
        while (j < b.size) ops += Op.Add(b[j++])
        return ops
    }
}
