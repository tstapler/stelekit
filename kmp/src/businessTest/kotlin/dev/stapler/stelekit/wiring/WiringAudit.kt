package dev.stapler.stelekit.wiring

import java.time.LocalDate

/**
 * Pure logic behind `WiringCompletenessTest` (ADR-002): finds every implementation of a
 * [WiringAudit.SEAMS] interface in production sources and checks that some composition-root file
 * constructs it, applying the expiring [AllowEntry] allowlist.
 *
 * Limit (stated, ADR-002): this is a text match. A constructor that exists only inside dead code
 * (a never-called function of a root) still counts as "constructed". The second layer that
 * catches that is the observable-effect catalog ([WiringCatalog]); see `PlantedDeadCodeConstructorTest`.
 * It also does not follow reflection or `Name::class` factories, and a root that builds the class
 * through a typealias is not seen.
 */
object WiringAudit {
    /** Identical to the plan Glossary's `WiringSeam` list (UT-149). `Plugin` is not a seam. */
    val SEAMS = listOf(
        "BackgroundSyncScheduler",
        "AssetPipelinePlugin",
        "ConsoleCommand",
        "MeasurementDeviceFactory",
        "WaybackMachineService",
        "CameraProvider",
    )

    const val MAX_RENEWALS = 2
    const val EXPIRY_DAYS = 90L

    enum class Kind { CLASS, OBJECT }

    data class Impl(val name: String, val file: String, val kind: Kind)

    private val DECLARATION = Regex(
        """(?m)^[ \t]*((?:(?:public|private|internal|protected|abstract|open|final|sealed|data|inner|enum|""" +
            """annotation|inline|value|expect|actual|fun)[ \t]+)*)(class|object|interface)[ \t]+(\w+)""",
    )
    private val HEADER_END = Regex(
        """\n[ \t]*\n|\n[ \t]*(?:@|class|interface|object|fun|val|var|private|internal|public|abstract|open|""" +
            """sealed|data|enum|typealias|import|package)\b""",
    )

