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
import dev.stapler.stelekit.git.model.GitLabCommitAction
import dev.stapler.stelekit.git.model.GitLabCommitRequest
import dev.stapler.stelekit.git.model.GitRefResponse
import dev.stapler.stelekit.git.model.GitRefUpdateRequest
import dev.stapler.stelekit.git.model.PendingCommit
import dev.stapler.stelekit.git.model.gitApiJson
import dev.stapler.stelekit.platform.PlatformFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.Serializable


/**
 * Epic 6.3 (Story 6.3.1): a manually-triggered, non-CI-blocking live test that exercises the
 * REAL GitHub and GitLab write APIs (no `MockEngine`, unlike
 * [WasmGitWriteServiceMockedIntegrationTest]) against disposable throwaway repos, to catch
 * API-shape drift `project_plans/web-git-writeback/requirements.md`'s Feasibility Risks flags
 * explicitly — before a real user hits it.
 *
 * ### This test NEVER runs automatically
 * Every `@Test` here checks [liveGitTestEnvFlagEnabled] first and returns immediately (never
 * fails, never hangs, never counts against the CI gate) unless `STELEKIT_LIVE_GIT_TEST=1` is set.
 * `bazel test //...` / `./gradlew ciCheck` always see this file as a no-op.
 *
 * ### Target repos
 * - GitHub: `tstapler/steno-wiki-livetest`, branch `livetest` (see [GITHUB_OWNER]/[GITHUB_REPO]/
 *   [GITHUB_BRANCH]).
 * - GitLab: **not yet provisioned** — `project_plans/web-git-writeback/implementation/plan.md`'s
 *   own Unresolved Questions leaves the disposable GitLab project name TBD; provisioning is owned
 *   by the repo owner outside this change. [GITLAB_OWNER]/[GITLAB_REPO] below are placeholders —
 *   replace them (and [GITHUB_BASE_SHA]/[GITLAB_BASE_SHA], see next section) once the project
 *   exists. This blocks only the GitLab test cases actually *running*, not this file compiling.
 *
 * ### Base SHAs
 * [GITHUB_BASE_SHA] and [GITLAB_BASE_SHA] are placeholders (`"0" * 40`) until the disposable repos
 * are provisioned. Every test case's first action force-resets its target branch to this fixed
 * sha (Task 6.3.1b) so repeated manual runs never accumulate unbounded commit history — replace
 * both constants with the real initial commit sha of each disposable repo's `livetest` branch
 * once created.
 *
 * ### Credentials
 * PAT from `System.getenv`'s wasmJs-test equivalent, [liveGitTestPatOrNull] — reads
 * `STELEKIT_LIVE_GIT_TEST_PAT` — **never** hardcoded, committed, or interpolated into an assertion
 * message/print statement anywhere in this file. Per `validation.md`'s Epic 6.3 table this is a
 * single env var shared by both host test cases: it must be a token valid for whichever host's
 * test case you are actually running (a GitHub PAT for `liveGitHubPushAndVerify`, a GitLab PAT for
 * the `liveGitLab*` cases) — this file does not attempt to reconcile two different credential
 * shapes under one variable name; that is a known simplification carried from `validation.md`,
 * not something this test resolves.
 *
 * ### Run command
 * ```
 * STELEKIT_LIVE_GIT_TEST=1 STELEKIT_LIVE_GIT_TEST_PAT=*** \
 *   ./gradlew :kmp:wasmJsBrowserTest --tests "*WasmGitWriteServiceLiveTest*"
 * ```
 *
 * ### Known platform gap (read before your first real run)
 * `kmp/build.gradle.kts` configures the wasmJs test target as `wasmJs { browser() }` — tests run
 * inside a headless browser (karma), which has no Node `process.env` unless the project's
 * webpack/karma config explicitly injects one (this repo does not, as of this file). This means
 * the shell env vars in the run command above will NOT automatically reach [liveGitTestEnvFlagEnabled]/
 * [liveGitTestPatOrNull] without additional `DefinePlugin`/`EnvironmentPlugin`-style webpack
 * wiring — out of scope for this test-only file to add. [liveGitTestEnvFlagEnabled] defaults to
 * `false` (and [liveGitTestPatOrNull] to `null`) whenever `process` is unavailable, which is safe
 * (every test still skips cleanly) but means this test cannot actually be driven end-to-end until
 * that wiring exists. Flagging this here rather than silently pretending the run command "just
 * works" today.
 */
