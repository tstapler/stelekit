// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpToolErrorTest {

    @Test
    fun `GraphNotOpen toCallToolResult returns isError true with a no-graph-open message`() {
        val result = McpToolError.GraphNotOpen.toCallToolResult()

        assertEquals(true, result.isError)
        assertEquals(1, result.content.size)
        assertEquals("No graph is currently open in SteleKit.", result.firstText())
    }

    @Test
    fun `AmbiguousPageName toCallToolResult names the page and the match count`() {
        val result = McpToolError.AmbiguousPageName(name = "Project", matchCount = 3).toCallToolResult()

        assertEquals(true, result.isError)
        assertTrue("Project" in result.firstText())
        assertTrue("3" in result.firstText())
    }

    @Test
    fun `InboxFull toCallToolResult tells the caller the cap and that a human must review`() {
        val result = McpToolError.InboxFull(cap = 20).toCallToolResult()

        assertEquals(true, result.isError)
        assertTrue("20" in result.firstText())
        assertTrue("accept or reject" in result.firstText())
    }

    @Test
    fun `Timeout toCallToolResult names the tool and the timeout`() {
        val result = McpToolError.Timeout(toolName = "search_graph", timeoutMs = 15_000L).toCallToolResult()

        assertEquals(true, result.isError)
        assertTrue("search_graph" in result.firstText())
        assertTrue("15000" in result.firstText())
    }

    @Test
    fun `Internal toCallToolResult carries the domain error message`() {
        val result = McpToolError.Internal(DomainError.DatabaseError.ReadFailed("disk on fire")).toCallToolResult()

        assertEquals(true, result.isError)
        assertTrue("disk on fire" in result.firstText())
    }

    @Test
    fun `UnexpectedFailure toCallToolResult names the tool and exception class but not the raw message`() {
        val result = McpToolError.UnexpectedFailure(
            toolName = "read_page",
            cause = IllegalStateException("secret page content"),
        ).toCallToolResult()

        assertEquals(true, result.isError)
        assertTrue("read_page" in result.firstText())
        assertTrue("IllegalStateException" in result.firstText())
        assertFalse("secret page content" in result.firstText())
    }

    @Test
    fun `every McpToolError variant maps to a distinct message`() {
        val messages = listOf(
            McpToolError.GraphNotOpen,
            McpToolError.AmbiguousPageName("a", 2),
            McpToolError.InboxFull(20),
            McpToolError.Timeout("t", 1L),
            McpToolError.Internal(DomainError.DatabaseError.ReadFailed("x")),
            McpToolError.UnexpectedFailure("t", RuntimeException()),
        ).map { it.toCallToolResult().firstText() }

        assertEquals(messages.size, messages.toSet().size)
        assertNotEquals("", messages.first())
    }
}
