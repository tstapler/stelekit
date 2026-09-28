package dev.stapler.stelekit.docs

import java.io.File
import java.net.JarURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reads repo-root planning artifacts (`docs/journeys/` markdown files,
 * `project_plans/rich-editing-experience/implementation/` markdown files) from a businessTest
 * classpath.
 *
 * Two resolution paths, tried in order:
 *  1. **Bazel**: each file is bundled as a classpath resource (see
 *     `kmp/src/businessTest/kotlin/BUILD.bazel`'s `rich_editing_backlog_docs_as_resources` and
 *     `journey_docs_as_resources` targets) — `getResourceAsStream`/jar-entry enumeration, no
 *     real [File] involved. Needed because Bazel's `kt_jvm_test` packs classpath resources into
 *     a runfiles jar, so a classloader resource resolves to a `jar:file:...!/...` URL —
 *     `File(url.toURI())` throws `IllegalArgumentException: URI is not hierarchical` on that,
 *     and even if it didn't, walking "up" from a path inside Bazel's sandboxed runfiles tree
 *     would never reach the real repo root.
 *  2. **Gradle** (fallback): walk up from a known classpath resource (`demo-graph/pages`, an
 *     unpacked directory under Gradle) until a directory containing the expected repo-root
 *     siblings (`kmp/`, `docs/`, `project_plans/`) is found, then read the real on-disk file.
 */
object DocRepoLocator {

    val repoRoot: File by lazy {
        val resource = DocRepoLocator::class.java.classLoader.getResource("demo-graph/pages")
            ?: error("demo-graph/pages not found on classpath — is the test classpath misconfigured?")
        var dir = File(resource.toURI())
        while (dir != dir.parentFile) {
            if (dir.resolve("kmp").isDirectory &&
                dir.resolve("docs").isDirectory &&
                dir.resolve("project_plans").isDirectory
            ) {
                return@lazy dir
            }
            dir = dir.parentFile
        }
        error("Could not locate repository root (dir containing kmp/, docs/, project_plans/) from: $resource")
    }

    /** [resourceName] is the Bazel-bundled classpath resource name; [repoRelativePath] is the
     * real path from [repoRoot], used only as the Gradle fallback. */
    private fun readRepoFile(resourceName: String, repoRelativePath: String): String =
        DocRepoLocator::class.java.classLoader.getResourceAsStream(resourceName)
            ?.bufferedReader()?.readText()
            ?: repoRoot.resolve(repoRelativePath).readText()

    fun gapBacklogText(): String = readRepoFile(
        "gap-backlog.md",
        "project_plans/rich-editing-experience/implementation/gap-backlog.md",
    )

    fun planText(): String = readRepoFile(
        "plan.md",
        "project_plans/rich-editing-experience/implementation/plan.md",
    )

    /** (fileName, text) pairs for every non-README .md file in docs/journeys/, sorted by name. */
    fun journeyDocs(): List<Pair<String, String>> {
        val bazelNames = bazelJourneyDocNames()
        return if (bazelNames != null) {
            bazelNames.map { name -> name to readRepoFile("journeys/$name", "docs/journeys/$name") }
        } else {
            val journeysDir = repoRoot.resolve("docs/journeys")
            (journeysDir.listFiles { f -> f.isFile && f.extension == "md" && f.name != "README.md" }
                ?: error("docs/journeys/ not found or unreadable at $journeysDir"))
                .sortedBy { it.name }
                .map { it.name to it.readText() }
        }
    }

    /** Enumerates journeys/ .md resource names from the Bazel-bundled jar (mirrors
     * DemoFileSystemSyncTest's file:// vs jar:// handling for demo-graph/pages). Returns null
     * when there's no "journeys" classpath resource at all — the Gradle path, which has no such
     * resource since journey_docs_as_resources is Bazel-only. */
    private fun bazelJourneyDocNames(): List<String>? {
        val url = DocRepoLocator::class.java.classLoader.getResource("journeys") ?: return null
        if (url.protocol != "jar") return null
        val connection = url.openConnection() as JarURLConnection
        return connection.jarFile.use { jar ->
            jar.entries().asSequence()
                .filter {
                    !it.isDirectory && it.name.startsWith("journeys/") &&
                        it.name.endsWith(".md") && it.name != "journeys/README.md"
                }
                .map { it.name.removePrefix("journeys/") }
                .sorted()
                .toList()
        }
    }
}

