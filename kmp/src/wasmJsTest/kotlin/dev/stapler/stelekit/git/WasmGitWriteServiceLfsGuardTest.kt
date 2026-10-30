// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.model.RepoRoot
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.lfs.LfsOid
import dev.stapler.stelekit.git.lfs.LfsPointer
import dev.stapler.stelekit.git.lfs.LfsPointerCodec
import dev.stapler.stelekit.git.lfs.LfsViolationReason
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.git.model.PendingCommit
import dev.stapler.stelekit.git.model.gitApiJson
import dev.stapler.stelekit.platform.PlatformFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The web half of the LFS safety floor, against the REAL [WasmGitWriteService] (real Ktor client, JSON and
 * [PlatformFileSystem] dirty state) with a MockEngine standing in for GitHub and GitLab. [WasmGitWriteService]
 * is the sole web guard: no blob or commit action may ever carry a raw binary for a path `.gitattributes`
 * stores with LFS, the rest of the dirty set still uploads, and the held files stay dirty.
 */
@OptIn(ExperimentalEncodingApi::class)
class WasmGitWriteServiceLfsGuardTest {
    private val rules = "assets/** filter=lfs\n"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(2_000) { 7 }
    private val pointer = LfsPointerCodec.serialize(
        LfsPointer(LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"), 3_000_000),
    )

    private val hostConfig = GitHostConfig(
        type = GitHostType.GITHUB,
        owner = "tstapler",
        repo = "steno-wiki",
        branch = "main",
        token = "test-token",
        apiBase = "https://api.github.com/repos/tstapler/steno-wiki",
    )
    private val gitLabHostConfig = GitHostConfig(
        type = GitHostType.GITLAB,
        owner = "tstapler",
        repo = "steno-wiki",
        branch = "main",
        token = "test-token",
        apiBase = "https://gitlab.com/api/v4/projects/tstapler%2Fsteno-wiki",
    )

    private fun freshGraphId(): String = "it-lfs-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun config(graphId: String, host: GitHostType = GitHostType.GITHUB) = GitConfig(
        graphId = graphId,
        repoRoot = RepoRoot("/stelekit/$graphId"),
        wikiSubdir = "",
        authType = if (host == GitHostType.GITHUB) GitAuthType.GITHUB_OAUTH else GitAuthType.NONE,
    )

    private fun body(request: HttpRequestData): String =
        (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun MockRequestHandleScope.json(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun client(engine: MockEngine) = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }

    private suspend fun newFileSystem(graphId: String): PlatformFileSystem =
        PlatformFileSystem().also { it.preload("/stelekit/$graphId") }

    /** Scripted GitHub: serves [attributes] at the repo root (404 elsewhere) and records every blob body. */
    private class GitHubScript(val attributes: String?, val attributesStatus: HttpStatusCode = HttpStatusCode.OK) {
        val blobContents = mutableListOf<ByteArray>()
        var treeBody = ""
        var blobRequests = 0
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun gitHubEngine(script: GitHubScript) = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/.gitattributes") && request.url.host == "raw.githubusercontent.com" -> when {
                script.attributesStatus != HttpStatusCode.OK -> respond("", script.attributesStatus)
                path == "/tstapler/steno-wiki/8f3c1a9/.gitattributes" && script.attributes != null -> respond(script.attributes, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
            path.endsWith("/git/blobs") -> {
                script.blobRequests++
                val encoded = Regex("\"content\":\"([^\"]*)\"").find(body(request))?.groupValues?.get(1).orEmpty()
                script.blobContents += Base64.Default.decode(encoded)
                json("""{"sha":"blob-${script.blobRequests}"}""", HttpStatusCode.Created)
            }
            path.endsWith("/git/trees") -> {
                script.treeBody = body(request)
                json("""{"sha":"tree-sha"}""", HttpStatusCode.Created)
            }
            path.endsWith("/git/commits") -> json("""{"sha":"commit-sha"}""", HttpStatusCode.Created)
            request.method.value == "PATCH" && path.contains("/git/refs/heads/main") -> json("""{"ref":"refs/heads/main"}""")
            else -> error("unexpected request ${request.method} ${request.url}")
        }
    }

    @Test
    fun `commit() posts a blob for the text note and none for a raw binary at an LFS-tracked path, which stays dirty`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFile("/stelekit/$graphId/pages/a.md", "# a\n")
        fs.writeFileBytes("/stelekit/$graphId/assets/p.png", png)
        val script = GitHubScript(rules)
        val service = WasmGitWriteService(client(gitHubEngine(script)), fs)

        val result = service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

        assertEquals(Either.Right(Unit), result)
        assertEquals(1, script.blobRequests, "only pages/a.md is uploaded")
        assertEquals("# a\n", script.blobContents.single().decodeToString())
        assertTrue(script.treeBody.contains("pages/a.md"), script.treeBody)
        assertTrue(!script.treeBody.contains("assets/p.png"), "the tree must not mention the held path: ${script.treeBody}")
        assertEquals(setOf("pages/a.md", "assets/p.png"), fs.getDirtySnapshot().keys, "commit() never clears the dirty set")
        assertEquals(listOf("assets/p.png"), service.lfsHeldViolations().map { it.path })
        assertEquals(LfsViolationReason.RawBinary, service.lfsHeldViolations().single().reason)
    }

