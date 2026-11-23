package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the Bazel module split described by `kmp/modules_manifest.bzl` (W9): every JVM source file (commonMain,
 * jvmCommonMain, jvmMain) is in exactly one module, the declared module graph is acyclic, and no file imports a
 * declaration from a module outside its own dependency closure. Bazel itself would not notice a file that is in two
 * modules (it compiles twice) or in none (it silently drops it), so this is the completeness check.
 */
class ModuleManifestGuardTest {
    private class Module(val name: String, val deps: List<String>, val srcs: List<String>)

    private val modules: Map<String, Module> by lazy { parseManifest(WiringInputs.dataFile(MANIFEST_RESOURCE, MANIFEST_REPO_PATH)) }

    /** Manifest paths (relative to `kmp/src`) of every JVM production source on disk or on the classpath. */
    private val sourceUniverse: Map<String, String> by lazy {
        WiringInputs.productionSources
            .filterKeys { it.substringBefore('/') in JVM_SOURCE_SETS }
            .mapKeys { (key, _) -> key.substringBefore('/') + "/kotlin/" + key.substringAfter('/') }
    }

    @Test
    fun every_jvm_source_file_belongs_to_exactly_one_module() {
        val owners = HashMap<String, MutableList<String>>()
        for (m in modules.values) for (s in m.srcs) if (!s.startsWith("//")) owners.getOrPut(s) { mutableListOf() }.add(m.name)

        val unassigned = sourceUniverse.keys - owners.keys
        val duplicated = owners.filterValues { it.size > 1 }
        // `stats` is its own Bazel package, so the Bazel source bundle (WiringInputs) does not carry its files.
        val stale = (owners.keys - sourceUniverse.keys).filterNot { owners.getValue(it) == listOf(STATS) }

        assertTrue(unassigned.isEmpty(), "source files in no module (add them to kmp/modules_manifest.bzl): ${unassigned.sorted().take(MAX_LISTED)}")
        assertTrue(duplicated.isEmpty(), "source files in more than one module: ${duplicated.entries.sortedBy { it.key }.take(MAX_LISTED)}")
        assertTrue(stale.isEmpty(), "manifest entries for files that no longer exist: ${stale.sorted().take(MAX_LISTED)}")
    }

    @Test
    fun module_dependencies_name_real_modules_and_are_acyclic() {
        for (m in modules.values) for (d in m.deps) assertTrue(d in modules, "module ${m.name} depends on unknown module $d")
        val cycle = findCycle(modules.mapValues { it.value.deps })
        assertEquals(null, cycle, "the module graph has a cycle: $cycle")
    }

    @Test
    fun no_file_imports_a_module_outside_its_dependency_closure() {
        val closure = closures(modules.mapValues { it.value.deps })
        val ownerOf = HashMap<String, String>()
        for (m in modules.values) for (s in m.srcs) ownerOf[s] = m.name

        val declaredIn = HashMap<String, MutableList<String>>() // "pkg.Name" -> manifest paths
        for ((path, text) in sourceUniverse) {
            val pkg = PACKAGE.find(text)?.groupValues?.get(1) ?: continue
            for (name in topLevelNames(text)) declaredIn.getOrPut("$pkg.$name") { mutableListOf() }.add(path)
        }

        val violations = ArrayList<String>()
        for ((path, text) in sourceUniverse) {
            val from = ownerOf[path] ?: continue
            if (from == GRADLE_ONLY) continue
            for (import in IMPORT.findAll(text).map { it.groupValues[1] }) {
                val target = resolve(import, declaredIn) ?: continue
                val owners = target.mapNotNull { ownerOf[it] }.filter { it != GRADLE_ONLY }.toSet()
                if (owners.isEmpty() || owners.any { it == from || it in closure.getValue(from) }) continue
                violations += "$path (module $from) imports $import from ${owners.sorted()}"
            }
        }
        assertTrue(violations.isEmpty(), "imports from outside the module's dependency closure (${violations.size}):\n" + violations.sorted().take(MAX_LISTED).joinToString("\n"))
    }

    @Test
    fun the_cycle_finder_reports_a_cycle_and_accepts_a_dag() {
        assertNotNull(findCycle(mapOf("a" to listOf("b"), "b" to listOf("c"), "c" to listOf("a"))))
        assertEquals(null, findCycle(mapOf("a" to listOf("b", "c"), "b" to listOf("c"), "c" to emptyList())))
        assertFalse(closures(mapOf("a" to listOf("b"), "b" to emptyList())).getValue("b").contains("a"))
    }

    private fun resolve(import: String, declaredIn: Map<String, List<String>>): List<String>? {
        val parts = import.removeSuffix(".*").split('.')
        for (k in parts.size downTo 2) declaredIn[parts.take(k).joinToString(".")]?.let { return it }
        return null
    }

    private fun topLevelNames(text: String): Sequence<String> =
        text.lineSequence()
            .filter { it.isNotEmpty() && !it[0].isWhitespace() && it[0] != '}' && !PRIVATE.containsMatchIn(it) }
            .mapNotNull { DECLARATION.find(it)?.groupValues?.get(1) }

    private fun findCycle(graph: Map<String, List<String>>): List<String>? {
        val state = HashMap<String, Int>() // 1 = on the DFS stack, 2 = done
        val stack = ArrayList<String>()
        fun visit(n: String): List<String>? {
            when (state[n]) {
                1 -> return stack.dropWhile { it != n } + n
                2 -> return null
            }
            state[n] = 1
            stack += n
            for (d in graph[n].orEmpty()) visit(d)?.let { return it }
            stack.removeAt(stack.lastIndex)
            state[n] = 2
            return null
        }
        for (n in graph.keys) visit(n)?.let { return it }
        return null
    }

    private fun closures(graph: Map<String, List<String>>): Map<String, Set<String>> {
        val memo = HashMap<String, Set<String>>()
        fun of(n: String): Set<String> = memo.getOrPut(n) { graph[n].orEmpty().flatMap { setOf(it) + of(it) }.toSet() }
        return graph.keys.associateWith { of(it) }
    }

    private fun parseManifest(text: String): Map<String, Module> {
        val json = Json.parseToJsonElement(text.substringAfter("KMP_MODULES =")).jsonObject
        return json.mapValues { (name, spec) ->
            val obj: JsonObject = spec.jsonObject
            Module(name, obj.getValue("deps").jsonArray.strings(), obj.getValue("srcs").jsonArray.strings())
        }
    }

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

    private companion object {
        const val MANIFEST_RESOURCE = "modules_manifest.bzl"
        const val MANIFEST_REPO_PATH = "kmp/modules_manifest.bzl"
        const val GRADLE_ONLY = "gradle_only"
        const val STATS = "stats"
        const val MAX_LISTED = 25
        val JVM_SOURCE_SETS = setOf("commonMain", "jvmCommonMain", "jvmMain")
        val PACKAGE = Regex("""(?m)^package\s+([\w.]+)""")
        val IMPORT = Regex("""(?m)^import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+\w+)?\s*$""")
        val PRIVATE = Regex("""^(?:@[\w.]+(?:\([^)]*\))?\s+)*private\b""")
        val DECLARATION = Regex(
            """^(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|internal|expect|actual|data|sealed|enum|abstract|open|inline|value|annotation|const|suspend|tailrec|operator|infix|external|fun)\s+)*""" +
                """(?:class|interface|object|typealias|val|var|fun)\s+(?:<[^>]*>\s*)?(?:[\w<>?.,\s*]+\.)?(\w+)""",
        )
    }
}
