// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.security.CredentialAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Helpers in `GitOperationSupport.kt` other than the failure classifier (see `GitOperationSupportClassifierTest`). */
class GitOperationSupportHelpersTest {
    private val tempDirs = mutableListOf<File>()
    private val openGits = mutableListOf<Git>()

    @AfterTest
    fun cleanUp() {
        openGits.forEach { it.close() }
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("git-support-test").toFile().also { tempDirs += it }

    private fun initRepo(dir: File = tempDir()): Git =
        Git.init().setDirectory(dir).setInitialBranch("main").call().also { openGits += it }

    private fun Git.commitFile(name: String, content: String): ObjectId {
        File(repository.workTree, name).writeText(content)
        add().addFilepattern(name).call()
        return commit().setMessage("add $name").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com")
            .setSign(false).call().id
    }

    private class ProbeCommand(repo: Repository) : TransportCommand<ProbeCommand, Unit>(repo) {
        override fun call() = Unit
        val provider: CredentialsProvider? get() = credentialsProvider
    }

    private fun ProbeCommand.credentials(): Pair<String, String>? {
        val p = provider ?: return null
        val user = CredentialItem.Username()
        val pass = CredentialItem.Password()
        p.get(URIish("https://example.com/r.git"), user, pass)
        return user.value to String(pass.value)
    }

    private class MapCredentials(private val values: Map<String, String>) : CredentialAccess {
        override fun retrieve(key: String): String? = values[key]
        override fun store(key: String, value: String) = error("unused")
        override fun delete(key: String) = error("unused")
    }

    private fun config(authType: GitAuthType, root: String = "/r", https: String? = null, oauth: String? = null, ssh: String? = null) =
        GitConfig(
            graphId = "g", repoRoot = RepoRoot(root), wikiSubdir = null, authType = authType,
            httpsTokenKey = https, oauthTokenKey = oauth, sshKeyPassphraseKey = ssh,
        )

    // runGitOp / runGitOpOrFalse

    @Test
    fun runGitOp_returnsTheOperationResultUntouched() {
        val result = runGitOp(onFailed = { DomainError.GitError.FetchFailed(it.message.orEmpty()) }) { 7.right() }

        assertEquals(7.right(), result)
    }

    @Test
    fun runGitOp_convertsThrownExceptionThroughOnFailed() {
        val result: Either<DomainError.GitError, Unit> =
            runGitOp(onFailed = { DomainError.GitError.FetchFailed("wrapped: ${it.message}") }) { error("boom") }

        assertEquals(DomainError.GitError.FetchFailed("wrapped: boom"), result.leftOrNull())
    }

    @Test
    fun runGitOp_rethrowsCancellation() {
        assertFailsWith<CancellationException> {
            runGitOp<Unit>(onFailed = { DomainError.GitError.FetchFailed("x") }) { throw CancellationException("stop") }
        }
    }

    @Test
    fun runGitOpOrFalse_mapsFailureToFalseAndPropagatesCancellation() {
        assertTrue(runGitOpOrFalse { true })
        assertFalse(runGitOpOrFalse { false })
        assertFalse(runGitOpOrFalse { error("boom") })
        assertFailsWith<CancellationException> { runGitOpOrFalse { throw CancellationException("stop") } }
    }

    // redaction

    @Test
    fun redactedTransportException_stripsBareUserinfoAndKeepsCause() {
        val original = RuntimeException("cannot open https://ghp_secrettoken@github.com/o/r.git")

        val redacted = redactedTransportException(original)

        assertFalse(redacted.message.orEmpty().contains("ghp_secrettoken"), redacted.message)
        assertTrue(redacted.message.orEmpty().contains("github.com/o/r.git"), redacted.message)
        assertEquals(original, redacted.cause)
    }

    @Test
    fun redactedTransportException_returnsSameInstanceWhenNothingToRedact() {
        val clean = RuntimeException("connection reset")
        val noMessage = RuntimeException()

        assertTrue(clean === redactedTransportException(clean))
        assertTrue(noMessage === redactedTransportException(noMessage))
    }

    // retry cleanup

    @Test
    fun deleteLockFileIfStaleForRetry_deletesOnlyLocksOlderThanTheRetryLoop() {
        val dir = tempDir()
        val old = File(dir, "old.lock").apply { writeText("") ; setLastModified(1_000L) }
        val fresh = File(dir, "fresh.lock").apply { writeText("") }

        deleteLockFileIfStaleForRetry(old, retryLoopStartMs = 5_000L)
        deleteLockFileIfStaleForRetry(fresh, retryLoopStartMs = 5_000L)
        deleteLockFileIfStaleForRetry(File(dir, "absent.lock"), retryLoopStartMs = 5_000L)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun deleteDirectoryContentsForRetry_emptiesTheDirectoryButKeepsIt() {
        val dir = tempDir()
        File(dir, "a.txt").writeText("a")
        File(dir, "sub/deep").mkdirs()
        File(dir, "sub/deep/b.txt").writeText("b")

        deleteDirectoryContentsForRetry(dir)

        assertTrue(dir.isDirectory)
        assertEquals(0, dir.listFiles()?.size)
        deleteDirectoryContentsForRetry(File(dir, "never-existed"))
    }

    // commit counting / shallow guard

    @Test
    fun countRemoteCommitsBestEffort_countsRangeAndFallsBackToZero() {
        val git = initRepo()
        val first = git.commitFile("a", "1")
        git.commitFile("b", "2")
        val third = git.commitFile("c", "3")

        assertEquals(2, countRemoteCommitsBestEffort(git, first, third))
        assertEquals(0, countRemoteCommitsBestEffort(git, third, third))
        assertEquals(0, countRemoteCommitsBestEffort(git, ObjectId.fromString("1".repeat(40)), third))
    }

    @Test
    fun isShallowHistoryInsufficientForMerge_isFalseForAFullHistory() {
        val git = initRepo()
        git.commitFile("a", "1")
        val head = git.commitFile("b", "2")

        assertFalse(isShallowHistoryInsufficientForMerge(git.repository, head))
    }

    // auth configuration

    @Test
    fun configureHttpsOrNoAuth_setsCredentialsForHttpsTokenAndReportsHandled() {
        val cmd = ProbeCommand(initRepo().repository)

        val handled = configureHttpsOrNoAuth(cmd, GitAuth.HttpsToken("alice") { "tok" }, preResolvedToken = "tok", onMissingToken = { error("unexpected") })

        assertTrue(handled)
        assertEquals("alice" to "tok", cmd.credentials())
    }

    @Test
    fun configureHttpsOrNoAuth_missingTokenLeavesCommandUnauthenticatedAndNotifies() {
        val cmd = ProbeCommand(initRepo().repository)
        var notified = 0

        val handled = configureHttpsOrNoAuth(cmd, GitAuth.HttpsToken("alice") { null }, preResolvedToken = null, onMissingToken = { notified++ })

        assertTrue(handled)
        assertEquals(1, notified)
        assertNull(cmd.provider)
    }

    @Test
    fun configureHttpsOrNoAuth_noneIsHandledAndSshIsLeftToTheCaller() {
        val cmd = ProbeCommand(initRepo().repository)

        assertTrue(configureHttpsOrNoAuth(cmd, GitAuth.None, null, onMissingToken = { error("unexpected") }))
        assertFalse(configureHttpsOrNoAuth(cmd, GitAuth.SshKey("/k") { null }, null, onMissingToken = { error("unexpected") }))
        assertNull(cmd.provider)
    }

    @Test
    fun configureTransportAuth_usesTheCredentialKeyAndUsernameForEachAuthType() {
        val store = MapCredentials(mapOf("https-key" to "https-secret", "oauth-key" to "oauth-secret", "pp-key" to "passphrase"))
        val repo = initRepo().repository

        val https = ProbeCommand(repo).also { configureTransportAuth(it, config(GitAuthType.HTTPS_TOKEN, https = "https-key"), store) { error("unexpected") } }
        val oauth = ProbeCommand(repo).also { configureTransportAuth(it, config(GitAuthType.GITHUB_OAUTH, oauth = "oauth-key"), store) { error("unexpected") } }
        val none = ProbeCommand(repo).also { configureTransportAuth(it, config(GitAuthType.NONE), store) { error("unexpected") } }

        assertEquals("" to "https-secret", https.credentials())
        assertEquals("x-oauth-basic" to "oauth-secret", oauth.credentials())
        assertNull(none.provider)
    }

    @Test
    fun configureTransportAuth_missingStoredTokenLeavesCommandUnauthenticated() {
        val repo = initRepo().repository
        val empty = MapCredentials(emptyMap())

        val noKey = ProbeCommand(repo).also { configureTransportAuth(it, config(GitAuthType.HTTPS_TOKEN), empty) { error("unexpected") } }
        val unresolved = ProbeCommand(repo).also { configureTransportAuth(it, config(GitAuthType.GITHUB_OAUTH, oauth = "gone"), empty) { error("unexpected") } }

        assertNull(noKey.provider)
        assertNull(unresolved.provider)
    }

    @Test
    fun configureTransportAuth_sshHandsResolvedPassphraseToCallback() {
        val store = MapCredentials(mapOf("pp-key" to "passphrase"))
        val repo = initRepo().repository
        val seen = mutableListOf<String?>()

        configureTransportAuth(ProbeCommand(repo), config(GitAuthType.SSH_KEY, ssh = "pp-key"), store) { seen += it }
        configureTransportAuth(ProbeCommand(repo), config(GitAuthType.SSH_KEY), store) { seen += it }

        assertEquals(listOf<String?>("passphrase", null), seen)
    }

    // ls-remote based helpers against a local origin

    @Test
    fun testRemoteViaLsRemote_succeedsForAReachableRepoAndFailsForAMissingOne() = runBlocking<Unit> {
        val origin = initRepo()
        origin.commitFile("a", "1")

        val ok = testRemoteViaLsRemote(origin.repository.directory.absolutePath, GitAuth.None) { _, _, _ -> }
        val missing = testRemoteViaLsRemote(File(tempDir(), "nope").absolutePath, GitAuth.None) { _, _, _ -> }

        assertEquals(Unit.right(), ok)
        assertIs<DomainError.GitError>(missing.leftOrNull())
    }

    @Test
    fun testRemoteViaLsRemote_resolvesHttpsTokenBeforeConfiguringAuth() = runBlocking<Unit> {
        val origin = initRepo()
        origin.commitFile("a", "1")
        var tokenSeen: String? = null

        testRemoteViaLsRemote(origin.repository.directory.absolutePath, GitAuth.HttpsToken("u") { "resolved" }) { _, _, token -> tokenSeen = token }

        assertEquals("resolved", tokenSeen)
    }

    @Test
    fun hasRemoteDivergedSinceShallowClone_tracksWhetherTheAdvertisedTipMovedSinceLastFetch() {
        val origin = initRepo()
        origin.commitFile("a", "1")
        val clone = Git.cloneRepository()
            .setURI(origin.repository.directory.absolutePath)
            .setDirectory(tempDir())
            .setBranch("main")
            .call().also { openGits += it }
        val cfg = config(GitAuthType.NONE)

        assertFalse(hasRemoteDivergedSinceShallowClone(clone, cfg) { })

        origin.commitFile("b", "2")
        assertTrue(hasRemoteDivergedSinceShallowClone(clone, cfg) { })

        clone.fetch().setRemote("origin").call()
        assertFalse(hasRemoteDivergedSinceShallowClone(clone, cfg) { })
    }

    @Test
    fun hasRemoteDivergedSinceShallowClone_treatsUnknownBranchAndFailureAsDiverged() {
        val origin = initRepo()
        origin.commitFile("a", "1")
        val clone = Git.cloneRepository()
            .setURI(origin.repository.directory.absolutePath)
            .setDirectory(tempDir())
            .setBranch("main")
            .call().also { openGits += it }

        val unknownBranch = GitConfig(graphId = "g", repoRoot = RepoRoot("/r"), wikiSubdir = null, remoteBranch = "nope", authType = GitAuthType.NONE)
        val unreachableRemote = GitConfig(graphId = "g", repoRoot = RepoRoot("/r"), wikiSubdir = null, remoteName = "no-such-remote", authType = GitAuthType.NONE)

        assertTrue(hasRemoteDivergedSinceShallowClone(clone, unknownBranch) { })
        assertTrue(hasRemoteDivergedSinceShallowClone(clone, unreachableRemote) { })
        assertNotNull(clone.repository.exactRef("refs/remotes/origin/main"))
    }
}
