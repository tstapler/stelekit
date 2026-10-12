// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chains every real seam with no in-memory transport: child JVM running [main] of
 * `McpLauncherMain` -> its stdio -> byte proxy -> Unix domain socket -> [McpSocketServer] ->
 * [McpAdapter] -> real on-disk SQLite graph. Proves `search_graph`/`read_page` return real data
 * to an external process the way an MCP host would see them.
 */
class McpRealSubprocessSmokeTest {

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
    fun `real launcher subprocess gets search_graph and read_page data over stdio and a unix socket`() =
        withTempHome { home ->
            runBlocking {
                val graphManager = GraphManager(
                    platformSettings = StubSettings(),
                    driverFactory = DriverFactory(),
                    fileSystem = StubFileSystem(),
                    defaultBackend = GraphBackend.SQLDELIGHT,
                )
                var socketServer: McpSocketServer? = null
                val runId = System.nanoTime()
                try {
                    val repoSet = graphManager.openGraph("/test/mcp-subprocess-$runId")
                    val now = Clock.System.now()
                    val pageUuid = "00000000-0000-0000-0000-0000000000a1"
                    repoSet.pageRepository.savePage(
                        Page(uuid = PageUuid(pageUuid), name = "Smoke Test Page", createdAt = now, updatedAt = now),
                    )

                    val identity = McpClientIdentity()
                    val server = McpAdapter(graphManager, RecordingSuggestionSink(), identity::resolve).buildServer()
                    val bound = McpSocketServer(GraphId("smoke"), server, identity)
                    socketServer = bound
                    bound.start()

                    val process = ProcessBuilder(
                        File(System.getProperty("java.home"), "bin/java").path,
                        "-Duser.home=$home",
                        "-cp", System.getProperty("java.class.path"),
                        "dev.stapler.stelekit.mcp.McpLauncherMainKt",
                        "--graph-id", "smoke",
                    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

                    try {
                        val stdin = process.outputStream.bufferedWriter()
                        val stdout = process.inputStream.bufferedReader()

                        fun rpc(frame: String): kotlinx.serialization.json.JsonObject {
                            stdin.write(frame)
                            stdin.write("\n")
                            stdin.flush()
                            return Json.parseToJsonElement(stdout.readResponse()).jsonObject
                        }

                        val init = rpc(
                            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"smoke-client","version":"1"}}}""",
                        )
                        assertTrue(init["result"]!!.jsonObject.containsKey("protocolVersion"), "initialize: $init")
                        stdin.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n")
                        stdin.flush()

                        val list = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
                        val toolNames = list["result"]!!.jsonObject["tools"]!!.jsonArray
                            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                        assertTrue(
                            toolNames.containsAll(
                                listOf("search_graph", "read_page", "list_backlinks", "list_graphs", "propose_block_edit", "propose_new_page"),
                            ),
                            "tools/list: $toolNames",
                        )

                        val search = rpc(
                            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_graph","arguments":{"query":"Smoke"}}}""",
                        )
                        assertTrue(search.toString().contains("Smoke Test Page"), "search_graph: $search")

                        val read = rpc(
                            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"read_page","arguments":{"name":"Smoke Test Page"}}}""",
                        )
                        assertTrue(read.toString().contains("Smoke Test Page"), "read_page: $read")
                        assertEquals(null, read["error"], "read_page error: $read")
                    } finally {
                        process.destroy()
                        process.waitFor(10, TimeUnit.SECONDS)
                        if (process.isAlive) process.destroyForcibly()
                    }
                } finally {
                    graphManager.shutdown()
                }
                socketServer.stop()
            }
        }

    /** Reads the next non-empty line, skipping any server-initiated notifications (no `"id"`). */
    private fun BufferedReader.readResponse(): String {
        while (true) {
            val line = readLine() ?: error("launcher stdout closed before a response arrived")
            if (line.isBlank()) continue
            if (Json.parseToJsonElement(line).jsonObject.containsKey("id")) return line
        }
    }
}
