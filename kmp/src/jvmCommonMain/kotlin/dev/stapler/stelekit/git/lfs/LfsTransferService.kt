// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import java.io.File

/** Moves LFS objects between the local `.git/lfs` store and the server through [LfsBatchClient]. */
class LfsTransferService(private val client: LfsBatchClient, private val store: LfsObjectStore, private val gitDir: File) {
    private val logger = Logger("LfsTransferService")

    /** Downloads every object in [pointers] that the store lacks; objects are verified before they become visible. */
    suspend fun download(pointers: Collection<LfsPointer>): Either<DomainError.GitError, Int> {
        val missing = pointers.distinctBy { it.oid }.filterNot { store.has(it) }
        if (missing.isEmpty()) return 0.right()
        // Store copy plus the materialised working file, so budget twice the payload.
        val needed = missing.sumOf { it.size } * 2
        val available = gitDir.usableSpace
        if (needed > available) return DomainError.GitError.LfsStorageInsufficient(needed, available).left()

        val instructions = client.batch("download", missing).fold({ return it.left() }, { it })
        var fetched = 0
        for (pointer in missing) {
            val obj = instructions[pointer.oid.hex] ?: return DomainError.GitError.LfsObjectMissing(pointer.oid.hex).left()
            val temp = store.newTempFile()
            try {
                client.download(obj, temp).onLeft { temp.delete(); return it.left() }
                if (!store.commit(temp, pointer)) return DomainError.GitError.LfsObjectIntegrityFailed(pointer.oid.hex).left()
                fetched++
            } catch (e: CancellationException) {
                temp.delete()
                throw e
            }
        }
        logger.info("lfs: downloaded $fetched object(s)")
        return fetched.right()
    }

    /** Uploads [pointers] before a push; any failure aborts so no pointer commit reaches the remote without its object. */
    suspend fun upload(pointers: Collection<LfsPointer>): Either<DomainError.GitError, Int> {
        val unique = pointers.distinctBy { it.oid }
        if (unique.isEmpty()) return 0.right()
        val instructions = client.batch("upload", unique).fold({ return it.left() }, { it })
        var sent = 0
        for (pointer in unique) {
            val obj = instructions[pointer.oid.hex] ?: return DomainError.GitError.PushFailed("Git LFS did not answer for ${pointer.oid.hex.take(8)}").left()
            val wantsUpload = obj.actions?.containsKey("upload") == true
            // A pointer that arrived from the remote may have no local object; only a server that wants it is a problem.
            if (!store.has(pointer)) {
                if (wantsUpload) return DomainError.GitError.LfsObjectMissing(pointer.oid.hex).left()
                continue
            }
            client.upload(obj, store.file(pointer.oid)).onLeft { return it.left() }
            if (wantsUpload) sent++
        }
        logger.info("lfs: uploaded $sent object(s)")
        return sent.right()
    }
}

/** Puts real bytes into the working tree where the filters left pointer text (object absent at checkout time). */
object LfsMaterializer {
    /**
     * Replaces working-tree pointer text with the real bytes for [paths] whose object is now in the store
     * (the filters leave pointer text when an object was absent at checkout time).
     */
    fun materialize(store: LfsObjectStore, workTree: File, paths: Map<String, LfsPointer>): Int {
        var written = 0
        for ((path, pointer) in paths) {
            val target = File(workTree, path)
            if (!target.isFile || !store.has(pointer)) continue
            val current = if (target.length() < LfsPointerCodec.MAX_POINTER_BYTES) target.readBytes() else continue
            val onDisk = (LfsPointerCodec.parse(current) as? LfsPointerParse.Valid)?.pointer ?: continue
            if (onDisk.oid != pointer.oid) continue
            val temp = File(target.parentFile, ".${target.name}.lfs-tmp")
            store.file(pointer.oid).copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            written++
        }
        return written
    }
}
