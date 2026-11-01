// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A minimal in-process Git LFS server (batch API, basic transfer) for exercising the real client over real HTTP. */
class FakeLfsServer(private val requiredAuth: String? = null) : AutoCloseable {
    data class Request(val method: String, val path: String, val authorization: String?)

    val objects = ConcurrentHashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<Request>()

    /** When set, every PUT answers this status instead of storing the object. */
    @Volatile var failUploadsWith: Int? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> handle(ex) }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun uploads() = requests.count { it.method == "PUT" }

    private fun handle(ex: HttpExchange) {
        val auth = ex.requestHeaders.getFirst("Authorization")
        requests += Request(ex.requestMethod, ex.requestURI.path, auth)
        val path = ex.requestURI.path
        try {
            when {
                path == "/objects/batch" -> batch(ex, auth)
                ex.requestMethod == "PUT" && path.startsWith("/objects/") -> put(ex, path.removePrefix("/objects/"))
                ex.requestMethod == "GET" && path.startsWith("/objects/") -> get(ex, path.removePrefix("/objects/"))
                else -> reply(ex, 404, ByteArray(0))
            }
        } finally {
            ex.close()
        }
    }

    private fun batch(ex: HttpExchange, auth: String?) {
        if (requiredAuth != null && auth != requiredAuth) return reply(ex, 401, "{}".toByteArray())
        val body = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
        val operation = body["operation"]!!.jsonPrimitive.content
        val entries = (body["objects"] as JsonArray).map { it.jsonObject }
        val out = entries.joinToString(",") { o ->
            val oid = o["oid"]!!.jsonPrimitive.content
            val size = o["size"]!!.jsonPrimitive.content
            val have = objects.containsKey(oid)
            val href = "\"href\":\"$url/objects/$oid\",\"header\":{\"X-Fake\":\"1\"}"
            when {
                operation == "download" && have -> """{"oid":"$oid","size":$size,"actions":{"download":{$href}}}"""
                operation == "download" -> """{"oid":"$oid","size":$size,"error":{"code":404,"message":"missing"}}"""
                have -> """{"oid":"$oid","size":$size}"""
                else -> """{"oid":"$oid","size":$size,"actions":{"upload":{$href}}}"""
            }
        }
        reply(ex, 200, """{"transfer":"basic","objects":[$out]}""".toByteArray(), "application/vnd.git-lfs+json")
    }

    private fun put(ex: HttpExchange, oid: String) {
        failUploadsWith?.let { return reply(ex, it, ByteArray(0)) }
        val bytes = ex.requestBody.readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != oid) return reply(ex, 422, ByteArray(0))
        objects[oid] = bytes
        reply(ex, 200, ByteArray(0))
    }

    private fun get(ex: HttpExchange, oid: String) {
        val bytes = objects[oid] ?: return reply(ex, 404, ByteArray(0))
        reply(ex, 200, bytes, "application/octet-stream")
    }

    private fun reply(ex: HttpExchange, status: Int, body: ByteArray, type: String = "application/json") {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
    }

    override fun close() = server.stop(0)
}
