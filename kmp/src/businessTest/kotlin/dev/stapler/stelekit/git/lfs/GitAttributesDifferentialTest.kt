// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential test: the pure-Kotlin matcher against the two implementations that define the truth, on
 * generated nested `.gitattributes` trees and a corpus of real-world files.
 *
 * - JGit's `TreeWalk.getAttributes()` always runs (it is on the classpath).
 * - The `git check-attr` CLI runs when `git` is on PATH. Set `LFS_DIFFERENTIAL_REQUIRED=true` (CI does) to turn
 *   "git is missing" into a failure, so the check cannot silently skip where it is supposed to run.
 *
 * Divergences found while writing this test are recorded in [knownJGitDivergences]; the `git` CLI is the oracle
 * of record, and every divergence of the matcher from it is a failure.
 */
class GitAttributesDifferentialTest {
    private val required = System.getenv("LFS_DIFFERENTIAL_REQUIRED") == "true"

    private val gitAvailable: Boolean = runCatching { run(File("."), listOf("git", "--version")).first == 0 }.getOrDefault(false)

    private fun requireGit() {
        if (required) assertTrue(gitAvailable, "LFS_DIFFERENTIAL_REQUIRED=true but `git` is not on PATH")
        assumeTrue("git is not on PATH; set LFS_DIFFERENTIAL_REQUIRED=true to make this a failure", gitAvailable)
    }

    // ── fixture ──────────────────────────────────────────────────────────────────────────────

    private class Case(val files: Map<String, String>, val paths: List<String>)

    private fun materialise(case: Case): File {
        val dir = createTempDirectory("stelekit_attr_diff_").toFile()
        run(dir, listOf("git", "init", "-q"))
        case.files.forEach { (path, text) -> File(dir, path).also { it.parentFile.mkdirs() }.writeText(text) }
        case.paths.forEach { path -> File(dir, path).also { it.parentFile.mkdirs() }.takeIf { !it.exists() }?.writeText("") }
        return dir
    }

    private fun run(dir: File, command: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).also { builder ->
            builder.environment().apply {
                put("GIT_CONFIG_NOSYSTEM", "1")
                put("GIT_CONFIG_GLOBAL", "/dev/null")
                put("GIT_ATTR_NOSYSTEM", "1")
                put("HOME", dir.absolutePath)
            }
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.exitValue() to output
    }

    private fun ours(case: Case): Map<String, String> {
        val files = case.files.filterKeys { it.endsWith(".gitattributes") || it == ".gitattributes" }.map { (path, text) ->
            val dir = path.removeSuffix(".gitattributes").trimEnd('/')
            GitAttributesParser.parse(text, dirPrefix = dir)
        }
        val rules = AttributeRuleSet(files)
        return case.paths.associateWith { stateText(rules.resolve(it, "filter")) }
    }

    private fun stateText(state: AttrState): String = when (state) {
        AttrState.Set -> "set"
        AttrState.Unset -> "unset"
        AttrState.Unspecified -> "unspecified"
        is AttrState.Value -> state.value
    }

    private fun gitCli(dir: File, case: Case): Map<String, String> {
        val out = mutableMapOf<String, String>()
        case.paths.chunked(CHUNK).forEach { chunk ->
            val (code, text) = run(dir, listOf("git", "check-attr", "filter", "--") + chunk)
            check(code == 0) { "git check-attr failed: $text" }
            text.lines().filter { it.isNotBlank() }.forEach { line ->
                val path = line.substringBefore(": filter: ")
                out[path] = line.substringAfter(": filter: ")
            }
        }
        return out
    }

