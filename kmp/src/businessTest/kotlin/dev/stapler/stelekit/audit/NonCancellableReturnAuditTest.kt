// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `return@withContext` inside a `withContext(NonCancellable) { }` block binds to the inner lambda, so
 * the pipeline falls through to the next step (push after a conflict). The merge unit is extracted instead.
 */
class NonCancellableReturnAuditTest {
    companion object {
        private const val OPENER = "withContext(NonCancellable)"

        /** Bodies of every `withContext(NonCancellable) { ... }` block, by brace matching. */
        fun nonCancellableBlocks(text: String): List<String> {
            val blocks = mutableListOf<String>()
            var from = text.indexOf(OPENER)
            while (from >= 0) {
                val open = text.indexOf('{', from + OPENER.length)
                if (open < 0) break
                var depth = 0
                var i = open
                while (i < text.length) {
                    when (text[i]) {
                        '{' -> depth++
                        '}' -> if (--depth == 0) break
                    }
                    i++
                }
                blocks += text.substring(open, minOf(i + 1, text.length))
                from = text.indexOf(OPENER, from + OPENER.length)
            }
            return blocks
        }

        fun violations(text: String): List<String> =
            nonCancellableBlocks(text).filter { "return@withContext" in it }
    }

    @Test
    fun gitSyncService_should_HaveNoReturnAtWithContextInsideNonCancellableBlock() {
        val text = WiringInputs.productionSources.getValue("commonMain/dev/stapler/stelekit/git/GitSyncService.kt")

        assertTrue(nonCancellableBlocks(text).isNotEmpty(), "an empty scan would pass vacuously")
        assertEquals(emptyList(), violations(text))
    }

    @Test
    fun audit_should_FlagAReturnAtWithContext_When_PlantedInsideANonCancellableBlock() {
        val planted = "x { withContext(NonCancellable) { if (a) { return@withContext 1 } } }\n" +
            "withContext(IO) { return@withContext 2 }"

        assertEquals(1, violations(planted).size)
        assertEquals(0, violations("withContext(NonCancellable) { f() }\nwithContext(IO) { return@withContext 2 }").size)
    }
}
