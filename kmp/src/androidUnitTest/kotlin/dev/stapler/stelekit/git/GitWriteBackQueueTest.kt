// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [GitWriteBackQueue]: enqueue/dequeue/getAll happy path, plus the atomic-rename
 * `dequeue()` crash-safety property called out in its class doc — a process death between the
 * temp-file write and the rename must never leave the primary queue file (or a concurrent
 * [GitWriteBackQueue.getAll]) observing a partially-written state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitWriteBackQueueTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newQueue(): GitWriteBackQueue = GitWriteBackQueue(tempFolder.newFile("queue.txt"))

    // ── Task 8.1.2a: enqueue/dequeue/getAll happy path ──────────────────────────────────────────

    @Test
    fun `enqueue dequeue and getAll happy path`() {
        val queue = newQueue()
        assertTrue(queue.isEmpty())

        queue.enqueue("pages/A.md")
        queue.enqueue("pages/B.md")
        queue.enqueue("journals/2026_08_29.md")

        assertEquals(
            setOf("pages/A.md", "pages/B.md", "journals/2026_08_29.md"),
            queue.getAll().toSet(),
        )
        assertFalse(queue.isEmpty())

        queue.dequeue("pages/B.md")

        assertEquals(setOf("pages/A.md", "journals/2026_08_29.md"), queue.getAll().toSet())

        queue.dequeue("pages/A.md")
        queue.dequeue("journals/2026_08_29.md")

        assertTrue(queue.isEmpty())
        assertEquals(emptyList(), queue.getAll())
    }

    @Test
    fun `dequeue on a path not in the queue is a no-op`() {
        val queue = newQueue()
        queue.enqueue("pages/A.md")

        queue.dequeue("pages/DoesNotExist.md")

        assertEquals(listOf("pages/A.md"), queue.getAll())
    }

    // ── Task 8.1.2a: mid-dequeue crash-safety (atomic rename) ───────────────────────────────────

    @Test
    fun `dequeue never leaves the queue file partially written across a simulated mid-write crash`() {
        val queueFile = tempFolder.newFile("queue.txt")
        val queue = GitWriteBackQueue(queueFile)
        val tmpFile = File(queueFile.parentFile, "${queueFile.name}.tmp")

        queue.enqueue("pages/A.md")
        queue.enqueue("pages/B.md")
        queue.enqueue("pages/C.md")

        // Simulate a prior dequeue() that crashed after writing the temp file but before the
        // rename completed: a stray, incomplete .tmp file is left sitting next to the untouched
        // primary queue file.
        tmpFile.writeText("pages/A.md\n") // incomplete/garbage — as if truncated mid-write

        // A concurrent getAll() must only ever observe the primary file's fully-valid contents —
        // the stray .tmp file must never be visible through the public API.
        assertEquals(
            setOf("pages/A.md", "pages/B.md", "pages/C.md"),
            queue.getAll().toSet(),
            "getAll must never observe the stray/partial .tmp file left by a simulated crash",
        )

        // A real dequeue() now runs: it must overwrite the stray .tmp file with fully-valid
        // content, rename it over the primary file, and leave no .tmp file behind afterward.
        queue.dequeue("pages/B.md")

        assertFalse(tmpFile.exists(), "the .tmp file must be consumed by the rename — no orphaned temp file after dequeue")
        assertEquals(setOf("pages/A.md", "pages/C.md"), queue.getAll().toSet())

        // The primary file itself must be well-formed: every line non-blank, valid UTF-8 text.
        val rawLines = queueFile.readLines(Charsets.UTF_8)
        assertTrue(rawLines.isNotEmpty())
        assertTrue(rawLines.all { it.isNotBlank() }, "primary queue file must never contain a truncated/blank line")
    }

    @Test
    fun `sequential dequeues leave no tmp file behind between calls`() {
        val queueFile = tempFolder.newFile("queue.txt")
        val queue = GitWriteBackQueue(queueFile)
        val tmpFile = File(queueFile.parentFile, "${queueFile.name}.tmp")

        queue.enqueue("pages/A.md")
        queue.enqueue("pages/B.md")

        queue.dequeue("pages/A.md")
        assertFalse(tmpFile.exists())
        queue.dequeue("pages/B.md")
        assertFalse(tmpFile.exists())

        assertTrue(queue.isEmpty())
    }
}
