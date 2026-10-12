@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.RepositorySet
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ListBacklinksToolTest {

    private suspend fun openTestGraph(): Pair<GraphManager, RepositorySet> {
        val graphManager = unopenedGraphManager()
        val repositorySet = graphManager.openGraph("/test/graph")
        return graphManager to repositorySet
    }

    private fun buildTestServer(graphManager: GraphManager): Server {
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0.0.1"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        server.registerListBacklinksTool(graphManager)
        return server
    }

    private fun page(uuid: String, name: String): Page {
        val now = Clock.System.now()
        return Page(uuid = PageUuid(uuid), name = name, createdAt = now, updatedAt = now)
    }

    private fun block(uuid: String, pageUuid: String, content: String, position: String, parentUuid: String? = null): Block {
        val now = Clock.System.now()
        return Block(
            uuid = BlockUuid(uuid),
            pageUuid = PageUuid(pageUuid),
            parentUuid = parentUuid?.let { BlockUuid(it) },
            content = content,
            position = position,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `list_backlinks returns the wiki-linked block from getLinkedReferences, not a child block`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(page("p1", "Zettelkasten Method"))
        repositorySet.pageRepository.savePage(page("p2", "Reading Notes"))
        // A plain outline child of p1's own block — structurally nested, but not a wiki-link
        // reference, so it must NOT show up as a backlink (outline-vs-reference disambiguation).
        repositorySet.blockRepository.saveBlock(block("b1", "p1", "Core idea", "a0"))
        repositorySet.blockRepository.saveBlock(block("b2", "p1", "A supporting detail", "a1", parentUuid = "b1"))
        // The actual semantic backlink: a [[wiki-link]] reference from an unrelated page.
        repositorySet.blockRepository.saveBlock(block("b3", "p2", "See [[Zettelkasten Method]] for details", "a0"))

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("list_backlinks", buildJsonObject { put("name", "Zettelkasten Method") })

            val json = Json.parseToJsonElement(result.firstText()).jsonObject
            val backlinks = json["backlinks"]!!.jsonArray
            assertEquals(1, backlinks.size)
            assertEquals("b3", backlinks[0].jsonObject["blockUuid"]!!.jsonPrimitive.content)
            assertTrue(
                backlinks[0].jsonObject["content"]!!.jsonPrimitive.content.contains("[[Zettelkasten Method]]"),
                "Expected the wiki-link block, not the plain outline child",
            )
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `list_backlinks returns an empty result without throwing when the target page has no backlinks`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(page("p1", "Lonely Page"))
        repositorySet.pageRepository.savePage(page("p2", "Unrelated Page"))
        repositorySet.blockRepository.saveBlock(block("b1", "p2", "Nothing to do with the lonely page", "a0"))

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("list_backlinks", buildJsonObject { put("name", "Lonely Page") })

            assertEquals(null, result.isError)
            val json = Json.parseToJsonElement(result.firstText()).jsonObject
            assertEquals(0, json["backlinks"]!!.jsonArray.size)
            assertEquals(0, json["count"]!!.jsonPrimitive.int)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `list_backlinks resolves a uuid to its page name via the shared resolvePage before searching`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(page("p1", "Zettelkasten Method"))
        repositorySet.pageRepository.savePage(page("p2", "Reading Notes"))
        repositorySet.blockRepository.saveBlock(block("b1", "p2", "See [[Zettelkasten Method]] for details", "a0"))

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("list_backlinks", buildJsonObject { put("uuid", "p1") })

            val json = Json.parseToJsonElement(result.firstText()).jsonObject
            assertEquals("Zettelkasten Method", json["pageName"]!!.jsonPrimitive.content)
            assertEquals(1, json["backlinks"]!!.jsonArray.size)
        } finally {
            client.close()
            server.close()
        }
    }
}
