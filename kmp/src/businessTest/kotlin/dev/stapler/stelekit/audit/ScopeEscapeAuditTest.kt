// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U-E2-16: a `rememberCoroutineScope()` value must not flow into an object built inside `remember { }`; Compose
 * cancels that scope on leaving composition and the object would throw `ForgottenCoroutineScopeException` (CLAUDE.md).
 * Textual approximation (a `.name` member access is not the variable): per file, collect the names bound to `rememberCoroutineScope()`, then flag a `remember {`
 * block whose body mentions one of them. Existing offenders are listed in [BASELINE] and may only shrink.
 */
class ScopeEscapeAuditTest {
    companion object {
        /** `path` entries that predate this audit; delete an entry when its file is fixed, never add one. */
        val BASELINE: Set<String> = emptySet()

        private val SCOPE_BINDING = Regex("""\bval\s+(\w+)\s*=\s*rememberCoroutineScope\s*\(\s*\)""")
        private val REMEMBER_OPEN = Regex("""\bremember\s*(?:\([^)]*\))?\s*\{""")

        /** Body of the brace block opening at [open] (index of `{`), or the rest of the text if unbalanced. */
        private fun block(text: String, open: Int): String {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return text.substring(open, i + 1)
                }
            }
            return text.substring(open)
        }

        fun escapes(text: String): List<String> {
            val scopes = SCOPE_BINDING.findAll(text).map { it.groupValues[1] }.toSet()
            if (scopes.isEmpty()) return emptyList()
            return REMEMBER_OPEN.findAll(text).flatMap { m ->
                val body = block(text, m.range.last)
                scopes.filter { Regex("""(?<![.\w])$it\b""").containsMatchIn(body) }
            }.toList()
        }

        fun violations(sources: Map<String, String>): List<String> =
            sources.filter { escapes(it.value).isNotEmpty() }.keys.sorted()
    }

    @Test
    fun rememberCoroutineScope_should_NotFlowIntoRememberedConstructors_InNewCode() {
        val sources = WiringInputs.productionSources
        assertTrue(sources.values.any { "rememberCoroutineScope()" in it }, "no rememberCoroutineScope() site found, so the audit would pass vacuously")
        val found = violations(sources)
        assertEquals(emptyList(), found.filter { it !in BASELINE }, "rememberCoroutineScope() value passed into remember { }")
        assertEquals(emptyList(), BASELINE.filter { it !in found }, "stale BASELINE entries: delete them")
    }

    @Test
    fun escapes_should_FlagAScopePassedToRememberedClass_AndAllowTransientUse() {
        val bad = "val scope = rememberCoroutineScope()\nval m = remember { SomeManager(scope) }\n"
        val ok = "val scope = rememberCoroutineScope()\nval m = remember { SomeManager() }\nButton(onClick = { scope.launch { } })\n"
        assertEquals(listOf("scope"), escapes(bad))
        assertEquals(emptyList(), escapes(ok))
    }
}
