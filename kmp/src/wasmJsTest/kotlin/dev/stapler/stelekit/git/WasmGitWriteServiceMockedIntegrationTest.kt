// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.WasmGitWriteService.Companion.installRetryPolicy
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mocked-network integration tests for the REAL `WasmGitWriteService` (not a commonTest double)
 * — exercises real Ktor client wiring, real JSON (de)serialization, and a real `PlatformFileSystem`
 * instance's `getDirtySnapshot()`/`dirtyFileCountFlow` state, against a Ktor `MockEngine` standing
 * in for GitHub's/GitLab's REST API. Covers `IT-3.1.1-A`, `IT-3.1.2-A/B` (Epic 3.1), and
 * `IT-3.2.1-A`/`IT-3.2.2-A` (Epic 3.2) from
 * `project_plans/web-git-writeback/implementation/validation.md`.
 *
 * NOTE: see `PlatformFileSystemDirtyTrackingIntegrationTest`'s doc comment for this repo's
 * standing caveat — this sandboxed dev environment has no headless Chrome available, so
 * `./gradlew :kmp:wasmJsBrowserTest` could not be run here to confirm these pass in a real
 * browser. Verified to compile via `./gradlew :kmp:compileTestKotlinWasmJs`; run
 * `wasmJsBrowserTest` in a browser-capable environment before relying on this file as a
 * regression gate.
 */
class WasmGitWriteServiceMockedIntegrationTest {

