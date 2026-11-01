package dev.stapler.stelekit.wiring

/**
 * `wiring-catalog.txt` check (ADR-002 item 4). Line format `Seam  EntryPointTest  effect:<label>`.
 * A line passes only when the named test exists, drives the real stack (a `WiredAppFixture.` call or
 * a platform root factory, with a `GraphContent*Setup` / `rememberGraphContent*` reference), and then
 * calls `ObservableEffect.assert("<label>") { ... }` with a body that asserts runtime state. Comments
 * and string literals are stripped first, so a comment that mentions the stack does not satisfy it.
 *
 * Static limit: this proves the test is shaped to observe an effect; whether the effect really
 * happens is proven by running the test (`PlantedDeadCodeConstructorTest`).
 */
object WiringCatalog {
    private val SEPARATOR = Regex("""\s{2,}""")
    private val FIXTURE_CALL = Regex("""\b(?:Desktop)?WiredAppFixture\s*\.\s*\w+\s*\(""")
    private val ROOT_FACTORY_CALL = Regex("""\b(?:platformCaps|defaultMeasurementDeviceFactories|defaultAssetPlugins)\s*\(""")
    private val STACK_REFERENCE = Regex("""GraphContent\w*Setup|rememberGraphContent\w+""")
    private val STATE_ASSERTION = Regex("""\bassert\w*\s*\(|\bcheck\s*\(|\brequire\s*\(|==|!=|>=|<=|[<>]""")
    private val CLASS_REFERENCE = Regex("""::class|\bjavaClass\b|Class\.forName""")

    class Line(val seam: String, val testClass: String, val effect: String?, val raw: String)

    fun parse(text: String): List<Line> = text.lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { raw ->
            val parts = raw.split(SEPARATOR)
            val effect = parts.getOrNull(2)?.takeIf { it.startsWith("effect:") }?.removePrefix("effect:")?.trim()
            Line(parts[0], parts.getOrElse(1) { "" }, effect?.takeIf { it.isNotEmpty() }, raw)
        }

    fun check(catalogText: String, testSources: Map<String, String>, seams: List<String> = WiringAudit.SEAMS): List<String> {
        val violations = mutableListOf<String>()
        for (line in parse(catalogText)) {
            if (line.seam !in seams) violations += "catalog line names an unknown seam: '${line.raw}'"
            if (line.testClass.isEmpty()) {
                violations += "catalog line has no entry-point test: '${line.raw}'"
                continue
            }
            val label = line.effect
            if (label == null) {
                violations += "catalog line without effect:<label>: '${line.raw}'"
                continue
            }
            val source = testSources.entries.firstOrNull { it.key.endsWith("/${line.testClass}.kt") }?.value
            if (source == null) {
                violations += "${line.seam}: entry-point test ${line.testClass} does not exist"
                continue
            }
            violations += checkEntryPointTest(line.seam, line.testClass, label, WiringAudit.stripCommentsAndStrings(source), source)
        }
        return violations
    }

    private fun checkEntryPointTest(seam: String, testClass: String, label: String, code: String, rawSource: String): List<String> {
        val v = mutableListOf<String>()
        val fixture = FIXTURE_CALL.find(code) ?: ROOT_FACTORY_CALL.find(code)
        if (fixture == null) v += "$seam: $testClass never calls WiredAppFixture. or a platform root factory"
        if (!STACK_REFERENCE.containsMatchIn(code) && !ROOT_FACTORY_CALL.containsMatchIn(code)) {
            v += "$seam: $testClass does not reference a GraphContent*Setup stack"
        }
        // Labels are string literals, which the stripped copy blanks, so locate the call there and read the label from the raw text.
        val callAt = Regex("""\bObservableEffect\s*\.\s*assert\s*\(""").findAll(code)
            .map { it.range.first }
            .firstOrNull { Regex("""ObservableEffect\s*\.\s*assert\s*\(\s*"${Regex.escape(label)}"""").matchAt(rawSource, it) != null }
        if (callAt == null) {
            v += "$seam: $testClass never calls ObservableEffect.assert(\"$label\")"
            return v
        }
        if (fixture != null && callAt < fixture.range.first) {
            v += "$seam: $testClass asserts effect '$label' before it invokes the real root factory"
        }
        val body = lambdaBody(rawSource, rawSource.indexOf('{', callAt))
        if (CLASS_REFERENCE.containsMatchIn(body) || !STATE_ASSERTION.containsMatchIn(WiringAudit.stripCommentsAndStrings(body))) {
            v += "$seam: $testClass effect '$label' must assert runtime state (row count, log line), not a class reference"
        }
        return v
    }

    private fun lambdaBody(src: String, open: Int): String {
        if (open < 0) return ""
        var depth = 0
        for (i in open until src.length) {
            if (src[i] == '{') depth++
            if (src[i] == '}' && --depth == 0) return src.substring(open + 1, i)
        }
        return src.substring(open + 1)
    }
}
