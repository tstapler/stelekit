// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import kotlin.io.path.createTempDirectory

/** Throwaway real-JGit work tree shared by the LFS safety-floor tests. */
class LfsRepoFixture : AutoCloseable {
    val dir: File = createTempDirectory("stelekit_lfs_guard_").toFile()
    val git: Git = Git.init().setDirectory(dir).setInitialBranch("main").call().also { git ->
        git.repository.config.apply {
            setString("user", null, "name", "Stelekit Test")
            setString("user", null, "email", "stelekit-test@example.com")
            // A developer machine with git-lfs installed has `filter.lfs.*` in ~/.gitconfig, and JGit shells out to it
            // at `add`, converting the very raw files these tests stage. A local identity filter shadows it.
            setString("filter", "lfs", "clean", "cat")
            setString("filter", "lfs", "smudge", "cat")
            setBoolean("filter", "lfs", "required", false)
            setString("core", null, "autocrlf", "false") // never normalise the CRLF pointers some tests stage on purpose
            save()
        }
    }
    val repo: Repository get() = git.repository

    fun write(path: String, bytes: ByteArray) {
        File(dir, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }

    fun write(path: String, text: String) = write(path, text.encodeToByteArray())

    fun add(vararg paths: String) {
        val cmd = git.add()
        paths.forEach { cmd.addFilepattern(it) }
        cmd.call()
    }

    fun commit(message: String = "c") = git.commit().setMessage(message).call()

    /** Every blob reachable from HEAD as (path, bytes); empty for an unborn branch. */
    fun headBlobs(): Map<String, ByteArray> {
        val head = repo.resolve(Constants.HEAD) ?: return emptyMap()
        val blobs = LinkedHashMap<String, ByteArray>()
        RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tw ->
                tw.addTree(walk.parseCommit(head).tree)
                tw.isRecursive = true
                while (tw.next()) {
                    if (tw.getFileMode(0) == FileMode.GITLINK) continue
                    blobs[tw.pathString] = repo.open(tw.getObjectId(0)).bytes
                }
            }
        }
        return blobs
    }

    /** Paths staged in the index whose content differs from HEAD (adds and modifications). */
    fun stagedPaths(): Set<String> {
        val status = git.status().call()
        return status.added + status.changed
    }

    override fun close() {
        git.close()
        dir.deleteRecursively()
    }

    companion object {
        val PNG: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(3_000)

        val POINTER: ByteArray = LfsPointerCodec.serialize(
            LfsPointer(LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"), 3_000_000),
        )
    }
}
