// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.lfs.LfsPathPolicy
import dev.stapler.stelekit.git.lfs.WasmLfsRulesSource
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmLfsRulesSourceTest {
    private val host = GitHostConfig(
        type = GitHostType.GITHUB,
        owner = "tstapler",
        repo = "steno-wiki",
        branch = "main",
        token = "test-token",
        apiBase = "https://api.github.com/repos/tstapler/steno-wiki",
    )

    private val base = "/tstapler/steno-wiki/abc123"

    private fun client(requests: MutableList<String>, files: Map<String, String>, failWith: HttpStatusCode? = null) =
        HttpClient(
            MockEngine { request ->
                requests += request.url.encodedPath
                val relative = request.url.encodedPath.removePrefix(base)
                when {
                    failWith != null -> respond("", failWith)
                    relative in files -> respond(files.getValue(relative), HttpStatusCode.OK)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )

    @Test
    fun `load reads the root and every ancestor directory's gitattributes at the base ref`() = runTest {
        val requests = mutableListOf<String>()
        val source = WasmLfsRulesSource(
            client(requests, mapOf("/.gitattributes" to "*.png filter=lfs\n", "/a/b/.gitattributes" to "*.pdf filter=lfs\n")),
            host,
            "abc123",
        )

        val rules = (source.load(listOf("a/b/x.png")) as Either.Right).value

        assertEquals(listOf("$base/.gitattributes", "$base/a/.gitattributes", "$base/a/b/.gitattributes"), requests)
        val policy = LfsPathPolicy(rules)
        assertTrue(policy.isTracked("a/b/x.png"))
        assertTrue(policy.isTracked("a/b/y.pdf"))
        assertFalse(policy.isTracked("a/y.pdf"), "a/b's file does not apply to a/")
    }

    @Test
    fun `a 404 means no rules at that level`() = runTest {
        val source = WasmLfsRulesSource(client(mutableListOf(), emptyMap()), host, "abc123")

        val rules = (source.load(listOf("a/x.png")) as Either.Right).value

        assertFalse(LfsPathPolicy(rules).hasAnyRules)
    }

    @Test
    fun `a 503 fails closed with NetworkFailure`() = runTest {
        val source = WasmLfsRulesSource(client(mutableListOf(), emptyMap(), failWith = HttpStatusCode.ServiceUnavailable), host, "abc123")

        val result = source.load(listOf("x.png"))

        assertIs<DomainError.GitError.NetworkFailure>(assertIs<Either.Left<DomainError.GitError>>(result).value)
    }

    @Test
    fun `a local override wins over the remote copy and a null override means the file is gone`() = runTest {
        val requests = mutableListOf<String>()
        val remote = mapOf("/.gitattributes" to "*.png filter=lfs\n")

        val edited = WasmLfsRulesSource(client(requests, remote), host, "abc123", localOverrides = mapOf("" to "*.bin filter=lfs\n".encodeToByteArray()))
        val deleted = WasmLfsRulesSource(client(requests, remote), host, "abc123", localOverrides = mapOf("" to null))

        val editedPolicy = LfsPathPolicy((edited.load(listOf("x.png")) as Either.Right).value)
        val deletedPolicy = LfsPathPolicy((deleted.load(listOf("x.png")) as Either.Right).value)

        assertTrue(editedPolicy.isTracked("x.bin"))
        assertFalse(editedPolicy.isTracked("x.png"))
        assertFalse(deletedPolicy.hasAnyRules)
        assertEquals(emptyList(), requests, "overridden directories are never fetched")
    }

    @Test
    fun `the cache avoids refetching the same ref but a new ref is read again`() = runTest {
        val requests = mutableListOf<String>()
        val cache = mutableMapOf<String, String?>()
        val http = client(requests, mapOf("/.gitattributes" to "*.png filter=lfs\n"))

        WasmLfsRulesSource(http, host, "abc123", cache = cache).load(listOf("x.png"))
        WasmLfsRulesSource(http, host, "abc123", cache = cache).load(listOf("y.png"))
        assertEquals(1, requests.size)

        WasmLfsRulesSource(http, host, "def456", cache = cache).load(listOf("y.png"))
        assertEquals(2, requests.size, "a moved base ref is never served from the old cache entry")
    }
}