    @Test
    fun `a valid pointer at a tracked path is uploaded like any other file`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFileBytes("/stelekit/$graphId/assets/p.png", pointer)
        val script = GitHubScript(rules)
        val service = WasmGitWriteService(client(gitHubEngine(script)), fs)

        service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

        assertEquals(1, script.blobRequests)
        assertEquals(pointer.decodeToString(), script.blobContents.single().decodeToString())
        assertTrue(service.lfsHeldViolations().isEmpty())
    }

    @Test
    fun `commit() fails closed with NetworkFailure and posts nothing when the rules cannot be read`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFile("/stelekit/$graphId/pages/a.md", "# a\n")
        fs.writeFileBytes("/stelekit/$graphId/assets/p.png", png)
        val script = GitHubScript(attributes = null, attributesStatus = HttpStatusCode.ServiceUnavailable)
        val service = WasmGitWriteService(client(gitHubEngine(script)), fs)

        val result = service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

        assertIs<DomainError.GitError.NetworkFailure>(assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertEquals(0, script.blobRequests, "nothing may be uploaded when the LFS rules are unknown")
        assertEquals(setOf("pages/a.md", "assets/p.png"), fs.getDirtySnapshot().keys, "the dirty set is unchanged")
    }

    @Test
    fun `a missing remote gitattributes means no rules, so everything uploads`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFileBytes("/stelekit/$graphId/assets/p.png", png)
        val script = GitHubScript(attributes = null)
        val service = WasmGitWriteService(client(gitHubEngine(script)), fs)

        service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

        assertEquals(1, script.blobRequests)
    }

    @Test
    fun `a locally edited gitattributes applies to the same commit`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFile("/stelekit/$graphId/.gitattributes", "*.png filter=lfs\n")
        fs.writeFileBytes("/stelekit/$graphId/x.png", png)
        val script = GitHubScript(attributes = null)
        val service = WasmGitWriteService(client(gitHubEngine(script)), fs)

        service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

        assertEquals(listOf("x.png"), service.lfsHeldViolations().map { it.path })
        assertEquals(1, script.blobRequests, "only .gitattributes itself uploads")
    }

    @Test
    fun `a push after a hold-back advances the ref and keeps the held file dirty`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFile("/stelekit/$graphId/pages/a.md", "# a\n")
        fs.writeFileBytes("/stelekit/$graphId/assets/p.png", png)
        val service = WasmGitWriteService(client(gitHubEngine(GitHubScript(rules))), fs)
        service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

        val result = service.push(hostConfig, fs.getPendingCommit())

        assertEquals(Either.Right(Unit), result)
        assertEquals(setOf("assets/p.png"), fs.getDirtySnapshot().keys, "the never-uploaded file must remain tracked as dirty")
        assertEquals(1, fs.dirtyFileCountFlow.value)
        assertEquals("commit-sha", fs.getBaseSha())
    }

    @Test
    fun `commit() stages nothing when every dirty file is held back`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFileBytes("/stelekit/$graphId/assets/p.png", png)
        val script = GitHubScript(rules)
        val service = WasmGitWriteService(client(gitHubEngine(script)), fs)

        val result = service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

        assertEquals(Either.Right(Unit), result)
        assertEquals(0, script.blobRequests)
        assertEquals(PendingCommit.None, fs.getPendingCommit(), "no empty commit object is staged")
    }

    @Test
    fun `pushViaGitLab leaves a raw binary at a tracked path out of the actions array and keeps it dirty`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFile("/stelekit/$graphId/pages/a.md", "# a\n")
        fs.writeFileBytes("/stelekit/$graphId/assets/p.png", png)
        var commitsBody = ""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains(".gitattributes") && path.contains("/repository/files/") ->
                    if (path.contains("/repository/files/.gitattributes/")) respond(rules, HttpStatusCode.OK) else respond("", HttpStatusCode.NotFound)
                path.endsWith("/repository/tree") -> json("[]")
                path.endsWith("/repository/commits") -> {
                    commitsBody = body(request)
                    json("""{"id":"gl-sha"}""", HttpStatusCode.Created)
                }
                else -> error("unexpected request ${request.url}")
            }
        }
        val service = WasmGitWriteService(client(engine), fs)

        val result = service.push(
            gitLabHostConfig,
            PendingCommit.None,
            WasmGitWriteService.GitLabPushContext(config(graphId, GitHostType.GITLAB), "8f3c1a9", "m"),
        )

        assertEquals(Either.Right(Unit), result)
        assertTrue(commitsBody.contains("pages/a.md"), commitsBody)
        assertTrue(!commitsBody.contains("assets/p.png"), "no action may name the held path: $commitsBody")
        assertEquals(setOf("assets/p.png"), fs.getDirtySnapshot().keys)
    }

    @Test
    fun `merge() never fetches or blobs a remote-changed LFS-tracked path`() = runTest {
        val graphId = freshGraphId()
        val fs = newFileSystem(graphId)
        fs.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")
        val fetchedRaw = mutableListOf<String>()
        val blobs = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.url.host == "raw.githubusercontent.com" && path.endsWith("/.gitattributes") ->
                    if (path == "/tstapler/steno-wiki/c0ffee2/.gitattributes") respond(rules, HttpStatusCode.OK) else respond("", HttpStatusCode.NotFound)
                request.url.host == "raw.githubusercontent.com" -> {
                    fetchedRaw += path
                    respond("# Bar (remote)\n", HttpStatusCode.OK)
                }
                path.endsWith("/git/ref/heads/main") -> json("""{"object":{"sha":"c0ffee2"}}""")
                path.contains("/compare/") -> json("""{"ahead_by":1,"files":[{"filename":"pages/Bar.md"},{"filename":"assets/r.png"}]}""")
                path.endsWith("/git/blobs") -> {
                    blobs += body(request)
                    json("""{"sha":"blob-${blobs.size}"}""", HttpStatusCode.Created)
                }
                path.endsWith("/git/trees") -> json("""{"sha":"tree-sha"}""", HttpStatusCode.Created)
                path.endsWith("/git/commits") -> json("""{"sha":"merge-sha"}""", HttpStatusCode.Created)
                else -> error("unexpected request ${request.url}")
            }
        }
        val service = WasmGitWriteService(client(engine), fs)

        val result = service.merge(config(graphId), hostConfig, baseSha = "8f3c1a9")

        assertTrue(result.isRight(), "$result")
        assertEquals(listOf("/tstapler/steno-wiki/c0ffee2/pages/Bar.md"), fetchedRaw, "the tracked remote path is never fetched as text")
        assertEquals(2, blobs.size, "the local note and the remote text note only")
    }

    @Test
    fun `no blob request body at a tracked path is ever a raw binary across 50 generated dirty sets`() = runTest {
        val random = Random(20260711)
        repeat(50) { iteration ->
            val graphId = freshGraphId()
            val fs = newFileSystem(graphId)
            val rawMarkers = mutableListOf<String>()
            val count = random.nextInt(1, 7)
            repeat(count) { i ->
                val underAssets = random.nextBoolean()
                val dir = if (underAssets) "assets" else "pages"
                val name = "$dir/f$iteration-$i" + if (underAssets) ".png" else ".md"
                val kind = random.nextInt(0, 3)
                val marker = "marker-$iteration-$i"
                val bytes = when (kind) {
                    0 -> marker.encodeToByteArray() + png
                    1 -> pointer
                    else -> "# note $marker\n".encodeToByteArray()
                }
                fs.writeFileBytes("/stelekit/$graphId/$name", bytes)
                // Anything but a pointer at a tracked path is "raw" to the guard, text notes under assets/ included.
                if (underAssets && kind != 1) rawMarkers += marker
            }
            val script = GitHubScript(rules)
            val service = WasmGitWriteService(client(gitHubEngine(script)), fs)

            val result = service.commit(config(graphId), hostConfig, baseSha = "8f3c1a9", message = "m")

            assertTrue(result.isRight(), "iteration $iteration: $result")
            for (content in script.blobContents) {
                val text = content.decodeToString()
                assertTrue(rawMarkers.none { it in text }, "iteration $iteration uploaded a raw binary: ${text.take(40)}")
            }
            assertEquals(rawMarkers.size, service.lfsHeldViolations().size, "iteration $iteration")
        }
    }

    @Test
    fun `the blob gate throws for a raw tracked path and accepts a pointer or an empty file`() {
        val policy = dev.stapler.stelekit.git.lfs.LfsPathPolicy(
            dev.stapler.stelekit.git.lfs.AttributeRuleSet(listOf(dev.stapler.stelekit.git.lfs.GitAttributesParser.parse(rules))),
        )
        val gate = dev.stapler.stelekit.git.lfs.WasmLfsBlobGate(policy)

        assertFailsWith<IllegalStateException> { gate.requireNotRawAtTrackedPath("assets/x.png", png) }
        gate.requireNotRawAtTrackedPath("assets/x.png", pointer)
        gate.requireNotRawAtTrackedPath("assets/x.png", ByteArray(0))
        gate.requireNotRawAtTrackedPath("pages/x.md", png) // untracked paths are not the gate's concern
    }
}
