// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 3.1.1 Task 3.1.1f (REQ-8 gap noted in validation.md — Task 3.1.1f names no test task):
 * a torn-pack-file race (`research/pitfalls.md` §3) must fail verification even though object/ref
 * counts still match, since [GitObjectContentVerifier] content-hashes every object/pack file
 * rather than comparing counts alone.
 */
@RunWith(RobolectricTestRunner::class)
class GitObjectContentVerifierTest {

    private fun tempGitDir(name: String): File =
        File.createTempFile(name, "").apply { delete(); mkdirs() }

    @Test
    fun `copyAndVerify should ReturnVerificationFailed When GitPackFileByteCorrupted`() = runTest {
        val sourceGit = tempGitDir("source-git")
        val destinationGit = tempGitDir("destination-git")

        val sourcePackDir = File(sourceGit, "objects/pack").apply { mkdirs() }
        val destinationPackDir = File(destinationGit, "objects/pack").apply { mkdirs() }

        val packBytes = ByteArray(256) { it.toByte() }
        File(sourcePackDir, "pack-abc123.pack").writeBytes(packBytes)
        // Simulate a torn-pack-file race: destination has the same byte count (an object/ref-count
        // or size-only check would pass) but one byte differs.
        val corrupted = packBytes.copyOf().also { it[10] = (it[10].toInt() xor 0xFF).toByte() }
        File(destinationPackDir, "pack-abc123.pack").writeBytes(corrupted)

        val result = GitObjectContentVerifier().verify(sourceGit, destinationGit)

        val error = assertIs<arrow.core.Either.Left<DomainError.StorageError>>(result).value
        val verificationFailed = assertIs<DomainError.StorageError.VerificationFailed>(error)
        assertTrue(
            verificationFailed.path.contains("pack-abc123.pack"),
            "expected the failing pack file's path in the error, got ${verificationFailed.path}",
        )
    }

    @Test
    fun `verify should ReturnZero When NoGitObjectsExist`() = runTest {
        val sourceGit = tempGitDir("source-git-empty")
        val destinationGit = tempGitDir("destination-git-empty")

        val result = GitObjectContentVerifier().verify(sourceGit, destinationGit)

        val count = assertIs<arrow.core.Either.Right<Int>>(result).value
        assertEquals(0, count)
    }

    @Test
    fun `verify should VerifyLooseObjects When ByteIdenticalAcrossFanoutDirectories`() = runTest {
        val sourceGit = tempGitDir("source-git-loose")
        val destinationGit = tempGitDir("destination-git-loose")

        val sourceFanout = File(sourceGit, "objects/ab").apply { mkdirs() }
        val destinationFanout = File(destinationGit, "objects/ab").apply { mkdirs() }
        val objectBytes = "loose object content".encodeToByteArray()
        File(sourceFanout, "cdef0123456789").writeBytes(objectBytes)
        File(destinationFanout, "cdef0123456789").writeBytes(objectBytes)

        val result = GitObjectContentVerifier().verify(sourceGit, destinationGit)

        val count = assertIs<arrow.core.Either.Right<Int>>(result).value
        assertEquals(1, count)
    }
}
