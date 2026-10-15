// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class ParseMcpClientNameTest {
    @Test
    fun parseMcpClientName_should_extractSecondSegment() {
        assertEquals("client", parseMcpClientName("mcp:client:123"))
        assertEquals("", parseMcpClientName("mcp:"))
        assertEquals(null, parseMcpClientName("aho-corasick-matcher"))
    }
}
