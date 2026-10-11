// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Proves the full `search_graph` path end-to-end — ChannelTransport -> [Server.registerSearchGraphTool]
 * -> [dev.stapler.stelekit.repository.SqlDelightSearchRepository] -> a real on-disk SQLite database
 * -> back out over the transport — with no repository mocks anywhere in the chain. Story 2.1.1's
 * unit tests (`SearchGraphToolTest`) cover handler logic against a fake repository; this test is
 * the one place that would catch a real FTS/schema regression those fakes can't see.
 */
class McpEndToEndReadTest {

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/test"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    @Test
    fun `tools_call search_graph returns a real page from an on-disk graph through ChannelTransport`() = runBlocking {
        val graphManager = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.SQLDELIGHT,
        )
        val runId = System.nanoTime()

        try {
            val repoSet = graphManager.openGraph("/test/mcp-e2e-read-$runId")
            val now = Clock.System.now()
            repoSet.pageRepository.savePage(
                Page(uuid = PageUuid("00000000-0000-0000-0000-0000000000e2"), name = "Zettelkasten Method", createdAt = now, updatedAt = now),
            )

            val server = McpAdapter(graphManager, RecordingSuggestionSink(), { "test-client" }).buildServer()
            server.registerSearchGraphTool(graphManager)
            val client = McpTestClient.connect(server)
            try {
                client.initialize()
                val result = client.callTool(
                    "search_graph",
                    buildJsonObject { put("query", JsonPrimitive("Zettelkasten")) },
                )

                assertFalse(result.isError ?: false, "unexpected error: ${result.firstText()}")
                val ranked = Json.parseToJsonElement(result.firstText()).jsonObject["ranked"]!!.jsonArray
                assertTrue(ranked.isNotEmpty(), "expected at least one real hit, got: ${result.firstText()}")
                val names = ranked.map { it.jsonObject["name"]?.jsonPrimitive?.content }
                assertTrue("Zettelkasten Method" in names, "expected 'Zettelkasten Method' among $names")
            } finally {
                client.close()
                server.close()
            }
        } finally {
            graphManager.shutdown()
        }
    }
}