    /** Blanks comments and string-literal contents, keeping offsets and line breaks. */
    fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> {
                    while (i < n && src[i] != '\n') { out.append(' '); i++ }
                }
                src.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < n) {
                        if (src.startsWith("/*", i)) { depth++; out.append("  "); i += 2 }
                        else if (src.startsWith("*/", i)) { depth--; out.append("  "); i += 2; if (depth == 0) break }
                        else { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                    }
                }
                src.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\""); i += 3
                    while (i < n && !src.startsWith("\"\"\"", i)) { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
                    if (i < n) { out.append("\"\"\""); i += 3 }
                }
                c == '"' -> {
                    out.append('"'); i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < n) { out.append("  "); i += 2 } else { out.append(' '); i++ }
                    }
                    if (i < n && src[i] == '"') { out.append('"'); i++ }
                }
                c == '\'' && i + 2 < n && (src[i + 2] == '\'' || (src[i + 1] == '\\' && i + 3 < n)) -> {
                    val end = src.indexOf('\'', i + 2).takeIf { it > 0 } ?: (i + 2)
                    repeat(end - i + 1) { out.append(' ') }
                    i = end + 1
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private class Decl(val name: String, val file: String, val kind: String, val modifiers: List<String>, val supers: Set<String>)

    private fun skipBalanced(t: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        while (i < t.length) {
            if (t[i] == open) depth++
            else if (t[i] == close) { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return t.length
    }

    private fun skipWs(t: String, from: Int): Int {
        var i = from
        while (i < t.length && t[i].isWhitespace()) i++
        return i
    }

    /** Top-level supertype simple names of the declaration starting at [afterName]. */
    private fun supertypes(t: String, afterName: Int): Set<String> {
        var i = skipWs(t, afterName)
        if (i < t.length && t[i] == '<') i = skipWs(t, skipBalanced(t, i, '<', '>'))
        if (i < t.length && t[i] == '(') i = skipWs(t, skipBalanced(t, i, '(', ')'))
        if (i >= t.length || t[i] != ':') return emptySet()
        i++
        val sb = StringBuilder()
        var angle = 0
        var paren = 0
        while (i < t.length) {
            val c = t[i]
            if (angle == 0 && paren == 0) {
                if (c == '{' || c == '=') break
                if (c == '\n' && HEADER_END.matchesAt(t, i)) break
            }
            when (c) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                else -> if (angle == 0 && paren == 0) sb.append(c)
            }
            i++
        }
        return sb.toString().split(',', ' ', '\n', '\t')
            .map { it.trim().substringAfterLast('.') }
            .filter { it.isNotEmpty() && it[0].isUpperCase() }
            .toSet()
    }

    private fun declarations(file: String, source: String): List<Decl> {
        val code = stripCommentsAndStrings(source)
        return DECLARATION.findAll(code).map { m ->
            Decl(
                name = m.groupValues[3],
                file = file,
                kind = m.groupValues[2],
                modifiers = m.groupValues[1].split(' ', '\t').filter { it.isNotEmpty() },
                supers = supertypes(code, m.range.last + 1),
            )
        }.toList()
    }

    /** Names of every class/object/interface declared in [sources]. */
    fun declaredNames(sources: Map<String, String>): Set<String> =
        sources.flatMap { (f, s) -> declarations(f, s) }.map { it.name }.toSet()

    /**
     * Concrete classes/objects that implement a seam directly or through another declared type.
     * Interfaces, abstract and sealed types are not constructed, so they are excluded.
     */
    fun findImplementations(sources: Map<String, String>, seams: List<String> = SEAMS): List<Impl> {
        val decls = sources.flatMap { (f, s) -> declarations(f, s) }
        val byName = decls.groupBy { it.name }
        fun implements(name: String, seen: Set<String>): Boolean =
            byName[name].orEmpty().any { d ->
                d.supers.any { it in seams } ||
                    d.supers.any { it !in seen && implements(it, seen + name) }
            }
        return decls
            .filter { it.kind != "interface" && "abstract" !in it.modifiers && "sealed" !in it.modifiers }
            .filter { it.name !in seams && implements(it.name, setOf(it.name)) }
            .map { Impl(it.name, it.file, if (it.kind == "object") Kind.OBJECT else Kind.CLASS) }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    /** True when [rootsText] (comment/string stripped) constructs [impl]. */
    fun isConstructed(impl: Impl, strippedRoots: Collection<String>): Boolean {
        val pattern = when (impl.kind) {
            Kind.CLASS -> Regex("""\b${impl.name}\s*(?:<[^>]*>\s*)?\(""")
            Kind.OBJECT -> Regex("""\b${impl.name}\b""")
        }
        return strippedRoots.any { pattern.containsMatchIn(it) }
    }

    // ---- allowlist ----

    data class AllowEntry(val cls: String, val reason: String, val date: LocalDate?, val renewals: Int) {
        val reasonClass: String get() = reason.substringBefore(' ')
        val isNeverExpiring: Boolean get() = date == null
        val expiry: LocalDate? get() = date?.plusDays(EXPIRY_DAYS)
        val item: String get() = reason.removePrefix("awaiting-unit").removePrefix("awaiting").trim()
    }

    class ParseResult(val entries: List<AllowEntry>, val errors: List<String>)

    private val NAMES_OPEN_ITEM = Regex("""awaiting-unit U\d+|awaiting OD-[1-6]|awaiting pass 12\.\d+[a-z]?|blocked-on-owner""")
    private val OWNER_ITEM = Regex("""OD-[1-6]|pass 12\.\d""")

    /** `ClassName  reason  YYYY-MM-DD|never  [renewals=N]`, fields separated by two or more spaces. */
    fun parseAllowlist(text: String): ParseResult {
        val entries = mutableListOf<AllowEntry>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(Regex("""\s{2,}""")).toMutableList()
            var renewals = 0
            if (parts.last().startsWith("renewals=")) {
                renewals = parts.removeAt(parts.lastIndex).removePrefix("renewals=").toIntOrNull()
                    ?: run { errors += "line ${idx + 1}: bad renewals in '$line'"; 0 }
            }
            if (parts.size != 3) {
                errors += "line ${idx + 1}: expected 'Class  reason  date  [renewals=N]', got '$line'"
                return@forEachIndexed
            }
            val date = if (parts[2] == "never") null else runCatching { LocalDate.parse(parts[2]) }.getOrElse {
                errors += "line ${idx + 1}: bad date '${parts[2]}' in '$line'"
                return@forEachIndexed
            }
            entries += AllowEntry(parts[0], parts[1], date, renewals)
        }
        return ParseResult(entries, errors)
    }

    /** Violations inside the allowlist itself, independent of what the sources contain. */
    fun checkAllowlistEntries(entries: List<AllowEntry>, today: LocalDate): List<String> {
        val v = mutableListOf<String>()
        entries.groupBy { it.cls }.filterValues { it.size > 1 }.keys.forEach { v += "duplicate allowlist entry: $it" }
        for (e in entries) {
            when (e.reasonClass) {
                "out-of-scope-ios" ->
                    if (!e.isNeverExpiring || e.renewals != 0) v += "${e.cls}: out-of-scope-ios must be dated 'never' with no renewals"
                "blocked-on-owner" -> {
                    if (e.isNeverExpiring) v += "${e.cls}: blocked-on-owner records the day it was set, not 'never'"
                    if (e.renewals != 0) v += "${e.cls}: blocked-on-owner takes no renewals"
                    if (!OWNER_ITEM.containsMatchIn(e.reason)) {
                        v += "${e.cls}: blocked-on-owner must name OD-1..OD-6 or a 'pass 12.n' item, got '${e.reason}'"
                    }
                }
                else -> {
                    if (e.isNeverExpiring) {
                        v += "${e.cls}: only out-of-scope-ios may be dated 'never'"
                    } else {
                        if (e.renewals > MAX_RENEWALS) v += "${e.cls}: renewals=${e.renewals} exceeds the cap of $MAX_RENEWALS"
                        if (e.renewals > 0 && !NAMES_OPEN_ITEM.containsMatchIn(e.reason)) {
                            v += "${e.cls}: renewed without naming an open item (awaiting OD-n / awaiting pass 12.n / awaiting-unit Un): '${e.reason}'"
                        }
                        if (today.isAfter(e.expiry!!)) {
                            v += if (e.renewals >= MAX_RENEWALS) {
                                "renewal limit reached: ${e.cls} awaiting ${e.item}"
                            } else {
                                "expired allowlist entry: ${e.cls} (${e.reason}) set ${e.date}, expired ${e.expiry}; " +
                                    "wire or delete it, or renew once if the reason names the open item it waits on"
                            }
                        }
                    }
                }
            }
        }
        return v
    }

    /** A PR may not lower an entry's `renewals`, and an entry may not drop below its previous count. */
    fun checkRenewalsNotLowered(previous: List<AllowEntry>, current: List<AllowEntry>): List<String> {
        val now = current.associateBy { it.cls }
        return previous.mapNotNull { p ->
            val c = now[p.cls] ?: return@mapNotNull null
            if (c.renewals < p.renewals) "renewals lowered for ${p.cls}: ${p.renewals} -> ${c.renewals}" else null
        }
    }

    /**
     * The whole gate: every implementation must be constructed by a root or be allowlisted, and
     * every allowlist entry must still be needed (stale-entry rule).
     */
    fun evaluate(
        productionSources: Map<String, String>,
        roots: Map<String, String>,
        allowlistText: String,
        today: LocalDate,
        seams: List<String> = SEAMS,
    ): List<String> {
        val parsed = parseAllowlist(allowlistText)
        val violations = parsed.errors.toMutableList()
        val strippedRoots = roots.values.map(::stripCommentsAndStrings)
        val impls = findImplementations(productionSources, seams)
        val existing = declaredNames(productionSources)
        val allowed = parsed.entries.associateBy { it.cls }

        for (impl in impls) {
            if (!isConstructed(impl, strippedRoots) && impl.name !in allowed) {
                violations += "${impl.name} (${impl.file}) implements a wiring seam but no composition root constructs it; " +
                    "wire it or add it to wiring-allowlist.txt; roots searched: ${roots.keys.sorted()}"
            }
        }
        val implByName = impls.associateBy { it.name }
        for (e in parsed.entries) {
            val impl = implByName[e.cls]
            when {
                e.cls !in existing -> violations += "stale allowlist entry: ${e.cls} no longer exists; delete its line"
                impl != null && isConstructed(impl, strippedRoots) ->
                    violations += "stale allowlist entry: ${e.cls} is now constructed by a root; delete its line"
                impl == null && isConstructed(Impl(e.cls, "", Kind.CLASS), strippedRoots) ->
                    violations += "stale allowlist entry: ${e.cls} is now constructed by a root; delete its line"
            }
        }
        violations += checkAllowlistEntries(parsed.entries, today)
        return violations
    }
}
