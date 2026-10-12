// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.repository.RepositorySet
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PageResourceTest {

    private suspend fun openTestGraph(): Pair<GraphManager, RepositorySet> {
        val graphManager = unopenedGraphManager()
        val repositorySet = graphManager.openGraph("/test/graph")
        return graphManager to repositorySet
    }

    private fun buildTestServer(graphManager: GraphManager): Server {
        val server = Server(
            serverInfo = Implementation(name = "test", version = "0.0.1"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                ),
            ),
        )
        server.registerReadPageTool(graphManager)
        server.registerPageResource(graphManager)
        return server
    }

    private fun page(uuid: String, name: String): Page {
        val now = Clock.System.now()
        return Page(
            uuid = PageUuid(uuid),
            name = name,
            createdAt = now,
            updatedAt = now,
            sectionId = SectionId.Global,
        )
    }

    @Test
    fun `note uuid resource returns the same text read_page would return for the same page`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(page("p1", "Zettelkasten Method"))

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val toolResult = client.callTool("read_page", buildJsonObject { put("uuid", "p1") })
            val resourceResult = client.readResource("note://p1")

            val resourceText = (resourceResult.contents.single() as TextResourceContents).text
            assertEquals(toolResult.firstText(), resourceText)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `resources list is always empty regardless of how many pages exist`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repeat(8_000) { i -> repositorySet.pageRepository.savePage(page("p$i", "Page $i")) }

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.listResources()

            assertTrue(result.resources.isEmpty(), "resources/list must stay empty — pages are addressed by note://<uuid>, never enumerated")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `resources templates list returns exactly the note uuid template`() = runBlocking {
        val (graphManager, _) = openTestGraph()
        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.listResourceTemplates()

            assertEquals(1, result.resourceTemplates.size)
            assertEquals("note://{uuid}", result.resourceTemplates.single().uriTemplate)
        } finally {
            client.close()
            server.close()
        }
    }
}
