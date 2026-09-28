// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.git.model.gitApiJson
import dev.stapler.stelekit.platform.PlatformFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Real (not commonTest-doubled) tests for `WasmGitRepository` — since it only exists on
 * `wasmJsMain`, its own trivial-but-real behavior is exercised directly against a Ktor
 * `MockEngine`, per `WasmGitWriteServiceMockedIntegrationTest.kt`'s established convention (private
 * `hostConfig`/`gitLabHostConfig` fixtures, `jsonResponse` helper). Covers `IT-4.1.1-A/B/C` from
 * `project_plans/web-git-writeback/implementation/validation.md`.
 *
 * NOTE: see `WasmGitWriteServiceMockedIntegrationTest`'s doc comment for this repo's standing
 * caveat — this sandboxed dev environment has no headless Chrome available, so
 * `./gradlew :kmp:wasmJsBrowserTest` could not be run here to confirm these pass in a real browser.
 * Verified to compile via `./gradlew :kmp:compileTestKotlinWasmJs`; run `wasmJsBrowserTest` in a
 * browser-capable environment before relying on this file as a regression gate.
 */
class WasmGitRepositoryTest {

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

    private val config = GitConfig(
        graphId = "default",
        repoRoot = "/stelekit/default",
        wikiSubdir = "",
        authType = GitAuthType.GITHUB_OAUTH,
    )

    private fun MockRequestHandleScope.jsonResponse(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content = content, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    private fun buildRepository(
        engine: MockEngine,
        resolver: suspend (GitConfig) -> GitHostConfig? = { hostConfig },
    ): WasmGitRepository {
        val fileSystem = PlatformFileSystem()
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        return WasmGitRepository(writeService, client, fileSystem, resolver)
    }

    // ── IT-4.1.1-A ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-4_1_1-A isGitRepo returns true, init-stageSubdir-removeStaleLockFile are no-op successes, hasDetachedHead is always false`() =
        runTest {
            var requestCount = 0
            val engine = MockEngine { _ -> requestCount++; error("must not make any network call") }
            val repo = buildRepository(engine)

            assertTrue(repo.isGitRepo(config.repoRoot))
            assertEquals(Either.Right(Unit), repo.init(config.repoRoot))
            assertEquals(Either.Right(Unit), repo.stageSubdir(config))
            assertEquals(Either.Right(Unit), repo.removeStaleLockFile(config))
            assertEquals(false, repo.hasDetachedHead(config))
            assertEquals(0, requestCount, "none of these methods should make a network call")
        }

    @Test
    fun `IT-4_1_1-A status() reflects the dirty snapshot`() = runTest {
        val fileSystem = PlatformFileSystem()
        fileSystem.preload(config.repoRoot)
        val engine = MockEngine { _ -> error("status() must not make any network call") }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
        val writeService = WasmGitWriteService(client, fileSystem)
        val repo = WasmGitRepository(writeService, client, fileSystem) { hostConfig }

        val emptyStatus = repo.status(config).getOrNull()
        assertEquals(GitStatus(hasLocalChanges = false, untrackedFiles = emptyList(), modifiedFiles = emptyList()), emptyStatus)

        fileSystem.writeFile("${config.repoRoot}/pages/Foo.md", "# Foo\n")
        val dirtyStatus = repo.status(config).getOrNull()
        assertEquals(
            GitStatus(hasLocalChanges = true, untrackedFiles = emptyList(), modifiedFiles = listOf("pages/Foo.md")),
            dirtyStatus,
        )
    }

    // ── IT-4.1.1-B ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-4_1_1-B clone() returns NotSupported without making any network call`() = runTest {
        var requestCount = 0
        val engine = MockEngine { _ -> requestCount++; error("clone() must not make any network call") }
        val repo = buildRepository(engine)

        val result = repo.clone(
            url = "https://github.com/tstapler/steno-wiki.git",
            localPath = config.repoRoot,
            auth = GitAuth.None,
            onProgress = {},
        )

        assertEquals(Either.Left(DomainError.GitError.NotSupported("web")), result)
        assertEquals(0, requestCount, "clone() must never make a network call on web")
    }

    // ── IT-4.1.1-C ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-4_1_1-C log() maps GitHub commit-history responses to GitCommit`() = runTest {
        val engine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/commits"), "unexpected path: ${request.url.encodedPath}")
            assertTrue(request.url.encodedQuery.contains("sha=main"), "query was: ${request.url.encodedQuery}")
            assertTrue(request.url.encodedQuery.contains("per_page=10"), "query was: ${request.url.encodedQuery}")
            jsonResponse(
                """[{"sha":"c0ffee1","commit":{"message":"Fix bug\n\nExtended body","author":{"name":"Tyler","date":"2026-07-15T12:00:00Z"}}}]"""
            )
        }
        val repo = buildRepository(engine) { hostConfig }

        val result = repo.log(config, maxCount = 10)

        assertEquals(
            listOf(
                GitCommit(
                    sha = "c0ffee1",
                    shortMessage = "Fix bug",
                    authorName = "Tyler",
                    timestamp = Instant.parse("2026-07-15T12:00:00Z").toEpochMilliseconds(),
                ),
            ),
            result.getOrNull(),
        )
    }

    @Test
    fun `IT-4_1_1-C log() maps GitLab commit-history responses to GitCommit`() = runTest {
        val engine = MockEngine { request ->
            assertTrue(
                request.url.encodedPath.endsWith("/repository/commits"),
                "unexpected path: ${request.url.encodedPath}",
            )
            assertTrue(request.url.encodedQuery.contains("ref_name=main"), "query was: ${request.url.encodedQuery}")
            assertTrue(request.url.encodedQuery.contains("per_page=10"), "query was: ${request.url.encodedQuery}")
            jsonResponse(
                """[{"id":"c0ffee2","title":"Fix bug","author_name":"Tyler","authored_date":"2026-07-15T12:00:00Z"}]"""
            )
        }
        val repo = buildRepository(engine) { gitLabHostConfig }

        val result = repo.log(config, maxCount = 10)

        assertEquals(
            listOf(
                GitCommit(
                    sha = "c0ffee2",
                    shortMessage = "Fix bug",
                    authorName = "Tyler",
                    timestamp = Instant.parse("2026-07-15T12:00:00Z").toEpochMilliseconds(),
                ),
            ),
            result.getOrNull(),
        )
    }

    @Test
    fun `log() maps an unresolvable host config to AuthFailed without making a network call`() = runTest {
        var requestCount = 0
        val engine = MockEngine { _ -> requestCount++; error("must not make any network call") }
        val repo = buildRepository(engine) { null }

        val result = repo.log(config, maxCount = 10)

        assertTrue(result is Either.Left && result.value is DomainError.GitError.AuthFailed)
        assertEquals(0, requestCount)
    }
}