    private fun freshGraphId(): String = "it-write-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun requestBodyText(request: HttpRequestData): String {
        val content = request.body
        return if (content is OutgoingContent.ByteArrayContent) content.bytes().decodeToString() else ""
    }

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

    private fun MockRequestHandleScope.jsonResponse(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content = content, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    // ── IT-3.1.1-A ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-3_1_1-A commit() against a Ktor MockEngine posts blob, tree, commit in order and stages a commit`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")

        val requestPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestPaths += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/git/blobs") -> jsonResponse(
                    """{"sha":"blob-sha-1"}""",
                    HttpStatusCode.Created,
                )
                request.url.encodedPath.endsWith("/git/trees") -> {
                    val body = requestBodyText(request)
                    assertTrue(body.contains("\"base_tree\":\"8f3c1a9\""), "tree request body was: $body")
                    assertTrue(body.contains("blob-sha-1"), "tree request body was: $body")
                    assertTrue(body.contains("pages/Foo.md"), "tree request body was: $body")
                    jsonResponse("""{"sha":"tree-sha-1"}""", HttpStatusCode.Created)
                }
                request.url.encodedPath.endsWith("/git/commits") -> {
                    val body = requestBodyText(request)
                    assertTrue(body.contains("tree-sha-1"), "commit request body was: $body")
                    assertTrue(body.contains("8f3c1a9"), "commit request body was: $body")
                    jsonResponse("""{"sha":"commit-sha-1"}""", HttpStatusCode.Created)
                }
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val result = writeService.commit(config, hostConfig, baseSha = "8f3c1a9", message = "SteleKit: 2026-07-15")

        assertEquals(Either.Right(Unit), result)
        assertEquals(
            listOf(
                "/repos/tstapler/steno-wiki/git/blobs",
                "/repos/tstapler/steno-wiki/git/trees",
                "/repos/tstapler/steno-wiki/git/commits",
            ),
            requestPaths,
            "blob -> tree -> commit must be posted in that exact order",
        )
        // commit() must NOT clear the dirty set — only a successful push() does.
        assertEquals(setOf("pages/Foo.md"), fileSystem.getDirtySnapshot().keys)
    }

    // ── BLOCKER-1 regression ─────────────────────────────────────────────────────────────────

    /**
     * BLOCKER fix: a paranoid-mode (encrypted) dirty file — written via
     * `PlatformFileSystem.writeFileBytes`, which stores content in `bytesCache`, never `cache` —
     * must be committable, and its exact raw bytes (not a lossy String round-trip) must reach the
     * blob POST. Before the fix, `commit()`'s content reads went through `readFile()`, which only
     * ever consults `cache`; a bytesCache-only path always read back `null`, poisoning the whole
     * commit batch with `CommitFailed("No cached content for dirty path: ...")`.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun `BLOCKER-1 commit() against a Ktor MockEngine successfully blobs a writeFileBytes (paranoid-mode) dirty entry, posting its exact raw bytes as base64`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        // Non-UTF-8-safe byte sequence — a lossy String round-trip (the pre-fix readFile() path)
        // would corrupt this; getContentBytes() must hand it through unchanged.
        val encryptedBytes = byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0x7F, 0x80.toByte(), 0xC3.toByte())
        fileSystem.writeFileBytes("/stelekit/$graphId/pages/Secret.md.stek", encryptedBytes)

        var blobRequestBase64: String? = null
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/git/blobs") -> {
                    val body = requestBodyText(request)
                    val contentMatch = Regex("\"content\":\"([^\"]*)\"").find(body)
                        ?: error("blob POST body missing content field: $body")
                    blobRequestBase64 = contentMatch.groupValues[1]
                    jsonResponse("""{"sha":"blob-sha-1"}""", HttpStatusCode.Created)
                }
                request.url.encodedPath.endsWith("/git/trees") -> jsonResponse("""{"sha":"tree-sha-1"}""", HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/git/commits") -> jsonResponse("""{"sha":"commit-sha-1"}""", HttpStatusCode.Created)
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val result = writeService.commit(config, hostConfig, baseSha = "8f3c1a9", message = "SteleKit: 2026-07-15")

        assertEquals(Either.Right(Unit), result)
        assertEquals(
            kotlin.io.encoding.Base64.Default.encode(encryptedBytes),
            blobRequestBase64,
            "the exact raw paranoid-mode bytes must be base64-encoded onto the wire, uncorrupted by any lossy String round-trip",
        )
    }

    // ── IT-3.1.2-A ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-3_1_2-A fetch() reports hasRemoteChanges=false and push() PATCHes then clears the dirty set inside GitWriteLock`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")

        var patchCount = 0
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/git/ref/heads/main") ->
                    jsonResponse("""{"object":{"sha":"8f3c1a9"}}""")
                request.method == HttpMethod.Patch && request.url.encodedPath.endsWith("/git/refs/heads/main") -> {
                    patchCount++
                    val body = requestBodyText(request)
                    assertTrue(body.contains("\"sha\":\"c0ffee1\""), "patch body was: $body")
                    assertTrue(body.contains("\"force\":false"), "patch body was: $body")
                    jsonResponse("{}")
                }
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)

        val fetchResult = writeService.fetch(hostConfig, baseSha = "8f3c1a9")
        assertEquals(FetchResult(hasRemoteChanges = false, remoteCommitCount = 0), fetchResult.getOrNull())

        val pushResult = writeService.push(
            hostConfig,
            PendingCommit.Staged(commitSha = "c0ffee1", treeSha = "7ea5e11"),
        )

        assertEquals(Either.Right(Unit), pushResult)
        assertEquals(1, patchCount, "the ref PATCH must be sent exactly once")
        assertTrue(fileSystem.getDirtySnapshot().isEmpty(), "push must clear the dirty set on success")
        assertEquals(0, fileSystem.dirtyFileCountFlow.value)
    }

    // ── IT-3.1.2-B ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-3_1_2-B fetch() reports remoteCommitCount via the compare API when the ref has moved`() = runTest {
        val fileSystem = PlatformFileSystem()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/git/ref/heads/main") ->
                    jsonResponse("""{"object":{"sha":"c0ffee2"}}""")
                request.url.encodedPath.contains("/compare/") -> {
                    assertTrue(
                        request.url.encodedPath.endsWith("/compare/8f3c1a9...c0ffee2"),
                        "unexpected compare path: ${request.url.encodedPath}",
                    )
                    jsonResponse("""{"ahead_by":2,"files":[]}""")
                }
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)

        val result = writeService.fetch(hostConfig, baseSha = "8f3c1a9")

        assertEquals(FetchResult(hasRemoteChanges = true, remoteCommitCount = 2), result.getOrNull())
    }

    // ── IT-3.2.1-A ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-3_2_1-A commit() (GitLab) makes no network call, and pushViaGitLab sends exactly one POST with both actions, clearing the dirty set on 201`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")
        fileSystem.deleteFile("/stelekit/$graphId/pages/Bar.md")

        var requestCount = 0
        val engine = MockEngine { request ->
            requestCount++
            error("unexpected request to ${request.url}")
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.NONE,
        )

        // Task 3.2.1d: commit() is a local-only no-op for GitLab — no network call at all.
        val commitResult = writeService.commit(config, gitLabHostConfig, baseSha = "8f3c1a9", message = "SteleKit: 2026-07-15")
        assertEquals(Either.Right(Unit), commitResult)
        assertEquals(0, requestCount, "commit() must not make any network call for GitLab")

        // push() re-derives the GitLab commits-API request fresh from the current dirty set at
        // push time (via GitLabPushContext) rather than resuming anything commit() staged. It
        // first fetches the real base-tree membership (BLOCKER-2 fix) — pages/Foo.md and
        // pages/Bar.md both already exist in this mocked tree, so both resolve to "update"/
        // "delete" respectively (not "create"), matching the assertions below.
        val pushEngine = MockEngine { req ->
            requestCount++
            when {
                req.url.encodedPath.endsWith("/repository/tree") -> {
                    assertEquals("8f3c1a9", req.url.parameters["ref"], "tree fetch must use context.baseSha as ref")
                    jsonResponse("""[{"path":"pages/Foo.md"},{"path":"pages/Bar.md"}]""")
                }
                req.url.encodedPath.endsWith("/repository/commits") -> {
                    val body = requestBodyText(req)
                    assertTrue(body.contains("\"start_sha\":\"8f3c1a9\""), "push body was: $body")
                    assertTrue(body.contains("\"action\":\"update\""), "push body was: $body")
                    assertTrue(body.contains("\"action\":\"delete\""), "push body was: $body")
                    assertTrue(body.contains("pages/Foo.md"), "push body was: $body")
                    assertTrue(body.contains("pages/Bar.md"), "push body was: $body")
                    jsonResponse("""{"id":"a1b2c3d4"}""", HttpStatusCode.Created)
                }
                else -> error("unexpected path: ${req.url.encodedPath}")
            }
        }
        val pushClient = HttpClient(pushEngine) { install(ContentNegotiation) { json(gitApiJson) } }
        val pushWriteService = WasmGitWriteService(pushClient, fileSystem)

        val pushResult = pushWriteService.push(
            gitLabHostConfig,
            PendingCommit.None,
            WasmGitWriteService.GitLabPushContext(config = config, baseSha = "8f3c1a9", message = "SteleKit: 2026-07-15"),
        )

        assertEquals(Either.Right(Unit), pushResult)
        assertTrue(fileSystem.getDirtySnapshot().isEmpty(), "push must clear the dirty set on success")
    }

    // ── BLOCKER-2 regression ─────────────────────────────────────────────────────────────────

    @Test
    fun `BLOCKER-2 pushViaGitLab classifies a genuinely new page as create, not update, by consulting the real repository tree instead of dirty_keys`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        // A brand-new page, never present on the remote — the BLOCKER-2 bug's old
        // `existingPaths = dirty.keys` behavior would misclassify this as "update" and GitLab
        // would reject it (400), which classifyGitLabPushConflict then misreports as a conflict.
        fileSystem.writeFile("/stelekit/$graphId/pages/NewPage.md", "# New\n")

        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.NONE,
        )

        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/repository/tree") -> {
                    assertEquals("8f3c1a9", req.url.parameters["ref"])
                    // Tree fetch returns an empty/partial tree that does NOT contain the new page.
                    jsonResponse("""[{"path":"pages/Existing.md"}]""")
                }
                req.url.encodedPath.endsWith("/repository/commits") -> {
                    val body = requestBodyText(req)
                    assertTrue(body.contains("\"action\":\"create\""), "commits POST body was: $body")
                    assertTrue(body.contains("pages/NewPage.md"), "commits POST body was: $body")
                    assertTrue(
                        !body.contains("\"action\":\"update\""),
                        "a brand-new page must never be classified update: $body",
                    )
                    jsonResponse("""{"id":"newcommitsha"}""", HttpStatusCode.Created)
                }
                else -> error("unexpected path: ${req.url.encodedPath}")
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)

        val pushResult = writeService.push(
            gitLabHostConfig,
            PendingCommit.None,
            WasmGitWriteService.GitLabPushContext(config = config, baseSha = "8f3c1a9", message = "SteleKit: 2026-07-15"),
        )

        assertEquals(Either.Right(Unit), pushResult)
        assertTrue(fileSystem.getDirtySnapshot().isEmpty(), "push must clear the dirty set on success")
    }

    // ── IT-3.2.2-A ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-3_2_2-A fetch()-mergeGitLab (GitLab) against MockEngine's compare endpoint reports remote changes and auto-merges non-overlapping paths`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")

        val engine = MockEngine { request ->
            assertTrue(
                request.url.encodedPath.endsWith("/repository/compare"),
                "unexpected path: ${request.url.encodedPath}",
            )
            assertTrue(request.url.encodedQuery.contains("from=8f3c1a9"), "query was: ${request.url.encodedQuery}")
            assertTrue(request.url.encodedQuery.contains("to=main"), "query was: ${request.url.encodedQuery}")
            jsonResponse(
                """{"commits":[{"id":"c0ffee2"}],"diffs":[{"new_path":"pages/Bar.md"}]}"""
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)

        val fetchResult = writeService.fetch(gitLabHostConfig, baseSha = "8f3c1a9")
        assertEquals(FetchResult(hasRemoteChanges = true, remoteCommitCount = 1), fetchResult.getOrNull())

        // Local dirty set is {"pages/Foo.md"}, remote diff is {"pages/Bar.md"} — disjoint, so
        // this must auto-merge (non-conflicting) with no tree-rebuild step (Story 3.2.2 AC).
        val mergeResult = writeService.mergeGitLab(gitLabHostConfig, baseSha = "8f3c1a9")
        assertEquals(
            MergeResult(hasConflicts = false, conflicts = emptyList(), changedFiles = listOf("pages/Bar.md")),
            mergeResult.getOrNull(),
        )
    }

    @Test
    fun `IT-3_2_2-B mergeGitLab (GitLab) returns MergeConflict for an overlapping path via MockEngine's compare endpoint`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")

        val engine = MockEngine { _ ->
            jsonResponse("""{"commits":[{"id":"c0ffee2"}],"diffs":[{"new_path":"pages/Foo.md"}]}""")
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)

        val mergeResult = writeService.mergeGitLab(gitLabHostConfig, baseSha = "8f3c1a9")

        assertEquals(
            Either.Left(DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf("pages/Foo.md"))),
            mergeResult,
        )
    }

    // ── IT-3.3.1-A ────────────────────────────────────────────────────────────────────────────
    //
    // NOTE: `WasmGitRepository` (the `GitRepository` actual that would let this be driven through
    // the unmodified `GitSyncService`) is Epic 4.1's deliverable, not this epic's — these tests
    // exercise the real `WasmGitWriteService` (`merge()`/`checkoutFile()`) directly instead, which
    // is exactly the surface Epic 4.1's `WasmGitRepository` is expected to delegate to.

    @Test
    fun `IT-3_3_1-A merge() (GitHub) auto-merges non-overlapping remote changes, fetching raw content via MockEngine and staging a fresh commit layered on the new remote head`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")

        val blobBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/git/ref/heads/main") ->
                    jsonResponse("""{"object":{"sha":"c0ffee2"}}""")
                request.url.encodedPath.contains("/compare/") -> {
                    assertTrue(
                        request.url.encodedPath.endsWith("/compare/8f3c1a9...c0ffee2"),
                        "unexpected compare path: ${request.url.encodedPath}",
                    )
                    jsonResponse("""{"ahead_by":1,"files":[{"filename":"pages/Bar.md"}]}""")
                }
                request.url.host == "raw.githubusercontent.com" -> {
                    assertEquals(
                        "/tstapler/steno-wiki/c0ffee2/pages/Bar.md",
                        request.url.encodedPath,
                        "unexpected raw-content path",
                    )
                    respond(content = "# Bar (remote)\n", status = HttpStatusCode.OK)
                }
                request.url.encodedPath.endsWith("/git/blobs") -> {
                    blobBodies += requestBodyText(request)
                    jsonResponse("""{"sha":"blob-sha-${blobBodies.size}"}""", HttpStatusCode.Created)
                }
                request.url.encodedPath.endsWith("/git/trees") -> {
                    val body = requestBodyText(request)
                    assertTrue(body.contains("\"base_tree\":\"c0ffee2\""), "tree body was: $body")
                    assertTrue(body.contains("pages/Foo.md"), "tree body was: $body")
                    assertTrue(body.contains("pages/Bar.md"), "tree body was: $body")
                    jsonResponse("""{"sha":"tree-sha-merge"}""", HttpStatusCode.Created)
                }
                request.url.encodedPath.endsWith("/git/commits") -> {
                    val body = requestBodyText(request)
                    assertTrue(body.contains("tree-sha-merge"), "commit body was: $body")
                    assertTrue(body.contains("c0ffee2"), "commit body was: $body")
                    jsonResponse("""{"sha":"merge-commit-sha"}""", HttpStatusCode.Created)
                }
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val result = writeService.merge(config, hostConfig, baseSha = "8f3c1a9")

        assertEquals(
            MergeResult(hasConflicts = false, conflicts = emptyList(), changedFiles = listOf("pages/Bar.md")),
            result.getOrNull(),
        )
        assertEquals(2, blobBodies.size, "one blob for the local dirty write, one for the fetched remote content")
        // applyRemoteContent must NOT mark pages/Bar.md dirty — only pages/Foo.md (the real local
        // edit) may remain in the dirty set.
        assertEquals(setOf("pages/Foo.md"), fileSystem.getDirtySnapshot().keys)
    }

    // ── IT-3.3.2-A ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-3_3_2-A checkoutFile(REMOTE) fetches raw content via MockEngine and writes it via the dirty-tracked writeFile path`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo (local)\n")
        // Simulate a prior successful push already having cleared the dirty set before this
        // conflict-resolution attempt begins.
        fileSystem.clearDirtySet(newBaseSha = "8f3c1a9")

        val engine = MockEngine { request ->
            assertEquals("raw.githubusercontent.com", request.url.host)
            assertEquals("/tstapler/steno-wiki/c0ffee2/pages/Foo.md", request.url.encodedPath)
            respond(content = "# Foo (remote)\n", status = HttpStatusCode.OK)
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val result = writeService.checkoutFile(
            config,
            hostConfig,
            filePath = "pages/Foo.md",
            remoteRef = "c0ffee2",
            side = MergeSide.REMOTE,
        )

        assertEquals(Either.Right(Unit), result)
        assertEquals("# Foo (remote)\n", fileSystem.readFile("/stelekit/$graphId/pages/Foo.md"))
        assertEquals(
            setOf("pages/Foo.md"),
            fileSystem.getDirtySnapshot().keys,
            "checkoutFile(REMOTE) must dirty-track the resolution so the next commit() includes it",
        )
    }

    @Test
    fun `IT-3_3_2-A checkoutFile(LOCAL) is a no-op - no network call, content and dirty set untouched`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo (local)\n")
        fileSystem.clearDirtySet(newBaseSha = "8f3c1a9")

        var requestCount = 0
        val engine = MockEngine { _ -> requestCount++; error("checkoutFile(LOCAL) must not make any network call") }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val result = writeService.checkoutFile(
            config,
            hostConfig,
            filePath = "pages/Foo.md",
            remoteRef = "c0ffee2",
            side = MergeSide.LOCAL,
        )

        assertEquals(Either.Right(Unit), result)
        assertEquals(0, requestCount)
        assertEquals("# Foo (local)\n", fileSystem.readFile("/stelekit/$graphId/pages/Foo.md"))
        assertTrue(fileSystem.getDirtySnapshot().isEmpty())
    }

    // ── IT-3.3.2-B ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-3_3_2-B scripted two-writer race - a local edit plus an out-of-band remote edit to the same file surfaces as MergeConflict, never a silent overwrite or drop`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo (local edit)\n")

        val engine = MockEngine { request ->
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/git/ref/heads/main") ->
                    jsonResponse("""{"object":{"sha":"c0ffee2"}}""")
                request.url.encodedPath.contains("/compare/") ->
                    // The "second writer": an out-of-band push already advanced the remote head
                    // with its own edit to the SAME file this attempt has dirty locally.
                    jsonResponse("""{"ahead_by":1,"files":[{"filename":"pages/Foo.md"}]}""")
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val result = writeService.merge(config, hostConfig, baseSha = "8f3c1a9")

        assertEquals(
            Either.Left(DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf("pages/Foo.md"))),
            result,
        )
        // Neither silently overwritten nor silently dropped: local content and dirty state are untouched.
        assertEquals("# Foo (local edit)\n", fileSystem.readFile("/stelekit/$graphId/pages/Foo.md"))
        assertEquals(setOf("pages/Foo.md"), fileSystem.getDirtySnapshot().keys)
    }

    // ── IT-3.4.1-A ────────────────────────────────────────────────────────────────────────────

    /**
     * Exercises the REAL [WasmGitWriteService.installRetryPolicy]-configured [HttpRequestRetry]
     * plugin (not a hand-rolled double, per validation.md's rationale — this is the only place a
     * wrong status code or a typo'd header name wired into `retryIf` would actually be caught)
     * against a `MockEngine` standing in for GitHub. The first blob POST responds `429` with
     * `Retry-After: 0` (a real, but effectively-instant, retry) and succeeds on the plugin's own
     * retry; `commit()` still completes for an 8-file dirty set. A short `delay(20)` inside the
     * mock handler holds each blob "in flight" long enough that the [Task 3.4.1c] bounded
     * `Semaphore(3)` concurrency guard is genuinely observable (never more than 3 overlapping
     * calls) rather than accidentally passing because everything happened to run sequentially.
     */
    @Test
    fun `IT-3_4_1-A the real HttpRequestRetry plugin retries a 429-then-200 blob POST and commit() completes with no more than 3 concurrent blob POSTs for an 8-file dirty set`() = runTest {
        val graphId = freshGraphId()
        val fileSystem = PlatformFileSystem()
        fileSystem.preload("/stelekit/$graphId")
        val filePaths = (1..8).map { "pages/File$it.md" }
        filePaths.forEach { path -> fileSystem.writeFile("/stelekit/$graphId/$path", "# $path\n") }

        var inFlight = 0
        var maxInFlight = 0
        var blobAttempts = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/git/blobs") -> {
                    inFlight++
                    maxInFlight = maxOf(maxInFlight, inFlight)
                    blobAttempts++
                    val attemptNumber = blobAttempts
                    // Hold the "connection" open briefly so overlapping in-flight calls are
                    // actually observable — without this, a naive sequential implementation and
                    // a correctly-bounded-concurrent one would be indistinguishable here.
                    delay(20)
                    inFlight--
                    if (attemptNumber == 1) {
                        respond(
                            content = "",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.RetryAfter, "0"),
                        )
                    } else {
                        jsonResponse("""{"sha":"blob-sha-$attemptNumber"}""", HttpStatusCode.Created)
                    }
                }
                request.url.encodedPath.endsWith("/git/trees") -> jsonResponse("""{"sha":"tree-sha-1"}""", HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/git/commits") -> jsonResponse("""{"sha":"commit-sha-1"}""", HttpStatusCode.Created)
                else -> error("unexpected request to ${request.url}")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(gitApiJson) }
            installRetryPolicy()
        }
        val writeService = WasmGitWriteService(client, fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val result = writeService.commit(config, hostConfig, baseSha = "8f3c1a9", message = "SteleKit: 2026-07-15")

        assertEquals(Either.Right(Unit), result)
        assertEquals(9, blobAttempts, "8 files + 1 retried 429 attempt = 9 total blob POST attempts")
        assertTrue(maxInFlight <= 3, "no more than 3 concurrent blob POSTs, observed max was $maxInFlight")
        assertTrue(maxInFlight >= 2, "must show genuine overlap, not an accidentally-sequential pass (observed max was $maxInFlight)")
    }
}
