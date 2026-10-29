// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generated attribute files, paths and contents run through the real [JvmGitRepository] `stageSubdir` +
 * `commit`: whatever was generated, no blob at an LFS-tracked path in the resulting history is a raw binary.
 */
class LfsRawBinaryNeverCommittedPropertyTest {
    private enum class Content { RAW_BINARY, RAW_TEXT, POINTER, EMPTY, CORRUPT_POINTER }

    private data class Generated(val path: String, val content: Content)

    private val attributeLines = listOf(
        "assets/** filter=lfs",
        "*.png filter=lfs",
        "*.bin filter=lfs",
        "docs/*.pdf filter=lfs",
        "*.png -filter",
        "assets/keep/** !filter",
        "**/raw/*.bin filter=other",
    )
    private val pathsVocabulary = listOf(
        "assets/a.png", "assets/keep/b.png", "assets/sub/c.bin", "pages/d.png", "pages/e.md",
        "docs/f.pdf", "docs/g/h.pdf", "x/raw/i.bin", "j.bin", "assets/empty.bin",
    )
    private val arbFile = Arb.bind(Arb.element(pathsVocabulary), Arb.element(Content.entries)) { p, c -> Generated(p, c) }

    private fun bytesFor(content: Content): ByteArray = when (content) {
        Content.RAW_BINARY -> LfsRepoFixture.PNG
        Content.RAW_TEXT -> "just some notes".encodeToByteArray()
        Content.POINTER -> LfsRepoFixture.POINTER
        Content.EMPTY -> ByteArray(0)
        Content.CORRUPT_POINTER -> LfsRepoFixture.POINTER.decodeToString().replace("\n", "\r\n").encodeToByteArray()
    }

    @Test
    fun noRawBlobAtATrackedPathIsEverCommitted() = runBlocking<Unit> {
        checkAll(40, Arb.list(Arb.element(attributeLines), 1..4), Arb.list(arbFile, 1..6)) { lines, files ->
            LfsRepoFixture().use { f ->
                val attributes = lines.joinToString("\n", postfix = "\n")
                f.write(".gitattributes", attributes)
                val byPath = files.associateBy { it.path }
                byPath.values.forEach { f.write(it.path, bytesFor(it.content)) }
                val policy = LfsPathPolicy(AttributeRuleSet(listOf(GitAttributesParser.parse(attributes))))
                val config = GitConfig(graphId = "g", repoRoot = RepoRoot(f.dir.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
                val repo = JvmGitRepository().apply { setLfsModeProvider { LfsMode.GUARD_ONLY } }

                repo.stageSubdir(config, emptySet())
                val commit = repo.commit(config, "generated")

                val rawAtTrackedPath = byPath.values.any { it.content != Content.POINTER && it.content != Content.EMPTY && policy.isTracked(it.path) }
                assertEquals(rawAtTrackedPath, commit.isLeft(), "attributes=[$attributes] files=$byPath")
                for ((path, bytes) in f.headBlobs()) {
                    if (!policy.isTracked(path)) continue
                    assertTrue(
                        bytes.isEmpty() || LfsPointerCodec.parse(bytes) is LfsPointerParse.Valid,
                        "raw blob at tracked path $path under [$attributes]",
                    )
                }
            }
        }
    }
}
