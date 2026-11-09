// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Test-only mutation flags (plan B.5d). Each one injects the failure a gate must catch, so every
 * gate has a repeatable red run. Selected with the instrumentation argument
 * `annotateMutation=<name>`; absent means no mutation. This object lives in androidInstrumentedTest
 * only: `scripts/check-no-mutations-in-main.sh` fails CI if a flag name reaches production sources.
 *
 * `scripts/bench-budget-check.js` holds the same name list; its node test fails if they drift.
 */
object Mutations {
    const val ARGUMENT = "annotateMutation"

    const val LOADER_PER_PRESS = "loaderPerPress"
    const val PER_DRAW_MEASURE = "perDrawMeasure"
    const val BUSY_WAIT_MOVE = "busyWaitMove"
    const val BACKGROUND_BUSY_LOOP = "backgroundBusyLoop"
    const val LEAK_STRONG_REF = "leakStrongRef"
    const val EXIT_ON_BACKGROUND = "exitOnBackground"
    const val LOUPE_FULL_DECODE = "loupeFullDecode"
    const val CACHE_NO_LIMIT = "cacheNoLimit"
    const val UNDO_UNBOUNDED = "undoUnbounded"
    const val OOM_INJECTION = "oomInjection"

    val ALL = listOf(
        LOADER_PER_PRESS, PER_DRAW_MEASURE, BUSY_WAIT_MOVE, BACKGROUND_BUSY_LOOP, LEAK_STRONG_REF,
        EXIT_ON_BACKGROUND, LOUPE_FULL_DECODE, CACHE_NO_LIMIT, UNDO_UNBOUNDED, OOM_INJECTION,
    )

    /** The active mutation, or null. An unknown name fails loudly so a typo cannot pass as "no mutation". */
    val active: String? by lazy {
        val name = InstrumentationRegistry.getArguments().getString(ARGUMENT)?.takeIf { it.isNotBlank() }
        require(name == null || name in ALL) { "unknown $ARGUMENT '$name'; expected one of $ALL" }
        name
    }

    fun isActive(name: String): Boolean = active == name
}
