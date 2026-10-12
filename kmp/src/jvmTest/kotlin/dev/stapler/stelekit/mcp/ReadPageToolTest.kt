@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.repository.RepositorySet
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ReadPageToolTest {

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
        server.registerReadPageTool(graphManager)
        return server
    }

    private fun page(
        uuid: String,
        name: String,
        sectionId: SectionId = SectionId.Global,
        isJournal: Boolean = false,
        journalDate: LocalDate? = null,
    ): Page {
        val now = Clock.System.now()
        return Page(
            uuid = PageUuid(uuid),
            name = name,
            createdAt = now,
            updatedAt = now,
            sectionId = sectionId,
            isJournal = isJournal,
            journalDate = journalDate,
        )
    }

    private fun block(uuid: String, pageUuid: String, content: String, position: String): Block {
        val now = Clock.System.now()
        return Block(
            uuid = BlockUuid(uuid),
            pageUuid = PageUuid(pageUuid),
            content = content,
            position = position,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `read_page by uuid returns the page's blocks via getPageByUuid`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(page("p1", "Zettelkasten Method"))
        repositorySet.blockRepository.saveBlock(block("b1", "p1", "atomic notes", "a0"))

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("read_page", buildJsonObject { put("uuid", "p1") })

            val json = Json.parseToJsonElement(result.firstText()).jsonObject
            assertEquals("p1", json["page"]!!.jsonObject["uuid"]!!.jsonPrimitive.content)
            assertEquals("Zettelkasten Method", json["page"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val blocks = json["blocks"]!!.jsonArray
            assertEquals(1, blocks.size)
            assertEquals("atomic notes", blocks[0].jsonObject["content"]!!.jsonPrimitive.content)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `read_page by name returns AmbiguousPageName when the same name matches pages in two different sections`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(page("p1", "Project Ideas", sectionId = SectionId.Global))
        repositorySet.pageRepository.savePage(page("p2", "project ideas", sectionId = SectionId.Named("work")))

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("read_page", buildJsonObject { put("name", "Project Ideas") })

            assertEquals(true, result.isError)
            assertTrue(
                result.firstText().contains("matches 2 pages"),
                "Expected an ambiguous-name message, got: ${result.firstText()}",
            )
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `read_page paginates a 300-block page to blockLimit 50 and sets hasMoreBlocks true`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(page("p1", "Big Page"))
        val blocks = (0 until 300).map { i -> block("b${i.toString().padStart(4, '0')}", "p1", "block $i", "p${i.toString().padStart(4, '0')}") }
        repositorySet.blockRepository.saveBlocks(blocks)

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("read_page", buildJsonObject { put("uuid", "p1"); put("blockLimit", 50) })

            val json = Json.parseToJsonElement(result.firstText()).jsonObject
            assertEquals(50, json["blocks"]!!.jsonArray.size)
            assertEquals(true, json["hasMoreBlocks"]!!.jsonPrimitive.boolean)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `read_page by date resolves via getJournalPageByDate regardless of the on-disk filename format`() = runBlocking {
        val (graphManager, repositorySet) = openTestGraph()
        repositorySet.pageRepository.savePage(
            page("p1", "2026_09_14", isJournal = true, journalDate = LocalDate(2026, 9, 14)),
        )

        val server = buildTestServer(graphManager)
        val client = McpTestClient.connect(server)
        try {
            client.initialize()
            val result = client.callTool("read_page", buildJsonObject { put("date", "2026-09-14") })

            val json = Json.parseToJsonElement(result.firstText()).jsonObject
            assertEquals("p1", json["page"]!!.jsonObject["uuid"]!!.jsonPrimitive.content)
            assertEquals("2026_09_14", json["page"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        } finally {
            client.close()
            server.close()
        }
    }
}