class WasmGitWriteServiceLiveTest {

    // ── Task 6.3.1a: env-var gate + skip-by-default scaffold ────────────────────────────────

    @Test
    fun liveGitHubPushAndVerify() = runTest {
        if (!liveGitTestEnvFlagEnabled()) return@runTest
        val token = liveGitTestPatOrNull()
            ?: fail("STELEKIT_LIVE_GIT_TEST=1 but STELEKIT_LIVE_GIT_TEST_PAT is unset")

        val hostConfig = githubHostConfig(token)
        val client = newLiveHttpClient()

        // Task 6.3.1b: reset before any assertion, every run.
        resetGitHubBranchToBase(client, hostConfig)

        val fileSystem = PlatformFileSystem()
        val graphId = "livetest-${Random.nextInt(0, Int.MAX_VALUE)}"
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/$LIVE_TEST_FILE", "# SteleKit live test\n\nrun-id: $graphId\n")

        val writeService = WasmGitWriteService.withDefaultClient(fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.GITHUB_OAUTH,
        )

        val commitResult = writeService.commit(
            config,
            hostConfig,
            baseSha = GITHUB_BASE_SHA,
            message = "SteleKit live test: $graphId",
        )
        assertTrue(commitResult is Either.Right, "commit() failed: $commitResult")

        val stagedOrNull = fileSystem.getPendingCommit()
        if (stagedOrNull !is PendingCommit.Staged) {
            fail("expected a staged commit after commit(), got $stagedOrNull")
        }
        val staged = stagedOrNull

        val pushResult = writeService.push(hostConfig, staged)
        assertTrue(pushResult is Either.Right, "push() failed: $pushResult")

        // Task 6.3.1c: read back the new ref SHA with a short retry for eventual consistency.
        val convergedSha = awaitGitHubRefSha(client, hostConfig, expectedSha = staged.commitSha)
        assertEquals(staged.commitSha, convergedSha)
    }

    @Test
    fun liveGitLabPushAndVerify() = runTest {
        if (!liveGitTestEnvFlagEnabled()) return@runTest
        val token = liveGitTestPatOrNull()
            ?: fail("STELEKIT_LIVE_GIT_TEST=1 but STELEKIT_LIVE_GIT_TEST_PAT is unset")

        val hostConfig = gitlabHostConfig(token)
        val client = newLiveHttpClient()

        // Task 6.3.1b: reset before any assertion, every run.
        resetGitLabBranchToBase(client, hostConfig)

        val fileSystem = PlatformFileSystem()
        val graphId = "livetest-${Random.nextInt(0, Int.MAX_VALUE)}"
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/$LIVE_TEST_FILE", "# SteleKit live test\n\nrun-id: $graphId\n")

        val writeService = WasmGitWriteService.withDefaultClient(fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.NONE,
        )

        // GitLab's commit() is a true no-op (Task 3.2.1d) — push() re-derives the commits-API
        // request fresh from the dirty set via GitLabPushContext.
        val pushResult = writeService.push(
            hostConfig,
            PendingCommit.None,
            WasmGitWriteService.GitLabPushContext(config = config, baseSha = GITLAB_BASE_SHA, message = "SteleKit live test: $graphId"),
        )
        // Task 6.3.1d / requirements.md's CORS acceptance criterion: if the configured Ktor JS
        // engine were sending `credentials: 'include'`, this cross-origin POST to gitlab.com would
        // be rejected by the browser's CORS preflight before ever reaching this assertion —
        // succeeding here IS the live confirmation that it isn't (research/pitfalls.md §2).
        assertTrue(pushResult is Either.Right, "GitLab push() failed (or was rejected by CORS): $pushResult")

        // Task 6.3.1c: read back the new ref SHA with a short retry for eventual consistency.
        val expectedSha = fileSystem.getBaseSha()
        val convergedSha = awaitGitLabBranchHeadSha(client, hostConfig, expectedSha = expectedSha)
        assertEquals(expectedSha, convergedSha)
    }

