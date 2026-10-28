package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringAudit

/**
 * Rules shared by `TestPlacementAuditTest` and `TestHygieneAuditTest` (plan Story 1.4, pitfalls E4).
 * Both are text heuristics over test sources with comments and string literals stripped; they are
 * ratcheted: pre-existing violations live in a baseline file and may only shrink.
 */
object TestAuditRules {
    /** Test source sets present under both Gradle (disk) and Bazel (bundled resources). */
    val AUDITED_SETS = listOf("commonTest", "businessTest", "jvmTest", "androidUnitTest", "androidInstrumentedTest", "androidApp.test")

    fun audited(testSources: Map<String, String>): Map<String, String> =
        testSources.filterKeys { it.substringBefore('/') in AUDITED_SETS }

    // ---- placement ----

    private val COMPOSE_TEST_IMPORT = Regex("""(?m)^\s*import\s+androidx\.compose\.ui\.test""")
    private val JVM_TEST_REASON = Regex("""//\s*jvmTest-reason:\s*(pixels|desktop-only)\b""")

    /** `jvmTest` files that import the Compose test API without a `// jvmTest-reason:` comment. */
    fun placementViolations(testSources: Map<String, String>): List<String> =
        testSources.filterKeys { it.startsWith("jvmTest/") }
            .filter { (_, text) -> COMPOSE_TEST_IMPORT.containsMatchIn(text) && !JVM_TEST_REASON.containsMatchIn(text) }
            .keys.sorted()

    // ---- hygiene ----

    enum class Rule(val label: String) {
        THREAD_SLEEP("Thread.sleep"),
        GLOBAL_SCOPE("GlobalScope"),
        UNCANCELLED_IO_SCOPE("io-scope-without-aftertest-cancel"),
        REAL_CLOCK_DELAY("real-clock-delay"),
    }

    private val THREAD_SLEEP = Regex("""\bThread\s*\.\s*sleep\s*\(""")
    private val GLOBAL_SCOPE = Regex("""\bGlobalScope\b""")
    private val IO_SCOPE = Regex("""CoroutineScope\s*\([^\n]*Dispatchers\s*\.\s*IO""")
    private val AFTER_TEST = Regex("""@(?:AfterTest|After|AfterClass|AfterEach)\b""")
    private val CANCEL = Regex("""\.\s*cancel\s*\(|\bcancel\s*\(|\.close\s*\(\s*\)|\.shutdown""")
    private val DELAY = Regex("""(?<![\w.])delay\s*\(""")
    private val VIRTUAL_TIME = Regex("""\brunTest\b|\bTestScope\b|\bTestDispatcher\b|\btestScheduler\b|\bStandardTestDispatcher\b|\bUnconfinedTestDispatcher\b""")

    /** Per-file count of each hygiene [Rule] violated. Key: `path\trule`. */
    fun hygieneCounts(testSources: Map<String, String>): Map<String, Int> {
        val counts = sortedMapOf<String, Int>()
        for ((path, raw) in testSources) {
            val code = WiringAudit.stripCommentsAndStrings(raw)
            fun add(rule: Rule, n: Int) { if (n > 0) counts["$path\t${rule.label}"] = n }
            add(Rule.THREAD_SLEEP, THREAD_SLEEP.findAll(code).count())
            add(Rule.GLOBAL_SCOPE, GLOBAL_SCOPE.findAll(code).count())
            val ioScopes = IO_SCOPE.findAll(code).count()
            if (ioScopes > 0 && !hasAfterTestCancel(code)) add(Rule.UNCANCELLED_IO_SCOPE, ioScopes)
            if (!VIRTUAL_TIME.containsMatchIn(code)) add(Rule.REAL_CLOCK_DELAY, DELAY.findAll(code).count())
        }
        return counts
    }

    private fun hasAfterTestCancel(code: String): Boolean {
        val after = AFTER_TEST.find(code) ?: return false
        return CANCEL.containsMatchIn(code.substring(after.range.first))
    }

    fun parseBaseline(text: String): Map<String, Int> = text.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "baseline line must be path<TAB>rule<TAB>count: '$line'" }
            "${parts[0]}\t${parts[1]}" to parts[2].toInt()
        }

    fun renderBaseline(counts: Map<String, Int>): String = counts.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    /** Violations above the baseline (new) and baseline entries that are now too high (ratchet must tighten). */
    class Diff(val newViolations: List<String>, val tightenable: List<String>)

    fun diff(actual: Map<String, Int>, baseline: Map<String, Int>): Diff {
        val new = actual.filter { (k, n) -> n > (baseline[k] ?: 0) }.map { (k, n) -> "$k (now $n, baseline ${baseline[k] ?: 0})" }
        val tight = baseline.filter { (k, n) -> (actual[k] ?: 0) < n }.map { (k, n) -> "$k (baseline $n, now ${actual[k] ?: 0})" }
        return Diff(new, tight)
    }
}
