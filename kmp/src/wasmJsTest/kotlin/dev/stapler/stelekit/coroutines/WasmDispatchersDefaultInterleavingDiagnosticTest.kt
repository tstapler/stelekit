package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Throwaway diagnostic (plan.md Task 0.1.3f, pre-mortem.md P1-5). Empirically checks whether
 * real wasmJs `Dispatchers.Default` exhibits true concurrent interleaving (lost updates) on an
 * unguarded shared `var`, or only macrotask-granularity *scheduling* nondeterminism without
 * true multi-core tearing. May be deleted once its result is recorded in ADR-019's Consequences
 * section — see that section for the reconciling note this test's result closes.
 *
 * Deliberately NOT run via `kotlinx-coroutines-test`'s `TestScope`/`runTest` — that scheduler is
 * single-threaded/virtual-time on every platform and cannot exercise real `Dispatchers.Default`
 * behavior even in principle (that is exactly why this file must run under a real
 * `wasmJsBrowserTest` browser execution, not just compile). This is a plain `suspend` test
 * function so kotlin-test dispatches it onto the real runtime, not a virtual-time scheduler.
 */
class WasmDispatchersDefaultInterleavingDiagnosticTest {

    @Test
    suspend fun diagnoseDispatchersDefaultInterleaving() {
        val results = mutableListOf<Int>()
        repeat(5) {
            var counter = 0
            coroutineScope {
                launch(Dispatchers.Default) { repeat(1000) { counter++ } }
                launch(Dispatchers.Default) { repeat(1000) { counter++ } }
            }
            results.add(counter)
        }

        val lostUpdatesObserved = results.any { it != 2000 }
        println(
            "WasmDispatchersDefaultInterleavingDiagnosticTest: counters=$results " +
                "(lostUpdatesObserved=$lostUpdatesObserved) — record this in ADR-019's Consequences section.",
        )

        // Deliberately not a hard correctness assertion either way — this test exists to
        // *observe* whether real wasmJs Dispatchers.Default tears an unguarded shared var, not
        // to gate CI on the answer. It only fails if the run itself is broken (e.g. counter never
        // reaches a sane value at all), never on the interleaving question itself.
        assertTrue(results.all { it in 0..2000 }, "counter must never exceed the total increments issued: $results")
    }
}
