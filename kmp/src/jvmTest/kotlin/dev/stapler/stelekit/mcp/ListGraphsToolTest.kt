// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ListGraphsToolTest {

    @Test
    fun `list_graphs marks the active graph isActive true and every other graph false`() = runBlocking {
        val graphManager = unopenedGraphManager()
        val g1 = graphManager.addGraph("/test/personal")
        graphManager.addGraph("/test/work")
        graphManager.switchGraph(g1)

        val server = McpAdapter(graphManager, RecordingSuggestionSink(), { "test-client" }).buildServer()
        server.registerListGraphsTool(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("list_graphs")

            val graphs = Json.parseToJsonElement(result.firstText()).jsonArray
            assertEquals(2, graphs.size)
            val active = graphs.activeFlags()
            assertTrue(active[g1.value] == true, "the switched-to graph must be isActive:true")
            assertTrue(active.values.count { it } == 1, "exactly one graph must be active: $active")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `list_graphs returns an empty array without throwing when no graphs are registered`() = runBlocking {
        val graphManager = unopenedGraphManager()
        val server = McpAdapter(graphManager, RecordingSuggestionSink(), { "test-client" }).buildServer()
        server.registerListGraphsTool(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("list_graphs")

            assertFalse(result.isError ?: false)
            val graphs = Json.parseToJsonElement(result.firstText()).jsonArray
            assertTrue(graphs.isEmpty(), "expected an empty array, got: ${result.firstText()}")
        } finally {
            client.close()
            server.close()
        }
    }

    private fun JsonArray.activeFlags(): Map<String, Boolean> =
        associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["isActive"]!!.jsonPrimitive.boolean }
}