    @Test
    fun liveGitLabStaleLastCommitIdRace() = runTest {
        if (!liveGitTestEnvFlagEnabled()) return@runTest
        val token = liveGitTestPatOrNull()
            ?: fail("STELEKIT_LIVE_GIT_TEST=1 but STELEKIT_LIVE_GIT_TEST_PAT is unset")

        val hostConfig = gitlabHostConfig(token)
        val client = newLiveHttpClient()

        // Task 6.3.1b: reset before any assertion, every run.
        resetGitLabBranchToBase(client, hostConfig)

        // The "second writer" (Task 3.2.3c): an out-of-band commit made directly via the raw
        // GitLab commits API (bypassing WasmGitWriteService entirely) that touches the SAME file
        // this test's own push targets below — advancing its real `last_commit_id` past
        // GITLAB_BASE_SHA before this test's own push runs.
        postRawGitLabCommit(
            client,
            hostConfig,
            GitLabCommitRequest(
                branch = hostConfig.branch,
                commitMessage = "SteleKit live test: out-of-band race writer",
                startSha = GITLAB_BASE_SHA,
                actions = listOf(
                    GitLabCommitAction(
                        action = "update",
                        filePath = LIVE_TEST_FILE,
                        content = base64Encode("# out-of-band edit\n"),
                        lastCommitId = GITLAB_BASE_SHA,
                    ),
                ),
            ),
        )

        val fileSystem = PlatformFileSystem()
        val graphId = "livetest-${Random.nextInt(0, Int.MAX_VALUE)}"
        fileSystem.preload("/stelekit/$graphId")
        fileSystem.writeFile("/stelekit/$graphId/$LIVE_TEST_FILE", "# local edit racing the out-of-band writer\n")

        val writeService = WasmGitWriteService.withDefaultClient(fileSystem)
        val config = GitConfig(
            graphId = graphId,
            repoRoot = "/stelekit/$graphId",
            wikiSubdir = "",
            authType = GitAuthType.NONE,
        )

        // This test's own push still carries the now-STALE GITLAB_BASE_SHA as last_commit_id —
        // the out-of-band writer above already advanced the file past it.
        val pushResult = writeService.push(
            hostConfig,
            PendingCommit.None,
            WasmGitWriteService.GitLabPushContext(
                config = config,
                baseSha = GITLAB_BASE_SHA,
                message = "SteleKit live test: stale race $graphId",
            ),
        )

        // Confirms (or corrects) Story 3.2.3's `400`-status assumption against the real API.
        if (pushResult !is Either.Left) {
            fail(
                "expected the stale last_commit_id race to surface as a conflict, got $pushResult " +
                    "— if GitLab's real API accepted this stale write, pushViaGitLab's optimistic- " +
                    "concurrency assumption needs revisiting",
            )
        }
        val err = pushResult.value
        if (err !is DomainError.GitError.MergeConflict) {
            fail(
                "expected MergeConflict, got ${err::class.simpleName} — GitLab's real 400 response " +
                    "shape may differ from classifyGitLabPushConflict's assumption; this is exactly " +
                    "the drift this live test exists to catch (Task 3.2.3c)",
            )
        }
    }

    // ── Task 6.3.1b: branch-reset-before-run helpers ────────────────────────────────────────

    /**
     * GitHub: the same ref-PATCH primitive `WasmGitWriteService`'s private `advanceRef` uses, but
     * with `force = true` — deliberately NOT added as a parameter to the production `advanceRef`
     * (which never force-pushes); this is a test-local direct PATCH built the same way.
     */
    private suspend fun resetGitHubBranchToBase(client: HttpClient, hostConfig: GitHostConfig) {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = client.patch("${hostConfig.apiBase}/git/refs/heads/${hostConfig.branch}") {
            header(headerName, headerValue)
            contentType(ContentType.Application.Json)
            setBody(GitRefUpdateRequest(sha = GITHUB_BASE_SHA, force = true))
        }
        assertTrue(
            response.status.value in 200..299,
            "GitHub branch-reset PATCH (force=true) failed: HTTP ${response.status.value} " +
                "for ${hostConfig.owner}/${hostConfig.repo}#${hostConfig.branch}",
        )
    }

