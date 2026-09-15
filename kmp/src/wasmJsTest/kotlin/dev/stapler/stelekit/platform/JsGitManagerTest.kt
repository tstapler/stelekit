// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.GitConfigRepository
import dev.stapler.stelekit.git.WasmGitRepository
import dev.stapler.stelekit.git.WasmGitWriteService
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.git.model.gitApiJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 4.2 (Story 4.2.1): `JsGitManager` delegate tests — covers `IT-4.2.1-A/B/C` from
 * `project_plans/web-git-writeback/implementation/validation.md`. Follows
 * `WasmGitWriteServiceMockedIntegrationTest.kt`'s established MockEngine convention (private
 * `hostConfig` fixture, `jsonResponse` helper, real `PlatformFileSystem`/`WasmGitWriteService`
 * instances — never commonTest doubles for the wasmJs-only classes under test).
 *
 * NOTE: see `WasmGitWriteServiceMockedIntegrationTest`'s doc comment for this repo's standing
 * caveat — this sandboxed dev environment has no headless Chrome available, so
 * `./gradlew :kmp:wasmJsBrowserTest` could not be run here to confirm these pass in a real
 * browser. Verified to compile via `./gradlew :kmp:compileTestKotlinWasmJs`; run
 * `wasmJsBrowserTest` in a browser-capable environment before relying on this file as a
 * regression gate.
 */
class JsGitManagerTest {

    private fun freshGraphId(): String = "it-jsgm-${Random.nextInt(0, Int.MAX_VALUE)}"

    private val hostConfig = GitHostConfig(
        type = GitHostType.GITHUB,
        owner = "tstapler",
        repo = "steno-wiki",
        branch = "main",
        token = "test-token",
        apiBase = "https://api.github.com/repos/tstapler/steno-wiki",
    )

    private fun MockRequestHandleScope.jsonResponse(content: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(content = content, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    /** Minimal [GitConfigRepository] test double — returns a fixed [config] for every graph id. */
    private class FakeGitConfigRepository(private val config: GitConfig?) : GitConfigRepository {
        override suspend fun getConfig(graphId: String): Either<DomainError, GitConfig?> = config.right()
        override suspend fun saveConfig(config: GitConfig): Either<DomainError, Unit> = Unit.right()
        override suspend fun deleteConfig(graphId: String): Either<DomainError, Unit> = Unit.right()
        override fun observeConfig(graphId: String): Flow<Either<DomainError, GitConfig?>> = flowOf(config.right())
    }

    // ── IT-4.2.1-A ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-4_2_1-A with no GitConfig saved, all five methods return the existing NOT_SUPPORTED error byte-for-byte unchanged`() =
        runTest {
            val fileSystem = PlatformFileSystem()
            val engine = MockEngine { error("no GitConfig means no network call should ever be attempted") }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
            val writeService = WasmGitWriteService(client, fileSystem)
            val manager = JsGitManager(
                graphId = { "default" },
                configRepository = FakeGitConfigRepository(config = null),
                fileSystem = fileSystem,
                gitWriteService = writeService,
                configResolver = { error("must not be reached when no GitConfig is saved") },
            )

            assertEquals(
                GitResult.Error("Git sync is not available on the web platform"),
                manager.commit("msg"),
            )
            assertEquals(
                DomainError.NetworkError.HttpError(501, "Git sync is not available on the web platform").left(),
                manager.push(),
            )
            assertEquals(
                DomainError.NetworkError.HttpError(501, "Git sync is not available on the web platform").left(),
                manager.pull(),
            )
            assertEquals(
                GitResult.Error("Git sync is not available on the web platform"),
                manager.status(),
            )
            assertEquals(
                GitResult.Error("Git sync is not available on the web platform"),
                manager.isDirty(),
            )
        }

    // ── IT-4.2.1-B ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-4_2_1-B with a configured GitConfig, JsGitManager push() delegates to the same WasmGitWriteService push() WasmGitRepository push() calls`() =
        runTest {
            val graphId = freshGraphId()
            val fileSystem = PlatformFileSystem()
            fileSystem.preload("/stelekit/$graphId")
            fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")

            val requestLog = mutableListOf<Pair<HttpMethod, String>>()
            val engine = MockEngine { request ->
                requestLog += request.method to request.url.encodedPath
                when {
                    request.method == HttpMethod.Patch && request.url.encodedPath.endsWith("/git/refs/heads/main") ->
                        jsonResponse("{}")
                    else -> error("unexpected request to ${request.url}")
                }
            }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
            // One shared WasmGitWriteService instance — the whole point of this test: both entry
            // points below must drive the SAME underlying write engine, not two independent ones.
            val sharedWriteService = WasmGitWriteService(client, fileSystem)
            val config = GitConfig(
                graphId = graphId,
                repoRoot = "/stelekit/$graphId",
                wikiSubdir = "",
                authType = GitAuthType.GITHUB_OAUTH,
            )

            val repository = WasmGitRepository(
                gitWriteService = sharedWriteService,
                httpClient = client,
                fileSystem = fileSystem,
                configResolver = { hostConfig },
            )
            val manager = JsGitManager(
                graphId = { graphId },
                configRepository = FakeGitConfigRepository(config = config),
                fileSystem = fileSystem,
                gitWriteService = sharedWriteService,
                configResolver = { hostConfig },
            )

            // First entry point: WasmGitRepository.push() — stage a commit, push, capture the
            // exact request sequence it produces.
            fileSystem.setPendingCommit(commitSha = "c0ffee1", treeSha = "7ea5e11")
            val repositoryPushResult = repository.push(config)
            val repositoryRequests = requestLog.toList()
            requestLog.clear()

            // Second entry point: JsGitManager.push() — restage an equivalent commit (the first
            // push already cleared it) and push again through the manager instead.
            fileSystem.setPendingCommit(commitSha = "c0ffee1", treeSha = "7ea5e11")
            val managerPushResult = manager.push()
            val managerRequests = requestLog.toList()

            assertEquals(Either.Right(Unit), repositoryPushResult)
            assertEquals(Either.Right(Unit), managerPushResult)
            assertEquals(
                repositoryRequests,
                managerRequests,
                "JsGitManager.push() must drive an identical MockEngine call sequence to WasmGitRepository.push()",
            )
            assertEquals(listOf(HttpMethod.Patch to "/repos/tstapler/steno-wiki/git/refs/heads/main"), managerRequests)
        }

    // ── IT-4.2.1-C ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `IT-4_2_1-C isDirty() reflects PlatformFileSystem dirtyFileCountFlow value greater than zero against a real dirty set`() =
        runTest {
            val graphId = freshGraphId()
            val fileSystem = PlatformFileSystem()
            fileSystem.preload("/stelekit/$graphId")
            val config = GitConfig(
                graphId = graphId,
                repoRoot = "/stelekit/$graphId",
                wikiSubdir = "",
                authType = GitAuthType.GITHUB_OAUTH,
            )
            val engine = MockEngine { error("isDirty() must never make a network call") }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(gitApiJson) } }
            val manager = JsGitManager(
                graphId = { graphId },
                configRepository = FakeGitConfigRepository(config = config),
                fileSystem = fileSystem,
                gitWriteService = WasmGitWriteService(client, fileSystem),
                configResolver = { hostConfig },
            )

            assertEquals(GitResult.Success(false), manager.isDirty())
            assertEquals(0, fileSystem.dirtyFileCountFlow.value)

            fileSystem.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo\n")

            assertTrue(fileSystem.dirtyFileCountFlow.value > 0)
            assertEquals(GitResult.Success(true), manager.isDirty())
        }
}