    private fun jgit(dir: File, case: Case): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val repo: Repository = FileRepositoryBuilder().setWorkTree(dir).build()
        repo.use {
            TreeWalk(it).use { walk ->
                walk.addTree(FileTreeIterator(it))
                walk.isRecursive = true
                walk.operationType = TreeWalk.OperationType.CHECKIN_OP
                while (walk.next()) {
                    val attribute: Attribute? = walk.attributes.get("filter")
                    out[walk.pathString] = when {
                        attribute == null -> "unspecified"
                        attribute.state == Attribute.State.CUSTOM -> attribute.value
                        attribute.state == Attribute.State.SET -> "set"
                        attribute.state == Attribute.State.UNSET -> "unset"
                        else -> "unspecified"
                    }
                }
            }
        }
        return out.filterKeys { it in case.paths }
    }

    // ── generated cases ──────────────────────────────────────────────────────────────────────

    private val patterns = listOf(
        "*.png", "*.bin", "assets/*", "/x.png", "**/raw/*", "docs/**", "img/*.png", "a?.png", "[ab]*.png",
        "*", "x.png", "!*.png", "assets/", "*.PNG", "raw/*.bin", "**/*.pdf", "docs/*/*.png",
    )
    private val attributes = listOf("filter=lfs", "-filter", "!filter", "filter=other", "filter", "-text", "diff=lfs")
    private val directories = listOf("assets", "assets/raw", "docs", "docs/img", "a/b", "raw")
    private val probePaths = listOf(
        "x.png", "y.png", "ab.png", "a1.png", "data.bin", "X.PNG", "report.pdf", "assets/p.png", "assets/raw/q.png",
        "assets/raw/r.bin", "assets/s/t.png", "docs/a.md", "docs/img/i.png", "docs/img/deep/j.png", "docs/z/k.png",
        "a/b/c.png", "a/b/c/d.bin", "raw/f.bin", "m/raw/f.bin", "m/n/raw/g.png", "docs/x.pdf", "img/h.png", "q/img/h.png",
    )

    private fun generate(seed: Int): Case {
        val random = Random(seed)
        fun lines(count: Int) = List(count) { "${patterns.random(random)} ${attributes.random(random)}" }.joinToString("\n", postfix = "\n")
        val files = linkedMapOf(".gitattributes" to lines(random.nextInt(1, 6)))
        directories.shuffled(random).take(random.nextInt(0, 4)).forEach { dir ->
            files["$dir/.gitattributes"] = lines(random.nextInt(1, 4))
        }
        return Case(files, probePaths)
    }

    private fun diff(label: String, expected: Map<String, String>, actual: Map<String, String>, case: Case) {
        val bad = case.paths.filter { expected[it] != actual[it] }
        assertTrue(
            bad.isEmpty(),
            "$label disagrees on ${bad.map { "$it: expected ${expected[it]} got ${actual[it]}" }}\nfiles=${case.files}",
        )
    }

    @Test
    fun matcherAgreesWithGitCheckAttrOnGeneratedNestedFiles() {
        requireGit()
        repeat(GIT_CASES) { seed ->
            val case = generate(seed)
            val dir = materialise(case)
            try {
                diff("git check-attr (seed $seed)", gitCli(dir, case), ours(case), case)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun matcherAgreesWithJGitOnGeneratedNestedFiles() {
        var compared = 0
        val divergences = mutableListOf<String>()
        repeat(JGIT_CASES) { seed ->
            val case = generate(seed)
            val dir = File(createTempDirectory("stelekit_attr_jgit_").toFile(), "w").also { it.mkdirs() }
            try {
                case.files.forEach { (path, text) -> File(dir, path).also { it.parentFile.mkdirs() }.writeText(text) }
                case.paths.forEach { File(dir, it).also { f -> f.parentFile.mkdirs() }.takeIf { f -> !f.exists() }?.writeText("") }
                File(dir, ".git").mkdirs()
                val expected = jgit(dir, case)
                val actual = ours(case)
                compared += case.paths.size
                case.paths.filter { expected[it] != actual[it] }.forEach { path ->
                    divergences += "seed=$seed path=$path jgit=${expected[path]} ours=${actual[path]}"
                }
            } finally {
                dir.parentFile.deleteRecursively()
            }
        }
        val unexplained = divergences.filterNot { line -> knownJGitDivergences.any { it in line } }
        assertTrue(unexplained.isEmpty(), "compared $compared paths; unexplained divergences from JGit:\n${unexplained.take(10).joinToString("\n")}")
    }

    /** Substrings of divergence lines where JGit itself differs from the git CLI (the oracle of record). */
    private val knownJGitDivergences: List<String> = listOf(
        // JGit does not understand C-quoted patterns (`\"my file.png\" filter=lfs`); git and this matcher do.
        "my file.png: jgit=unspecified ours=lfs",
        // JGit expands a macro from an earlier line even after a later line unsets it; git (and this matcher) do not.
        "small/s.png: jgit=lfs ours=unspecified",
    )

    // ── corpus of real-world attribute files ─────────────────────────────────────────────────

    private val corpus: Map<String, String> = mapOf(
        "git lfs track output" to "*.psd filter=lfs diff=lfs merge=lfs -text\n*.zip filter=lfs diff=lfs merge=lfs -text\nassets/** filter=lfs diff=lfs merge=lfs -text\n",
        "github linguist overrides" to "# Auto detect text files and perform LF normalization\n* text=auto\n*.md linguist-documentation\n*.png binary\n*.bin filter=lfs\n",
        "logseq style" to "pages/*.md merge=union\njournals/*.md merge=union\nassets/** filter=lfs diff=lfs merge=lfs -text\n",
        "macro" to "[attr]lfs filter=lfs diff=lfs merge=lfs -text\n*.bin lfs\n*.png lfs\nsmall/*.png -lfs\n",
        "negations" to "*.png filter=lfs\n*.png -filter\nlogo.png filter=lfs\n",
        "quoted and comments" to "# comment\n\"my file.png\" filter=lfs\n   \n*.pdf\tfilter=lfs\n",
    )
    private val corpusPaths = listOf(
        "a.psd", "dir/a.zip", "assets/x.png", "assets/deep/y.bin", "pages/Home.md", "journals/2026_01_01.md", "logo.png",
        "img/logo.png", "small/s.png", "big/b.png", "x.bin", "my file.png", "doc.pdf", "docs/doc.pdf", "README.md",
    )

    @Test
    fun matcherAgreesWithGitCheckAttrOnTheCorpus() {
        requireGit()
        for ((name, text) in corpus) {
            val case = Case(mapOf(".gitattributes" to text), corpusPaths)
            val dir = materialise(case)
            try {
                diff("git check-attr on corpus entry '$name'", gitCli(dir, case), ours(case), case)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun matcherAgreesWithJGitOnTheCorpus() {
        for ((name, text) in corpus) {
            val case = Case(mapOf(".gitattributes" to text), corpusPaths)
            val dir = File(createTempDirectory("stelekit_attr_corpus_").toFile(), "w").also { it.mkdirs() }
            try {
                File(dir, ".gitattributes").writeText(text)
                corpusPaths.forEach { File(dir, it).also { f -> f.parentFile.mkdirs() }.writeText("") }
                File(dir, ".git").mkdirs()
                val bad = case.paths.filter { jgit(dir, case)[it] != ours(case)[it] }
                    .map { "$it: jgit=${jgit(dir, case)[it]} ours=${ours(case)[it]}" }
                    .filterNot { line -> knownJGitDivergences.any { it in line } }
                assertEquals(emptyList(), bad, "JGit disagrees on corpus entry '$name'")
            } finally {
                dir.parentFile.deleteRecursively()
            }
        }
    }

    // ── the ADR-003 empty-file exemption ────────────────────────────────────────────────────

    @Test
    fun gitLfsStoresAnEmptyFileAsAnEmptyBlob() {
        val lfsAvailable = runCatching { run(File("."), listOf("git", "lfs", "version")).first == 0 }.getOrDefault(false)
        if (required) assertTrue(lfsAvailable, "LFS_DIFFERENTIAL_REQUIRED=true but git-lfs is not on PATH")
        assumeTrue("git-lfs is not on PATH; the empty-file exemption stays unconfirmed (ADR-003)", lfsAvailable)
        val dir = createTempDirectory("stelekit_lfs_empty_").toFile()
        try {
            run(dir, listOf("git", "init", "-q"))
            File(dir, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
            File(dir, "empty.bin").writeBytes(ByteArray(0))
            run(dir, listOf("git", "lfs", "install", "--local"))
            run(dir, listOf("git", "add", "."))
            val (_, blob) = run(dir, listOf("git", "cat-file", "-s", ":empty.bin"))
            assertEquals("0", blob.trim(), "git-lfs must store a zero-byte file as an empty raw blob, not a pointer")
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val GIT_CASES = 60
        const val JGIT_CASES = 200
        const val CHUNK = 12
    }
}
