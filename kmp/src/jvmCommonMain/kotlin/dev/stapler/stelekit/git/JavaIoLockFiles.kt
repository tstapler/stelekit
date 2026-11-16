// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.UUID

/**
 * [LockFiles] over one resolved git directory, shared by JVM and Android. Blocking I/O: call off the main thread.
 * Pass the directory returned by [resolveGitDir]; a non-directory makes the recovery lock unsupported (fail closed).
 */
class JavaIoLockFiles(private val gitDir: File) : LockFiles {
    private val recordFile = File(gitDir, RECORD_NAME)
    private val recoverFile = File(gitDir, RECOVER_NAME)

    override fun listAllowListedLocks(): List<LockFileEntry> {
        val found = FIXED_LOCKS.mapNotNull { name -> entryFor(name) }.toMutableList()
        val refs = File(gitDir, "refs")
        if (refs.isDirectory) {
            refs.walkTopDown().filter { it.isFile && it.name.endsWith(".lock") }.forEach { file ->
                val relative = file.relativeTo(gitDir).invariantSeparatorsPath
                if (LockAllowList.isAllowListed(relative)) found += LockFileEntry(relative, file.lastModified())
            }
        }
        return found
    }

    private fun entryFor(relative: String): LockFileEntry? {
        val file = File(gitDir, relative)
        return if (file.isFile) LockFileEntry(relative, file.lastModified()) else null
    }

    override fun readRecord(): RecordRead? = try {
        val bytes = Files.readAllBytes(recordFile.toPath())
        RecordRead(String(bytes, Charsets.UTF_8), recordFile.lastModified())
    } catch (_: NoSuchFileException) {
        null
    }

    override fun createRecordExclusive(content: String): Boolean {
        val temp = File(gitDir, "$RECORD_NAME.tmp-${UUID.randomUUID()}")
        try {
            Files.write(temp.toPath(), content.toByteArray(Charsets.UTF_8))
            return try {
                Files.createLink(recordFile.toPath(), temp.toPath())
                true
            } catch (_: FileAlreadyExistsException) {
                false
            } catch (_: UnsupportedOperationException) {
                createNewThenWrite(content)
            } catch (_: IOException) {
                // Hard links can be refused (FAT, some FUSE); an existing record still reports as such below.
                if (recordFile.exists()) false else createNewThenWrite(content)
            }
        } finally {
            temp.delete()
        }
    }

    // Fallback publication: a reader between CREATE_NEW and the write sees an empty file, which decodes as Torn (fail closed).
    private fun createNewThenWrite(content: String): Boolean = try {
        Files.newOutputStream(recordFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.write(content.toByteArray(Charsets.UTF_8))
        }
        true
    } catch (_: FileAlreadyExistsException) {
        false
    }

    override fun touchRecord(): Boolean = try {
        Files.setLastModifiedTime(recordFile.toPath(), FileTime.fromMillis(System.currentTimeMillis()))
        true
    } catch (_: NoSuchFileException) {
        false
    }

    override fun deleteRecord() {
        Files.deleteIfExists(recordFile.toPath())
    }

    override fun statLock(path: String): LockFileEntry? {
        if (!LockAllowList.isAllowListed(path)) return null
        val file = File(gitDir, path)
        return file.takeIf { it.isFile }?.let { LockFileEntry(path, it.lastModified()) }
    }

    // Linux/Android procfs only; any other platform, or an unreadable table, is "cannot tell" (null), never "none".
    override fun gitProcessMayBeRunning(): Boolean? {
        val proc = File("/proc")
        val entries = proc.listFiles { f -> f.name.all(Char::isDigit) } ?: return null
        val root = try { (gitDir.parentFile ?: return null).canonicalPath } catch (_: IOException) { return null }
        val found = entries.any { dir ->
            try {
                val args = File(dir, "cmdline").readText().split('\u0000')
                val exe = args.firstOrNull()?.substringAfterLast('/').orEmpty()
                (exe == "git" || exe.startsWith("git-")) &&
                    (args.any { it.startsWith(root) } || File(dir, "cwd").canonicalPath.startsWith(root))
            } catch (_: IOException) {
                false
            }
        }
        return if (found) true else null
    }

    override fun delete(path: String) {
        require(LockAllowList.isAllowListed(path)) { "not an allow-listed lock: $path" }
        Files.deleteIfExists(File(gitDir, path).toPath())
    }

    override fun tryLockRecovery(): RecoveryLockHandle? {
        if (!gitDir.isDirectory) throw RecoveryLockUnsupportedException("git dir is not a directory: $gitDir")
        val channel = try {
            FileChannel.open(recoverFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            throw RecoveryLockUnsupportedException("cannot open recovery file", e)
        }
        // The channel lives only for this lock, so closing it elsewhere cannot drop another channel's POSIX lock.
        val lock: FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (e: IOException) {
            channel.close()
            throw RecoveryLockUnsupportedException("filesystem rejected the recovery lock", e)
        }
        if (lock == null) {
            channel.close()
            return null
        }
        return object : RecoveryLockHandle {
            override fun release() {
                try {
                    lock.release()
                } finally {
                    channel.close()
                }
            }
        }
    }

    companion object {
        const val RECORD_NAME = "stelekit-sync.owner"
        const val RECOVER_NAME = "stelekit-sync.recover"
        private val FIXED_LOCKS = listOf("index.lock", "shallow.lock", "HEAD.lock", "packed-refs.lock", "config.lock")

        /**
         * The git directory for [repoRoot]: `.git` itself, or the target of a `gitdir:` line when `.git` is a file.
         * Returns the (non-directory) `.git` file when the line cannot be resolved, which [tryLockRecovery] rejects.
         */
        fun resolveGitDir(repoRoot: File): File {
            val dotGit = File(repoRoot, ".git")
            if (!dotGit.isFile) return dotGit
            val target = try {
                dotGit.readLines().firstOrNull { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim()
            } catch (_: IOException) {
                null
            } ?: return dotGit
            val resolved = File(target).let { if (it.isAbsolute) it else File(repoRoot, target) }
            return if (resolved.isDirectory) resolved else dotGit
        }
    }
}
