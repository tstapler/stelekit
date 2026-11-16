// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

/** Thrown inside the importer to unwind to the parse boundary, which turns it into an `Either.Left`. */
internal class ImportAbort(val error: ImportError) : Exception(error.reason.name)

/**
 * Cooperative time cap for a single-threaded wasmJs runtime (Story 5.1.1, engineering gap 11). The importer
 * calls [tick] as it works; every [YIELD_BYTES] bytes or [YIELD_ELEMENTS] elements the budget yields to the event
 * loop and then checks the deadline, so a long import neither starves the page nor outlives `maxParseMillis`.
 * A cancelled coroutine stops at the next yield (`yield()` rethrows the cancellation).
 *
 * [clock] returns milliseconds since an arbitrary start; [yielder] is injectable so a test can advance a fake
 * clock on every yield. The `max*` properties record the worst gaps for the budget tests.
 */
class ParseBudget(
    private val clock: () -> Long,
    private val deadlineMillis: Long,
    private val yielder: suspend () -> Unit = { yield() },
) {
    private var bytesSinceYield = 0L
    private var elementsSinceYield = 0
    private var lastYieldAt = clock()

    var yieldCount = 0
        private set
    var maxBytesBetweenYields = 0L
        private set
    var maxElementsBetweenYields = 0
        private set
    var maxGapMillis = 0L
        private set

    /** Accounts for [bytes] scanned/decoded and [elements] processed, yielding when a slice is used up. */
    internal suspend fun tick(bytes: Long, elements: Int = 0) {
        bytesSinceYield += bytes
        elementsSinceYield += elements
        if (bytesSinceYield >= YIELD_BYTES || elementsSinceYield >= YIELD_ELEMENTS) checkpoint()
    }

    /** Yields now and fails with `ImportTimeout` when the deadline has passed. */
    internal suspend fun checkpoint() {
        if (bytesSinceYield > maxBytesBetweenYields) maxBytesBetweenYields = bytesSinceYield
        if (elementsSinceYield > maxElementsBetweenYields) maxElementsBetweenYields = elementsSinceYield
        bytesSinceYield = 0
        elementsSinceYield = 0
        yielder()
        yieldCount++
        val now = clock()
        if (now - lastYieldAt > maxGapMillis) maxGapMillis = now - lastYieldAt
        lastYieldAt = now
        if (now >= deadlineMillis) {
            throw ImportAbort(ImportError(ImportReason.ImportTimeout, "import exceeded its time budget", deadlineMillis))
        }
    }

    companion object {
        /** Input bytes between two yields (64 KB). */
        const val YIELD_BYTES = 64L * 1024

        /** Elements between two yields. */
        const val YIELD_ELEMENTS = 256

        /** A budget on the monotonic clock with the `maxParseMillis` deadline from [limits]. */
        fun start(limits: SceneLimits = SceneLimits.Default): ParseBudget {
            val mark = TimeSource.Monotonic.markNow()
            return ParseBudget({ mark.elapsedNow().inWholeMilliseconds }, limits.maxParseMillis)
        }
    }
}
