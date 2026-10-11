// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.llm.LlmSuggestionInbox
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpAdapterTest {

    @Test
    fun `McpAdapter buildServer responds to initialize with an empty tools capability over ChannelTransport`() = runBlocking {
        val server = McpAdapter(unopenedGraphManager(), RecordingSuggestionSink(), { "test-client" }).buildServer()
        val client = McpTestClient.connect(server)
        try {
            val init = client.initialize()

            assertEquals("stelekit", init.serverInfo.name)
            assertNotNull(init.capabilities.tools, "tools capability must be advertised")
            assertTrue(client.listTools().tools.isEmpty(), "skeleton registers no tools")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `McpAdapter compiles and builds a Server with no LlmSuggestionInbox import anywhere in McpAdapter_kt`() {
        // Structural, binary-level: neither the constructor nor any field may take the raw inbox.
        val adapter = McpAdapter::class.java
        val referencedTypes = adapter.declaredFields.map { it.type } +
            adapter.declaredConstructors.flatMap { it.parameterTypes.toList() }
        assertFalse(
            referencedTypes.any { it == LlmSuggestionInbox::class.java },
            "McpAdapter must depend on McpSuggestionSink, not LlmSuggestionInbox: $referencedTypes",
        )

        // Source-level grep, when running with the repo checkout available (Gradle; not Bazel runfiles).
        val source = File("src/jvmMain/kotlin/dev/stapler/stelekit/mcp/McpAdapter.kt")
        if (source.exists()) {
            assertFalse("LlmSuggestionInbox" in source.readText(), "McpAdapter.kt must not mention LlmSuggestionInbox")
        }

        val server = McpAdapter(unopenedGraphManager(), RecordingSuggestionSink(), { "test-client" }).buildServer()
        assertNotNull(server)
    }
}
