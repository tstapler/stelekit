// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import dev.stapler.stelekit.table.formula.TableCalc
import dev.stapler.stelekit.table.formula.TableEngine
import dev.stapler.stelekit.ui.state.BlockUndoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Scenario `table-commit-200x12`: one single-cell commit (apply edit, incremental evaluate, serialize) on a 200x12 table
 * with a total formula per column. Time is recorded, never gated (relative baseline only); the counters are deterministic
 * and asserted. `UnifiedBenchmarkRunner` writes [run]'s metrics into the same JSON as the other scenarios.
 */
class TableCommitBenchmarkTest {

    @Test
    fun commitScenario_should_EvaluateOneDependent_AndDirtyOneLine() {
        val m = run()
        println("[TableCommitBenchmark] $m")
        assertEquals(1.0, m.getValue("evaluation_count"))
        assertEquals(1.0, m.getValue("serialized_dirty_lines"))
    }

    /** Memory of 100 undo entries holding 200x12 snapshots; recorded, not asserted (JVM heap readings drift). */
    @Test
    fun undoSnapshots_should_BeMeasured_ForOneHundredEntries() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sizeChars = content(0).length
            val before = usedHeapBytes()
            val manager = BlockUndoManager(scope, maxHistory = 100)
            var prev = content(0)
            repeat(100) { i ->
                val next = content(i + 1)
                val old = prev
                manager.record(undo = { old.length }, redo = { next.length })
                prev = next
            }
            val after = usedHeapBytes()
            println("[TableCommitBenchmark] 100 undo entries x ${sizeChars} chars: retained ~${(after - before) / 1024} KiB (heap delta, noisy); analytic ~${100L * 2 * sizeChars / 1024} KiB at 2 bytes/char")
            assertNotNull(manager)
        } finally {
            scope.cancel()
        }
    }

    companion object {
        private const val BODY = 200
        private const val COLS = 12

        fun content(variant: Int): String = buildString {
            append((1..COLS).joinToString(" | ", "| ", " |") { "H$it" })
            append("\n").append((1..COLS).joinToString("|", "|", "|") { "---" })
            for (r in 1 until BODY) append("\n").append((1..COLS).joinToString(" | ", "| ", " |") { c -> "${r * c + (if (r == 57 && c == 5) variant else 0)}" })
            append("\n").append((1..COLS).joinToString(" | ", "| ", " |") { c -> "=SUM(${'A' + c - 1}2:${'A' + c - 1}$BODY)" })
        }

        private fun usedHeapBytes(): Long {
            repeat(3) { System.gc() }
            val rt = Runtime.getRuntime()
            return rt.totalMemory() - rt.freeMemory()
        }

        /** Median of 200 commits, with the counters of the last one. */
        fun run(): Map<String, Double> {
            val text = content(0)
            val doc = (TableParser.parse(text) as TableParseResult.Parsed).doc
            val editor = TableEngine.editor()
            val times = ArrayList<Long>(200)
            var evaluations = 0
            var dirty = 0
            repeat(200) { i ->
                val calc = assertNotNull(TableCalc.orNull(doc))
                val before = calc.evaluationCount
                val start = System.nanoTime()
                val edit = TableEdit.SetCell(CellAddress(57, 5), CellText("${1000 + i}"))
                val applied = assertNotNull(editor.apply(doc, edit) as? TableEditOutcome.Applied)
                calc.update(applied.doc, edit)
                val out = TableSerializer.serialize(applied.doc).split("\n")
                times += System.nanoTime() - start
                evaluations = calc.evaluationCount - before
                val original = text.split("\n")
                dirty = out.indices.count { out[it] != original.getOrNull(it) }
            }
            times.sort()
            return mapOf(
                "commit_ms" to times[times.size / 2] / 1_000_000.0,
                "evaluation_count" to evaluations.toDouble(),
                "serialized_dirty_lines" to dirty.toDouble(),
            )
        }
    }
}
