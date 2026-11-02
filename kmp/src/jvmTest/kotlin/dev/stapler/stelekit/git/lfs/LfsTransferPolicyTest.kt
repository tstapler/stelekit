// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobState
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Download limits and job reporting for LFS, over real JGit and a [FakeLfsServer]. */
class LfsTransferPolicyTest {
    private val root = createTempDirectory("stelekit_lfs_policy_").toFile()
    private val server = FakeLfsServer()
    private val origin = File(root, "origin.git").also { Git.init().setBare(true).setDirectory(it).setInitialBranch("main").call().close() }
    private val publisher = JvmGitRepository()
    private val jobs = InProcessJobs()

    @AfterTest
    fun cleanup() {
        server.close()
        root.deleteRecursively()
    }

    private fun configFor(dir: File) = GitConfig(
        graphId = "g-${dir.name}",
        repoRoot = RepoRoot(dir.absolutePath),
        wikiSubdir = null,
        authType = GitAuthType.NONE,
    )

    /** Pushes one LFS-tracked file from a work repo and returns its bytes. */
    private fun publish(size: Int): ByteArray {
        val a = File(root, "a")
        Git.init().setDirectory(a).setInitialBranch("main").call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Stelekit Test")
                setString("user", null, "email", "stelekit-test@example.com")
                setString("remote", "origin", "url", origin.toURI().toString())
                setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                setString("lfs", null, "url", server.url)
                setString("core", null, "autocrlf", "false")
                save()
            }
        }
        val bytes = Random(size).nextBytes(size)
        File(a, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(a, "img.bin").writeBytes(bytes)
        runBlocking {
            val config = configFor(a)
            publisher.stageSubdir(config, emptySet())
            publisher.commit(config, "img")
            assertTrue(publisher.push(config).isRight())
        }
        return bytes
    }

    private fun support(policy: LfsTransferPolicy, metered: Boolean = false) =
        LfsRepositorySupport({ throw IllegalStateException() }, { HttpClient(OkHttp) { configureLfsTimeouts() } }).also {
            it.jobTracker = jobs
            it.policy = { policy }
            it.isMetered = { metered }
        }

    private fun cloneWith(support: LfsRepositorySupport): File {
        val b = File(root, "b")
        Git.cloneRepository().setURI(origin.toURI().toString()).setDirectory(b).setNoCheckout(true).call().use { git ->
            git.repository.config.setString("lfs", null, "url", server.url)
            git.repository.config.save()
            runBlocking { support.afterClone(git, null) }
        }
        return b
    }

    private fun lastJob() = runBlocking { jobs.jobs.first().single() }

    @Test
    fun download_should_ReportSucceededJob_When_ObjectArrives() {
        val bytes = publish(120_000)

        val b = cloneWith(support(LfsTransferPolicy()))

        assertContentEquals(bytes, File(b, "img.bin").readBytes())
        assertEquals(JobKind.LfsTransfer, lastJob().kind)
        assertEquals(JobState.Succeeded, lastJob().state)
    }

    @Test
    fun download_should_FailJobAndKeepPending_When_ServerCannotServeObject() {
        publish(120_000)
        server.objects.clear()

        val b = cloneWith(support(LfsTransferPolicy()))

        assertIs<JobState.Failed>(lastJob().state)
        assertTrue(File(b, ".git/lfs/pending").exists(), "the next sync must retry")
    }

    @Test
    fun download_should_SkipObjectOverCapAndExplainWhy_When_PolicyCapIsLower() {
        publish(300_000)

        val b = cloneWith(support(LfsTransferPolicy(maxObjectBytes = 100_000)))

        assertTrue(File(b, "img.bin").length() < 1024, "an over-cap image stays pointer text")
        val state = lastJob().state
        assertIs<JobState.Failed>(state)
        assertTrue(state.reason!!.contains("limit"), state.reason)
        assertFalse(File(b, ".git/lfs/pending").exists(), "an over-cap object is not retried every poll")
    }

    @Test
    fun download_should_BeHeldWithoutAJob_When_MeteredAndPolicyDefers() {
        publish(120_000)
        val before = server.requests.size

        val b = cloneWith(support(LfsTransferPolicy(deferOnMetered = true), metered = true))

        assertEquals(before, server.requests.size, "no request may reach the LFS server while metered")
        assertTrue(File(b, ".git/lfs/pending").exists())
        assertTrue(runBlocking { jobs.jobs.first() }.isEmpty())
    }

    @Test
    fun download_should_Proceed_When_MeteredButPolicyDoesNotDefer() {
        val bytes = publish(120_000)

        val b = cloneWith(support(LfsTransferPolicy(deferOnMetered = false), metered = true))

        assertContentEquals(bytes, File(b, "img.bin").readBytes())
    }

    @Test
    fun download_should_ExplainHttpsTokenIsNeeded_When_HostDemandsAuthAndNoneWasSent() {
        val authed = FakeLfsServer(requiredAuth = LfsRepositorySupport.basicAuth("", "tok"))
        try {
            val dir = File(root, "ssh").also { Git.init().setDirectory(it).setInitialBranch("main").call().close() }
            Git.open(dir).use { git ->
                git.repository.config.apply {
                    setString("lfs", null, "url", authed.url)
                    setString("user", null, "name", "t")
                    setString("user", null, "email", "t@e.x")
                    save()
                }
                val content = "hello".toByteArray()
                val pointer = LfsPointer(LfsOid(java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }), content.size.toLong())
                authed.objects[pointer.oid.hex] = content
                File(dir, "h.bin").writeBytes(LfsPointerCodec.serialize(pointer))
                File(dir, ".gitattributes").writeText("*.bin filter=lfs -text\n")
                val support = support(LfsTransferPolicy())
                support.prepare(git.repository, null)
                git.add().addFilepattern(".").call()
                git.commit().setMessage("p").call()

                runBlocking { support.afterClone(git, null) }
            }

            val state = lastJob().state
            assertIs<JobState.Failed>(state)
            assertTrue(state.reason!!.contains("HTTPS access token"), state.reason)
        } finally {
            authed.close()
        }
    }

    private class MapSettings(vararg entries: Pair<String, String>) : Settings {
        private val store = mutableMapOf(*entries)
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    @Test
    fun policySource_should_UseDefaults_When_NothingStored() {
        assertEquals(LfsTransferPolicy(), LfsTransferPolicySource(MapSettings()).current())
        assertEquals(LfsTransferPolicy(), LfsTransferPolicySource(null).current())
    }

    @Test
    fun policySource_should_ReadStoredValues_When_Present() {
        val settings = MapSettings(
            LfsTransferPolicySource.KEY_MAX_OBJECT_MB to "50",
            LfsTransferPolicySource.KEY_DEFER_ON_METERED to "true",
        )

        assertEquals(LfsTransferPolicy(maxObjectBytes = 50L * 1024 * 1024, deferOnMetered = true), LfsTransferPolicySource(settings).current())
    }

    @Test
    fun policySource_should_RoundTripSavedPolicy() {
        val settings = MapSettings()
        val chosen = LfsTransferPolicy(maxObjectBytes = 100L * 1024 * 1024, deferOnMetered = true)

        LfsTransferPolicySource(settings).save(chosen)

        assertEquals(chosen, LfsTransferPolicySource(settings).current())
    }

    @Test
    fun policySource_should_FallBackToDefaultCap_When_StoredSizeIsInvalid() {
        for (bad in listOf("0", "-5", "abc")) {
            val settings = MapSettings(LfsTransferPolicySource.KEY_MAX_OBJECT_MB to bad)

            assertEquals(LfsTransferPolicy.DEFAULT_MAX_OBJECT_BYTES, LfsTransferPolicySource(settings).current().maxObjectBytes, bad)
        }
    }
}
