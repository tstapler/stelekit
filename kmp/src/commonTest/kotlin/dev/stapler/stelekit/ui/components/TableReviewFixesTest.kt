// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.ui.components.sheet.isSafeCellUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Adversarial-review fixes that are pure logic: commit ordering, table detection, and the cell-link scheme allowlist. */
class TableReviewFixesTest {
    private val table = "| a | b |\n|---|---|\n| 1 | 2 |"

    @Test
    fun commitQueue_should_PersistTheLastSubmittedContent_UnderConcurrentWrites() = runBlocking {
        repeat(6) { round ->
            val uuid = BlockUuid("b")
            val persisted = mutableListOf<String>()
            val lock = Any()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val queue = TableCommitQueue(scope) { _, text, _ ->
                // Like updateBlockContent: the write runs on its own coroutine and takes a varying time.
                val done = CompletableDeferred<Unit>()
                scope.launch {
                    delay((text.hashCode().toLong() and 7L) + round % 3)
                    synchronized(lock) { persisted += text }
                    done.complete(Unit)
                }
                done.await()
            }
            val submitted = (1..100).map { "v$it" }
            submitted.forEach { queue.submit(uuid, it, 1L) }
            queue.close()
            while (synchronized(lock) { persisted.size } < submitted.size) delay(2)
            assertEquals(submitted, synchronized(lock) { persisted.toList() })
        }
    }

    @Test
    fun isGridTableContent_should_RejectATableInsideACodeFence() {
        assertFalse(isGridTableContent("```\n$table\n```"))
        assertFalse(isGridTableContent("~~~\n$table\n~~~"))
    }

    @Test
    fun isGridTableContent_should_AcceptAPlainTable_AndRejectProseAroundIt() {
        assertTrue(isGridTableContent(table))
        assertFalse(isGridTableContent("Intro line\n$table"))
        assertFalse(isGridTableContent("$table\ntrailing prose"))
    }

    @Test
    fun cellUrls_should_AllowOnlyWebAndMail() {
        assertTrue(isSafeCellUrl("https://example.com/a"))
        assertTrue(isSafeCellUrl("HTTP://example.com"))
        assertTrue(isSafeCellUrl("mailto:a@b.co"))
        assertFalse(isSafeCellUrl("javascript:alert(1)"))
        assertFalse(isSafeCellUrl("file:///etc/passwd"))
        assertFalse(isSafeCellUrl("intent://scan#Intent;end"))
        assertFalse(isSafeCellUrl("tel:123"))
    }

    @Test
    fun commitQueue_should_KeepRunningAfterAWriteThrows_AndNotBlockOtherBlocks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ran = java.util.concurrent.CopyOnWriteArrayList<String>()
        val hung = CompletableDeferred<Unit>()
        val queue = TableCommitQueue(scope) { uuid, text, _ ->
            if (uuid.value == "hung") hung.await()
            if (text == "boom") error("write failed")
            ran += "${uuid.value}:$text"
        }
        queue.submit(BlockUuid("hung"), "x", 1L)
        queue.submit(BlockUuid("a"), "boom", 1L)
        queue.submit(BlockUuid("a"), "ok", 2L)
        kotlinx.coroutines.withTimeout(5_000) { while ("a:ok" !in ran) delay(5) }
        assertEquals(listOf("a:ok"), ran.toList())
        queue.close()
        queue.submit(BlockUuid("a"), "late", 3L)
        hung.complete(Unit)
        kotlinx.coroutines.withTimeout(5_000) { while ("hung:x" !in ran) delay(5) }
        assertFalse("a:late" in ran)
    }
}