    /**
     * GitLab: there is no force-update-ref primitive equivalent to GitHub's — the equivalent reset
     * is delete-then-recreate the branch from the fixed base sha. A `404` on the delete (first-ever
     * run, branch doesn't exist yet) is expected and ignored; only the recreate's status is asserted.
     */
    private suspend fun resetGitLabBranchToBase(client: HttpClient, hostConfig: GitHostConfig) {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        client.delete("${hostConfig.apiBase}/repository/branches/${hostConfig.branch}") {
            header(headerName, headerValue)
        }
        val response = client.post("${hostConfig.apiBase}/repository/branches") {
            header(headerName, headerValue)
            contentType(ContentType.Application.Json)
            setBody(GitLabCreateBranchRequest(branch = hostConfig.branch, ref = GITLAB_BASE_SHA))
        }
        assertTrue(
            response.status.value in 200..299,
            "GitLab branch-reset (delete+recreate from base sha) failed: HTTP ${response.status.value} " +
                "for ${hostConfig.owner}/${hostConfig.repo}#${hostConfig.branch}",
        )
    }

    // ── Task 6.3.1c/d: ref/branch read-back with short retry (eventual consistency) ────────

    private suspend fun fetchGitHubRefSha(client: HttpClient, hostConfig: GitHostConfig): String {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = client.get("${hostConfig.apiBase}/git/ref/heads/${hostConfig.branch}") {
            header(headerName, headerValue)
        }
        assertTrue(response.status.value in 200..299, "GET ref failed: HTTP ${response.status.value}")
        return response.body<GitRefResponse>().obj.sha
    }

    private suspend fun awaitGitHubRefSha(client: HttpClient, hostConfig: GitHostConfig, expectedSha: String): String {
        var lastSeen = ""
        repeat(REF_POLL_ATTEMPTS) { attempt ->
            lastSeen = fetchGitHubRefSha(client, hostConfig)
            if (lastSeen == expectedSha) return lastSeen
            if (attempt < REF_POLL_ATTEMPTS - 1) delay(REF_POLL_DELAY_MS)
        }
        fail("GitHub ref for ${hostConfig.branch} did not converge to $expectedSha after $REF_POLL_ATTEMPTS attempts (last seen: $lastSeen)")
    }

    private suspend fun fetchGitLabBranchHeadSha(client: HttpClient, hostConfig: GitHostConfig): String {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = client.get("${hostConfig.apiBase}/repository/branches/${hostConfig.branch}") {
            header(headerName, headerValue)
        }
        assertTrue(response.status.value in 200..299, "GET branch failed: HTTP ${response.status.value}")
        return response.body<GitLabBranchResponse>().commit.id
    }

    private suspend fun awaitGitLabBranchHeadSha(client: HttpClient, hostConfig: GitHostConfig, expectedSha: String): String {
        var lastSeen = ""
        repeat(REF_POLL_ATTEMPTS) { attempt ->
            lastSeen = fetchGitLabBranchHeadSha(client, hostConfig)
            if (lastSeen == expectedSha) return lastSeen
            if (attempt < REF_POLL_ATTEMPTS - 1) delay(REF_POLL_DELAY_MS)
        }
        fail("GitLab branch ${hostConfig.branch} did not converge to $expectedSha after $REF_POLL_ATTEMPTS attempts (last seen: $lastSeen)")
    }

    // ── Task 6.3.1d: out-of-band raw GitLab commit (the scripted second writer) ────────────

