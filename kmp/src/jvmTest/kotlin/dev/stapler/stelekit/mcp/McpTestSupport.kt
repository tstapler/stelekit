// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeRequest
import io.modelcontextprotocol.kotlin.sdk.types.InitializeRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesResult
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.toJSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** In-memory [McpSuggestionSink] that records every proposal. */
internal class RecordingSuggestionSink : McpSuggestionSink {
    private val state = MutableStateFlow<Map<String, PendingLlmSuggestion>>(emptyMap())
    val proposed = mutableListOf<PendingLlmSuggestion>()

    override val pending: StateFlow<Map<String, PendingLlmSuggestion>> get() = state

    override fun propose(suggestion: PendingLlmSuggestion) {
        proposed += suggestion
        state.value = state.value + (suggestion.id to suggestion)
    }
}

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

/** A [GraphManager] with no graph open — enough for skeleton tests that never touch repositories. */
internal fun unopenedGraphManager(): GraphManager = GraphManager(
    platformSettings = StubSettings(),
    driverFactory = DriverFactory(),
    fileSystem = StubFileSystem(),
    defaultBackend = GraphBackend.IN_MEMORY,
)

/**
 * Minimal MCP client driving a [Server] over the SDK's in-memory [ChannelTransport], speaking raw
 * JSON-RPC so tests exercise the same request path a real host does without a socket/subprocess.
 */
@OptIn(ExperimentalMcpApi::class)
internal class McpTestClient private constructor(
    private val transport: ChannelTransport,
    val session: ServerSession,
    private val inbox: Channel<JSONRPCMessage>,
) {
    suspend fun initialize(clientName: String = "test-client", clientVersion: String = "1.0"): InitializeResult {
        val request = InitializeRequest(
            InitializeRequestParams(
                protocolVersion = LATEST_PROTOCOL_VERSION,
                capabilities = ClientCapabilities(),
                clientInfo = Implementation(name = clientName, version = clientVersion),
            ),
        )
        val result = call(request.toJSON()) as InitializeResult
        transport.send(JSONRPCNotification(method = "notifications/initialized"))
        return result
    }

    suspend fun listTools(): ListToolsResult =
        call(JSONRPCRequest(method = "tools/list")) as ListToolsResult

    suspend fun callTool(name: String): CallToolResult =
        call(CallToolRequest(CallToolRequestParams(name = name)).toJSON()) as CallToolResult

    suspend fun callTool(name: String, arguments: JsonObject): CallToolResult =
        call(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)).toJSON()) as CallToolResult

    suspend fun readResource(uri: String): ReadResourceResult =
        call(ReadResourceRequest(ReadResourceRequestParams(uri = uri)).toJSON()) as ReadResourceResult

    suspend fun listResources(): ListResourcesResult =
        call(JSONRPCRequest(method = "resources/list")) as ListResourcesResult

    suspend fun listResourceTemplates(): ListResourceTemplatesResult =
        call(JSONRPCRequest(method = "resources/templates/list")) as ListResourceTemplatesResult

    private suspend fun call(request: JSONRPCRequest): Any = withTimeout(RESPONSE_TIMEOUT_MS) {
        transport.send(request)
        while (true) {
            when (val message = inbox.receive()) {
                is JSONRPCResponse -> if (message.id == request.id) return@withTimeout message.result
                is JSONRPCError -> if (message.id == request.id) error("JSON-RPC error: ${message.error}")
                else -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun close() {
        transport.close()
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 10_000L

        suspend fun connect(server: Server): McpTestClient {
            val (client, serverSide) = ChannelTransport.createLinkedPair()
            val inbox = Channel<JSONRPCMessage>(Channel.UNLIMITED)
            client.onMessage { inbox.send(it) }
            val session = server.createSession(serverSide)
            client.start()
            return McpTestClient(client, session, inbox)
        }
    }
}

/** First text block of a tool result — every [McpToolError] renders as exactly one. */
internal fun CallToolResult.firstText(): String = (content.first() as TextContent).text
