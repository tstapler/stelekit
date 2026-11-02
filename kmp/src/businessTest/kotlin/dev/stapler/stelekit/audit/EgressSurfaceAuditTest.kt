package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every production file that builds an HTTP client is listed in `egress-allowlist.txt` with a
 * destination class. This is the whole-app egress inventory; the narrower claim that the asset
 * pipeline reaches no third party without consent is `CloudStructuralAuditTest`.
 */
class EgressSurfaceAuditTest {
    private val validClasses = setOf("first-party", "user-configured", "third-party-llm", "third-party-google", "git-remote", "url-fetch")

    companion object {
        /** A file is in scope iff a line starts `import io.ktor.client`, or it calls `HttpClient(`. */
        fun discover(sources: Map<String, String>): Set<String> = sources.filter { (_, text) ->
            text.lineSequence().any { it.startsWith("import io.ktor.client") } || text.contains("HttpClient(")
        }.keys

        fun parseAllowlist(text: String): Map<String, String> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split('\t')
                require(parts.size == 3) { "allowlist line needs path, class, reason: $line" }
                parts[0] to parts[1]
            }

        /** Violations, each naming the file: unlisted discoveries and entries whose file is gone. */
        fun compare(discovered: Set<String>, allowed: Set<String>): List<String> =
            (discovered - allowed).sorted().map { "not in egress-allowlist.txt: $it" } +
                (allowed - discovered).sorted().map { "stale egress-allowlist.txt entry (file gone or no longer builds a client): $it" }
    }

    @Test
    fun egressSurface_should_FailNamingFile_When_NewIoKtorClientImportNotInAllowlist_OrEntryStale() {
        val allowlist = parseAllowlist(WiringInputs.dataFile("egress-allowlist.txt", "kmp/src/businessTest/resources/egress-allowlist.txt"))
        val discovered = discover(WiringInputs.productionSources)

        assertEquals(emptyList(), compare(discovered, allowlist.keys), "update egress-allowlist.txt")
        assertTrue(allowlist.values.all { it in validClasses }, "unknown destination class in ${allowlist.filterValues { it !in validClasses }}")
        assertTrue(discovered.isNotEmpty(), "an empty scan would pass vacuously")
    }

    @Test
    fun egressSurface_should_NameTheNewFile_And_TheStaleEntry_When_ListsDisagree() {
        val planted = mapOf(
            "commonMain/dev/x/Sneaky.kt" to "package dev.x\nimport io.ktor.client.HttpClient\n",
            "commonMain/dev/x/Direct.kt" to "package dev.x\nval c = HttpClient(engine)\n",
            "commonMain/dev/x/Quiet.kt" to "package dev.x\n// mentions io.ktor.client only in a comment\n",
        )

        val found = discover(planted)
        assertEquals(setOf("commonMain/dev/x/Sneaky.kt", "commonMain/dev/x/Direct.kt"), found)

        val problems = compare(found, setOf("commonMain/dev/x/Direct.kt", "commonMain/dev/x/Gone.kt"))
        assertEquals(
            listOf(
                "not in egress-allowlist.txt: commonMain/dev/x/Sneaky.kt",
                "stale egress-allowlist.txt entry (file gone or no longer builds a client): commonMain/dev/x/Gone.kt",
            ),
            problems,
        )
    }
}