    private suspend fun postRawGitLabCommit(client: HttpClient, hostConfig: GitHostConfig, request: GitLabCommitRequest) {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = client.post("${hostConfig.apiBase}/repository/commits") {
            header(headerName, headerValue)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertTrue(
            response.status.value in 200..299,
            "out-of-band GitLab commits POST failed: HTTP ${response.status.value} — cannot set up the race scenario",
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64Encode(content: String): String = Base64.Default.encode(content.encodeToByteArray())

    // ── Shared config/client builders ───────────────────────────────────────────────────────

    private fun githubHostConfig(token: String) = GitHostConfig(
        type = GitHostType.GITHUB,
        owner = GITHUB_OWNER,
        repo = GITHUB_REPO,
        branch = GITHUB_BRANCH,
        token = token,
        apiBase = GitHostAdapter.apiBase(GitHostType.GITHUB, GITHUB_OWNER, GITHUB_REPO),
    )

    private fun gitlabHostConfig(token: String) = GitHostConfig(
        type = GitHostType.GITLAB,
        owner = GITLAB_OWNER,
        repo = GITLAB_REPO,
        branch = GITLAB_BRANCH,
        token = token,
        apiBase = GitHostAdapter.apiBase(GitHostType.GITLAB, GITLAB_OWNER, GITLAB_REPO),
    )

    private fun newLiveHttpClient(): HttpClient = HttpClient {
        install(ContentNegotiation) { json(gitApiJson) }
        installRetryPolicy()
    }

    private companion object {
        // ── Task 6.3.1e: disposable target repos ────────────────────────────────────────────
        private const val GITHUB_OWNER = "tstapler"
        private const val GITHUB_REPO = "steno-wiki-livetest"
        private const val GITHUB_BRANCH = "livetest"

        // TODO(owner): GitLab's disposable project is not yet provisioned (plan.md's own
        // Unresolved Questions) — these are placeholders. Replace with the real namespace/project
        // once created; this blocks only the liveGitLab* test cases actually running.
        private const val GITLAB_OWNER = "tstapler"
        private const val GITLAB_REPO = "steno-wiki-livetest"
        private const val GITLAB_BRANCH = "livetest"

        // TODO(owner): replace with each disposable repo's real initial commit sha once
        // provisioned — an all-zero placeholder can never match a real ref, so until replaced
        // these test cases would fail loudly at the reset step rather than silently no-op (the
        // env-var gate is what keeps them from running at all today).
        private const val GITHUB_BASE_SHA = "0000000000000000000000000000000000000000"
        private const val GITLAB_BASE_SHA = "0000000000000000000000000000000000000000"

        private const val LIVE_TEST_FILE = "LIVETEST.md"
        private const val REF_POLL_ATTEMPTS = 5
        private const val REF_POLL_DELAY_MS = 500L
    }
}

// ── Task 6.3.1a: env-var gate (top-level — `js()` bodies are only supported on top-level
// Kotlin/Wasm functions, matching this repo's existing convention, e.g.
// `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/db/SqliteWorkerInterop.kt`) ───────────────────

/**
 * `true` only when `STELEKIT_LIVE_GIT_TEST=1` is visible to this test process. Reads
 * `process.env` via JS interop — see this file's KDoc "Known platform gap" section: a pure
 * browser test run (this project's current `wasmJs { browser() }` setup) has no `process` at all,
 * so this safely defaults to `false` there, which is exactly the always-skip behavior every
 * `@Test` in this file needs when run via a normal `bazel test //...` / `./gradlew ciCheck`.
 */
private fun liveGitTestEnvFlagEnabled(): Boolean = js(
    "(typeof process !== 'undefined' && process.env && process.env['STELEKIT_LIVE_GIT_TEST'] === '1')",
)

/**
 * The `STELEKIT_LIVE_GIT_TEST_PAT` env var, or `null` when unset/unavailable (see
 * [liveGitTestEnvFlagEnabled]'s doc for the same `process`-availability caveat). Never logged,
 * never hardcoded — read fresh on each call, never cached in a field.
 */
private fun liveGitTestPatOrNull(): String? = js(
    "((typeof process !== 'undefined' && process.env && process.env['STELEKIT_LIVE_GIT_TEST_PAT']) || null)",
)

// ── GitLab branch reset/read-back response shapes — test-only, not part of GitLabCommitModels.kt
// (mirrors WasmGitWriteService.kt's own precedent of scoping a response-only shape locally to the
// file that's the sole reader of it, e.g. its private GitLabCommitErrorResponse) ────────────────

@Serializable
private data class GitLabCreateBranchRequest(val branch: String, val ref: String)

@Serializable
private data class GitLabBranchCommit(val id: String)

@Serializable
private data class GitLabBranchResponse(val commit: GitLabBranchCommit)
