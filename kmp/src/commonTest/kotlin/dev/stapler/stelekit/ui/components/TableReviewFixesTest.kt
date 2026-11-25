// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.ui.components.sheet.isSafeCellUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Adversarial-review fixes that are pure logic: commit ordering, table detection, and the cell-link scheme allowlist. */
class TableReviewFixesTest {
    private val table = "| a | b |\n|---|---|\n| 1 | 2 |"

    @Test
    fun commitQueue_should_PersistTheLastSubmittedContent_WhenWritesTakeVaryingTime() = runTest {
        repeat(6) { round ->
            val uuid = BlockUuid("b")
            val persisted = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val queue = TableCommitQueue(scope) { _, text, _ ->
                // Like updateBlockContent: the write runs on its own coroutine and takes a varying (virtual) time.
                val done = CompletableDeferred<Unit>()
                scope.launch {
                    delay((text.hashCode().toLong() and 7L) + round % 3)
                    persisted += text
                    done.complete(Unit)
                }
                done.await()
            }
            val submitted = (1..100).map { "v$it" }
            submitted.forEach { queue.submit(uuid, it, 1L) }
            queue.close()
            testScheduler.advanceUntilIdle()
            assertEquals(submitted, persisted.toList())
            scope.cancel()
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
    fun commitQueue_should_KeepRunningAfterAWriteThrows_AndNotBlockOtherBlocks() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val ran = mutableListOf<String>()
        val hung = CompletableDeferred<Unit>()
        val queue = TableCommitQueue(scope) { uuid, text, _ ->
            if (uuid.value == "hung") hung.await()
            if (text == "boom") error("write failed")
            ran += "${uuid.value}:$text"
        }
        queue.submit(BlockUuid("hung"), "x", 1L)
        queue.submit(BlockUuid("a"), "boom", 1L)
        queue.submit(BlockUuid("a"), "ok", 2L)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a:ok"), ran.toList())
        queue.close()
        queue.submit(BlockUuid("a"), "late", 3L)
        hung.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a:ok", "hung:x"), ran.toList())
        scope.cancel()
    }
}
