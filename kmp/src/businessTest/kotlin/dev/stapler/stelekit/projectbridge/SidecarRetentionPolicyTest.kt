// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 0.1.1c: `RetentionPolicy` for `backup/` (kept, git-synced) and `conflicts/` (newest 10, git-ignored). */
class SidecarRetentionPolicyTest {
    private val slug = "kitchen"
    private val sheets = mapOf("sheets/s1.json" to OldReaderFixtures.s1)
    private val bundle = sheets + (SidecarCodec.MODEL_PATH to OldReaderFixtures.model(listOf("s1" to null), sheets))

    private fun copyBytes(i: Int) = "{\"schema\":2,\"sheetUuid\":\"s1\",\"n\":$i}\n".encodeToByteArray()

    private fun runGit(dir: File, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).also {
            it.environment().apply { put("GIT_CONFIG_NOSYSTEM", "1"); put("GIT_CONFIG_GLOBAL", "/dev/null"); put("HOME", dir.absolutePath) }
        }.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.exitValue() to out
    }

    @Test
    fun retention_should_KeepAllBackupsAndNewest10Conflicts_AndIgnoreOnlyConflicts() = runBlocking<Unit> {
        val fs = SidecarFs()
        val store = newStore(fs)
        store.write(slug, bundle)
        val hashBefore = SidecarCodec.bundleHash(assertIs<SidecarRead.Present>(store.read(slug)).files)

        repeat(12) { assertIs<Either.Right<String>>(store.writeBackup(slug, "sheet$it", OldReaderFixtures.s1)) }
        val copies = (1..25).map { assertIs<Either.Right<String>>(store.writeSheetConflictCopy(slug, "s1", copyBytes(it))).value }
        val otherSheet = assertIs<Either.Right<String>>(store.writeSheetConflictCopy(slug, "s2", copyBytes(100))).value
        val unresolved = copies.first().substringAfterLast('/')

        val pruned = assertIs<Either.Right<Int>>(store.pruneConflictCopies(slug, "s1", setOf(unresolved))).value

        assertEquals(14, pruned, "25 copies: the newest 10 and the unresolved oldest stay")
        assertTrue(fs.fileExists(copies.first()), "an unresolved conflict's copy is never pruned")
        assertEquals(copies.takeLast(10), copies.filter { fs.fileExists(it) && it != copies.first() })
        assertTrue(fs.fileExists(otherSheet), "another sheet's copies are not counted")
        assertEquals(12, fs.allFilePaths().count { it.contains("/backup/") }, "backup/ is never pruned")

        val size = store.retentionSize(slug)
        assertEquals(RetentionPolicy.Size(12, 12L * OldReaderFixtures.s1.size, 12, size.conflictBytes), size)
        assertTrue(size.conflictBytes > 0)

        val after = assertIs<SidecarRead.Present>(store.read(slug)).files
        assertEquals(bundle.keys, after.keys)
        assertEquals(hashBefore, SidecarCodec.bundleHash(after))
    }

    @Test
    fun pruneRule_should_BePureAndKeepAllWhenAtOrUnderTheLimit() {
        val ten = (1..10).map { RetentionPolicy.ConflictCopy("s1.v2.conflict-$it.json", it.toLong()) }
        assertEquals(emptyList(), RetentionPolicy.conflictCopiesToPrune(ten, emptySet()))
        val eleven = ten + RetentionPolicy.ConflictCopy("s1.v2.conflict-0.json", 0L)
        assertEquals(listOf("s1.v2.conflict-0.json"), RetentionPolicy.conflictCopiesToPrune(eleven, emptySet()))
        assertEquals(emptyList(), RetentionPolicy.conflictCopiesToPrune(eleven, setOf("s1.v2.conflict-0.json")))
        assertEquals("s1", RetentionPolicy.sheetOfConflictCopy("s1.v2.conflict-abcd1234.json"))
        assertEquals(null, RetentionPolicy.sheetOfConflictCopy("notes.txt"))
    }

    @Test
    fun ignoreRule_should_AppendOnce_NeverRewriteUserLines_AndIgnoreOnlyConflicts() = runBlocking<Unit> {
        val fs = SidecarFs()
        fs.seed("$GRAPH/.gitignore", "build/\nassets/stelekit-sketch/**/*.tmp".encodeToByteArray())
        val store = newStore(fs)
        store.write(slug, bundle)
        store.write(slug, bundle)
        store.writeBackup(slug, "s1", OldReaderFixtures.s1)
        val conflict = assertIs<Either.Right<String>>(store.writeSheetConflictCopy(slug, "s1", copyBytes(1))).value

        val text = fs.readFileBytes("$GRAPH/.gitignore")!!.decodeToString()
        assertEquals(listOf("build/", "assets/stelekit-sketch/**/*.tmp", RetentionPolicy.CONFLICTS_IGNORE_RULE), text.lines().filter { it.isNotEmpty() })

        val git = runCatching { runGit(File("."), "--version").first == 0 }.getOrDefault(false)
        assumeTrue("git is not on PATH", git)
        val repo = createTempDirectory("stelekit_retention_").toFile()
        try {
            runGit(repo, "init", "-q")
            File(repo, ".gitignore").writeText(text)
            fun ignored(abs: String): Boolean {
                val rel = abs.removePrefix("$GRAPH/")
                File(repo, rel).also { it.parentFile.mkdirs() }.writeText("x")
                return runGit(repo, "check-ignore", "-q", rel).first == 0
            }
            assertTrue(ignored(conflict), "conflicts/* is device-local")
            assertFalse(ignored(sidecarPath(slug, "backup/s1.v1.json")), "backup/* is synced")
            assertFalse(ignored(sidecarPath(slug, "sheets/s1.json")))
            assertFalse(ignored(sidecarPath(slug, "sheets/s1.v2.json")))
            assertFalse(ignored(sidecarPath(slug, SidecarCodec.MODEL_PATH)))
        } finally {
            repo.deleteRecursively()
        }
    }
}