/**
 * Minimal, purpose-built parser for the frontmatter schema documented in
 * `docs/journeys/README.md`. Not a general YAML parser (no YAML library is on this project's
 * classpath) — it only recovers top-level `key: value` blobs, keeping nested maps/block scalars
 * verbatim as that key's raw text, which is enough to regex-check presence, enum membership, and
 * cited-evidence patterns. Same "read the real file, regex-extract structure" idiom as
 * [dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest].
 */
object FrontmatterParser {

    /** Returns the raw text between the two `---` fences, or null if there is no frontmatter. */
    fun extractFrontmatter(text: String): String? {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (endIdx == -1) return null
        return lines.subList(1, endIdx + 1).joinToString("\n")
    }

    private val topLevelKeyRegex = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*):(.*)$""")

    /**
     * A "top-level key" is any line starting at column 0 matching `key:`. Everything up to the
     * next such line (nested maps, block scalars, list items) is kept verbatim as that key's
     * value, then trimmed.
     */
    fun parseTopLevelFields(frontmatter: String): Map<String, String> {
        val result = LinkedHashMap<String, StringBuilder>()
        var currentKey: String? = null
        for (line in frontmatter.lines()) {
            val isTopLevel = line.isNotEmpty() && !line[0].isWhitespace() && topLevelKeyRegex.matches(line)
            if (isTopLevel) {
                val m = topLevelKeyRegex.matchEntire(line)!!
                currentKey = m.groupValues[1]
                result.getOrPut(currentKey) { StringBuilder() }.append(m.groupValues[2])
            } else if (currentKey != null) {
                result.getValue(currentKey).append('\n').append(line)
            }
        }
        return result.mapValues { it.value.toString().trim() }
    }
}

data class JourneyDoc(val fileName: String, val fields: Map<String, String>)

object JourneyDocs {

    val REQUIRED_JOURNEY_IDS = setOf(
        "insert-tag", "insert-link", "format-text", "toggle-todo", "insert-code-block",
        "insert-table", "insert-image", "reorder-block", "multi-select-block",
        "voice-capture", "annotate-asset",
    )

    fun parseAll(): List<JourneyDoc> = DocRepoLocator.journeyDocs().map { (name, text) ->
        val frontmatter = FrontmatterParser.extractFrontmatter(text)
            ?: error("$name: no frontmatter block found (must start with '---' and close with '---')")
        JourneyDoc(name, FrontmatterParser.parseTopLevelFields(frontmatter))
    }
}

data class FieldValidationResult(val errors: List<String>) {
    val isValid get() = errors.isEmpty()
}

/** Enforces the frontmatter schema table in `docs/journeys/README.md`. */
object JourneyDocValidator {
    private val VALID_TIERS = setOf("functional", "social", "in-between")
    private val VALID_STATUSES = setOf("audited", "stub-pending-phase-g", "audit-deferred")
    private val KEBAB_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")

    fun validate(fields: Map<String, String>): FieldValidationResult {
        val errors = mutableListOf<String>()

        val journeyId = fields["journey_id"]
        if (journeyId.isNullOrBlank()) {
            errors += "missing required field: journey_id"
        } else if (!KEBAB_REGEX.matches(journeyId)) {
            errors += "journey_id '$journeyId' is not kebab-case (must match ${KEBAB_REGEX.pattern})"
        }

        if (fields["platforms"].isNullOrBlank()) errors += "missing required field: platforms"

        val tier = fields["jtbd_tier"]?.trim()
        if (tier.isNullOrBlank()) {
            errors += "missing required field: jtbd_tier"
        } else if (tier !in VALID_TIERS) {
            errors += "jtbd_tier '$tier' not one of $VALID_TIERS"
        }

        if (!fields.containsKey("test_ids")) errors += "missing required field: test_ids"

        val status = fields["status"]?.trim()
        if (status.isNullOrBlank()) {
            errors += "missing required field: status"
        } else if (status !in VALID_STATUSES) {
            errors += "status '$status' not one of $VALID_STATUSES"
        }

        val lastVerified = fields["last_verified"]?.trim()
        if (lastVerified.isNullOrBlank()) {
            errors += "missing required field: last_verified"
        } else if (!DATE_REGEX.matches(lastVerified)) {
            errors += "last_verified '$lastVerified' is not YYYY-MM-DD"
        }

        if (fields["heuristic_findings"].isNullOrBlank()) errors += "missing required field: heuristic_findings"

        return FieldValidationResult(errors)
    }
}

class JourneyDocsFrontmatterTest {

    @Test
    fun `parseFrontmatter should returnAllRequiredFields When journeyDocIsWellFormed`() {
        val doc = JourneyDocs.parseAll().single { it.fileName == "insert-tag.md" }
        val required = listOf("journey_id", "platforms", "jtbd_tier", "test_ids", "status", "last_verified")
        for (key in required) {
            assertTrue(doc.fields.containsKey(key), "insert-tag.md missing required frontmatter field: $key")
        }
        assertEquals("insert-tag", doc.fields["journey_id"])
        assertTrue(Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(doc.fields.getValue("journey_id")))
    }

    @Test
    fun `parseFrontmatter should reportMissingField When journeyIdOrJtbdTierAbsent`() {
        val missingJourneyId = """
            platforms: [desktop]
            jtbd_tier: functional
            test_ids: []
            status: audited
            last_verified: 2026-07-05
            heuristic_findings: fixture
        """.trimIndent()
        val result1 = JourneyDocValidator.validate(FrontmatterParser.parseTopLevelFields(missingJourneyId))
        assertFalse(result1.isValid)
        assertTrue(result1.errors.any { it.contains("journey_id") })

        val bogusTier = """
            journey_id: some-journey
            platforms: [desktop]
            jtbd_tier: bogus
            test_ids: []
            status: audited
            last_verified: 2026-07-05
            heuristic_findings: fixture
        """.trimIndent()
        val result2 = JourneyDocValidator.validate(FrontmatterParser.parseTopLevelFields(bogusTier))
        assertFalse(result2.isValid)
        assertTrue(result2.errors.any { it.contains("jtbd_tier") })
    }

    @Test
    fun `scanJourneyDocs should findNoDuplicateJourneyIds When allDocsInDirectoryAreParsed`() {
        val docs = JourneyDocs.parseAll()
        val ids = docs.mapNotNull { it.fields["journey_id"] }
        assertEquals(ids.size, ids.toSet().size, "Duplicate journey_id values found across docs/journeys/: $ids")
        assertEquals(
            JourneyDocs.REQUIRED_JOURNEY_IDS,
            ids.toSet(),
            "docs/journeys/ journey_id set does not match the required set of 11 journeys"
        )
    }

    @Test
    fun `everyJourneyDoc should passSchemaValidation When readFromDisk`() {
        val failures = JourneyDocs.parseAll().mapNotNull { doc ->
            val result = JourneyDocValidator.validate(doc.fields)
            if (result.isValid) null else "${doc.fileName}: ${result.errors.joinToString("; ")}"
        }
        assertTrue(failures.isEmpty(), "Schema violations found:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `journeyId should matchItsOwnFileName When docIsWellFormed`() {
        val mismatches = JourneyDocs.parseAll().mapNotNull { doc ->
            val expected = doc.fileName.removeSuffix(".md")
            val actual = doc.fields["journey_id"]
            if (actual == expected) null else "${doc.fileName}: journey_id='$actual' expected='$expected'"
        }
        assertTrue(mismatches.isEmpty(), "journey_id must match its own filename:\n${mismatches.joinToString("\n")}")
    }
}
