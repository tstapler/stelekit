// jvmTest-reason: desktop-only
package dev.stapler.stelekit.sketch.v2

import kotlin.test.Test
import kotlin.test.assertTrue

/** Guard for Spike 0.1.6: per-step snapshot time stays within the budget recorded in ADR-002. */
class SceneStructureBenchmarkGuard {
    private val base = SceneStructureBench.scene(16_000.0)

    // Budgets: ~10x (map copy) to ~75x (trie) the measured median (see ADR-002 "Spike 0.1.6 decision") so host noise does not flake.
    private fun medianUs(step: (Int) -> Unit): Double {
        repeat(300) { step(it) }
        val t = LongArray(SceneStructureBench.STEPS) { val t0 = System.nanoTime(); step(it); System.nanoTime() - t0 }
        t.sort(); return t[t.size / 2] / 1000.0
    }

    @Test
    fun hashMapCopySnapshotStaysWithinBudget() {
        var m: Map<String, BenchRect> = base.associateByTo(HashMap(10_000)) { it.id }
        val us = medianUs { i -> m = HashMap(m).also { it["e$i"] = m["e$i"]!!.moved(1.0) } }
        assertTrue(us < BUDGET_MAP_COPY_US, "HashMap copy snapshot median $us us exceeds budget $BUDGET_MAP_COPY_US us")
    }

    @Test
    fun persistentTrieSnapshotStaysWithinBudget() {
        var t = base.fold(PTrie.empty<BenchRect>()) { acc, r -> acc.put(r.id, r) }
        val us = medianUs { i -> t = t.put("e$i", t["e$i"]!!.moved(1.0)) }
        assertTrue(us < BUDGET_TRIE_US, "trie snapshot median $us us exceeds budget $BUDGET_TRIE_US us")
    }

    companion object {
        const val BUDGET_MAP_COPY_US = 2_000.0
        const val BUDGET_TRIE_US = 100.0
    }
}
