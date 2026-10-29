// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** End to end: real JGit, a bare origin, [JvmGitRepository], and a [FakeLfsServer] over real HTTP. */
class JvmGitRepositoryLfsTransferTest {
    private val root = createTempDirectory("stelekit_lfs_transfer_").toFile()
    private val server = FakeLfsServer()
    private val origin = File(root, "origin.git").also { Git.init().setBare(true).setDirectory(it).setInitialBranch("main").call().close() }
    private val repo = JvmGitRepository()

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

    private fun newWorkRepo(name: String): File {
        val dir = File(root, name)
        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
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
        return dir
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun originBlob(path: String): ByteArray? = Git.open(origin).use { git ->
        val head = git.repository.resolve(Constants.HEAD) ?: return null
        TreeWalk.forPath(git.repository, path, git.repository.parseCommit(head).tree)
            ?.let { git.repository.open(it.getObjectId(0)).bytes }
    }

    private fun commitAndPush(dir: File, message: String): arrow.core.Either<*, *> = runBlocking {
        val config = configFor(dir)
        repo.stageSubdir(config, emptySet())
        repo.commit(config, message)
        repo.push(config)
    }

    @Test
    fun `push uploads the object, commits only a pointer, and keeps the real file in the work tree`() {
        val a = newWorkRepo("a")
        val bytes = Random(1).nextBytes(3 * 1024 * 1024)
        File(a, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(a, "big.bin").writeBytes(bytes)

        assertTrue(commitAndPush(a, "add big").isRight())

        val pointer = (LfsPointerCodec.parse(originBlob("big.bin")!!) as LfsPointerParse.Valid).pointer
        assertEquals(sha256(bytes), pointer.oid.hex)
        assertContentEquals(bytes, server.objects[pointer.oid.hex])
        assertContentEquals(bytes, File(a, "big.bin").readBytes(), "the work tree keeps the real bytes")
        assertFalse(runBlocking { repo.status(configFor(a)) }.getOrNull()!!.hasLocalChanges, "status must be clean")
        assertTrue(repo.cleanFilterActive)
    }

    @Test
    fun `a failed upload leaves the remote without the pointer commit`() {
        val a = newWorkRepo("a")
        File(a, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(a, "big.bin").writeBytes(Random(2).nextBytes(200_000))
        server.failUploadsWith = 500

        assertTrue(commitAndPush(a, "add big").isLeft())

        assertEquals(null, Git.open(origin).use { it.repository.resolve(Constants.HEAD) }, "origin must not have moved")
    }

    @Test
    fun `a second clone downloads the object and a later pull materialises new ones`() {
        val a = newWorkRepo("a")
        val first = Random(3).nextBytes(300_000)
        File(a, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(a, "one.bin").writeBytes(first)
        assertTrue(commitAndPush(a, "one").isRight())

        val b = File(root, "b")
        Git.cloneRepository().setURI(origin.toURI().toString()).setDirectory(b).setNoCheckout(true).call().use { git ->
            git.repository.config.setString("lfs", null, "url", server.url)
            git.repository.config.save()
            runBlocking { LfsRepositorySupport({ throw IllegalStateException() }, { io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) { configureLfsTimeouts() } }).afterClone(git, null) }
        }
        assertContentEquals(first, File(b, "one.bin").readBytes(), "clone must materialise the real bytes")
        assertFalse(runBlocking { repo.status(configFor(b)) }.getOrNull()!!.hasLocalChanges)

        val second = Random(4).nextBytes(150_000)
        File(a, "two.bin").writeBytes(second)
        assertTrue(commitAndPush(a, "two").isRight())
        assertEquals(2, server.uploads())

        val fetched = runBlocking { repo.fetch(configFor(b)) }
        assertTrue(fetched.isRight())
        val merged = runBlocking { repo.merge(configFor(b)) }
        assertTrue(merged.isRight(), merged.toString())
        assertContentEquals(second, File(b, "two.bin").readBytes(), "pull must materialise the new object")
    }

    @Test
    fun `the token reaches the batch endpoint only`() {
        server.close()
        val authed = FakeLfsServer(requiredAuth = LfsRepositorySupport.basicAuth("", "tok"))
        try {
            val dir = File(root, "auth").also { Git.init().setDirectory(it).setInitialBranch("main").call().close() }
            Git.open(dir).use { git ->
                git.repository.config.setString("lfs", null, "url", authed.url)
                git.repository.config.save()
                val pointer = LfsPointer(LfsOid(sha256("hello".toByteArray())), 5)
                authed.objects[pointer.oid.hex] = "hello".toByteArray()
                val support = LfsRepositorySupport({ throw IllegalStateException() }, { io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) { configureLfsTimeouts() } })
                // afterClone needs a HEAD; exercise the download path through a repo with one pointer commit instead.
                File(dir, "h.bin").writeBytes(LfsPointerCodec.serialize(pointer))
                File(dir, ".gitattributes").writeText("*.bin filter=lfs -text\n")
                git.repository.config.setString("user", null, "name", "t"); git.repository.config.setString("user", null, "email", "t@e.x"); git.repository.config.save()
                support.prepare(git.repository, null)
                git.add().addFilepattern(".").call(); git.commit().setMessage("p").call()
                runBlocking { support.afterClone(git, LfsRepositorySupport.basicAuth("", "tok")) }
            }
            assertEquals("hello", File(root, "auth/h.bin").readText())
            val batch = authed.requests.filter { it.path == "/objects/batch" }
            assertTrue(batch.isNotEmpty() && batch.all { it.authorization == LfsRepositorySupport.basicAuth("", "tok") })
            assertTrue(authed.requests.filter { it.path != "/objects/batch" }.all { it.authorization == null }, "object hrefs must not get the token")
        } finally {
            authed.close()
        }
    }
}
