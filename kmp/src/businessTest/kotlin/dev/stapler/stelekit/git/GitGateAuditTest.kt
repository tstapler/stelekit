// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A mutating [GitRepository] call must run under the repo gate. A call site passes when its enclosing function
 * takes the gate itself (`withExclusiveLease`, `tryAcquire`, `singleFlight`, ...); otherwise it needs a line in
 * `git-gate-allowlist.txt` (`path|function|method|reason`) saying which gated caller reaches it. The allow-list may
 * only shrink: a stale line fails, and [ALLOWLIST_CAP] is lowered with every removal (ADR-001, Task 1.8a).
 */
class GitGateAuditTest {
    data class Site(val path: String, val function: String, val method: String, val line: Int) {
        val key: String get() = "$path|$function|$method"
    }

    companion object {
        /** Lower this when an entry is removed; never raise it. */
        const val ALLOWLIST_CAP = 18

        val MUTATING = listOf(
            "add", "stageSubdir", "commit", "merge", "fetch", "push", "checkout", "checkoutFile", "reset",
            "abortMerge", "markResolved", "removeStaleLockFile", "forceRemoveLock", "clone", "unshallow",
        )
        private val CALL = Regex("""\b(?:gitRepository|gitRepo|repository)\s*\.\s*(${MUTATING.joinToString("|")})\s*\(""")
        private val GATE_MARKER = Regex("""withExclusiveLease|withLease|tryAcquire|singleFlight|\.acquire\(|withGate""")
        private val FUN_DECL = Regex("""^\s*(?:(?:private|internal|public|protected|override|suspend|inline|operator|tailrec)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(""")
        private val IN_SCOPE = listOf(
            "commonMain/dev/stapler/stelekit/git/", "commonMain/dev/stapler/stelekit/ui/", "commonMain/dev/stapler/stelekit/db/",
            "androidMain/dev/stapler/stelekit/git/", "jvmMain/", "androidApp.main/",
        )

        private fun indent(line: String) = line.length - line.trimStart().length

        private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

        /** Mutating calls that sit in a function whose own body never takes the gate. */
        fun ungatedSites(sources: Map<String, String>): List<Site> {
            val result = mutableListOf<Site>()
            for ((path, text) in sources) {
                if (IN_SCOPE.none { path.startsWith(it) } || path.substringAfterLast('/').endsWith("GitRepository.kt")) continue
                val lines = text.lines()
                lines.forEachIndexed { i, line ->
                    val method = CALL.find(line)?.groupValues?.get(1)
                    if (method == null || isCommentLine(line)) return@forEachIndexed
                    val declIdx = (i downTo 0).firstOrNull { j ->
                        FUN_DECL.containsMatchIn(lines[j]) && indent(lines[j]) < indent(line)
                    }
                    val name = declIdx?.let { FUN_DECL.find(lines[it])!!.groupValues[1] } ?: "<init>"
                    val body = if (declIdx == null) lines else {
                        val declIndent = indent(lines[declIdx])
                        val end = (declIdx + 1 until lines.size).firstOrNull { j ->
                            val l = lines[j]
                            l.isNotBlank() && indent(l) <= declIndent && !l.trimStart().startsWith(")")
                        } ?: lines.size - 1
                        lines.subList(declIdx, minOf(end + 1, lines.size))
                    }
                    if (body.none { GATE_MARKER.containsMatchIn(it) && !isCommentLine(it) }) {
                        result += Site(path, name, method, i + 1)
                    }
                }
            }
            return result
        }

        /** `path|function|method` keys of the allow-list; blank lines and `#` comments are skipped, a reason is required. */
        fun parseAllowlist(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                val parts = line.split('|').map { it.trim() }
                require(parts.size == 4 && parts[3].isNotEmpty()) { "git-gate-allowlist line needs path|function|method|reason: $line" }
                "${parts[0]}|${parts[1]}|${parts[2]}"
            }
    }

    private val allowlist: List<String> by lazy { parseAllowlist(WiringInputs.dataFile("git-gate-allowlist.txt")) }

    @Test
    fun mutatingGitCalls_should_BeGatedOrAllowListed() {
        val sources = WiringInputs.productionSources
        assertTrue(sources.keys.any { it.startsWith("commonMain/dev/stapler/stelekit/git/GitSyncService") }, "source loader returned no sync sources, so the audit would pass vacuously")
        val violations = ungatedSites(sources).filter { it.key !in allowlist }
            .map { "${it.path}:${it.line} ${it.function} calls ${it.method} outside a gated scope" }
        assertEquals(emptyList(), violations, "add the gate or a reasoned line to git-gate-allowlist.txt")
    }

    @Test
    fun allowlist_should_OnlyShrink() {
        val live = ungatedSites(WiringInputs.productionSources).map { it.key }.toSet()
        assertEquals(emptyList(), allowlist.filter { it !in live }, "stale allow-list entries (the call is gone or now gated): delete them")
        assertEquals(allowlist.size, allowlist.toSet().size, "duplicate allow-list entries")
        assertTrue(allowlist.size <= ALLOWLIST_CAP, "allow-list grew past the cap ${ALLOWLIST_CAP}: gate the new call instead")
    }

    @Test
    fun ungatedSites_should_ReportAPlantedBypassAndAcceptAGatedCall() {
        val planted = mapOf(
            "commonMain/dev/stapler/stelekit/ui/Bad.kt" to "class Bad {\n    suspend fun go() {\n        gitRepository.push(config)\n    }\n}\n",
            "commonMain/dev/stapler/stelekit/ui/Ok.kt" to "class Ok {\n    suspend fun go() {\n        gate.withExclusiveLease(k, o, e) {\n            gitRepository.push(config)\n        }\n    }\n}\n",
            "commonMain/dev/stapler/stelekit/ui/Cmt.kt" to "class C {\n    fun go() {\n        // gitRepository.push(config)\n    }\n}\n",
        )
        assertEquals(
            listOf("commonMain/dev/stapler/stelekit/ui/Bad.kt|go|push"),
            ungatedSites(planted).map { it.key },
        )
    }
}
