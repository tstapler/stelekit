// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The git-lfs-compatible local object store at `<gitdir>/lfs/objects/aa/bb/<oid>`. An object only becomes
 * visible after its SHA-256 and size were verified and it was atomically moved into place, so a truncated
 * download can never satisfy [has].
 */
class LfsObjectStore(gitDir: File) {
    private val objectsDir = File(gitDir, "lfs/objects")
    private val tmpDir = File(gitDir, "lfs/tmp")

    fun file(oid: LfsOid): File = File(objectsDir, "${oid.hex.substring(0, 2)}/${oid.hex.substring(2, 4)}/${oid.hex}")

    fun has(pointer: LfsPointer): Boolean = file(pointer.oid).let { it.isFile && it.length() == pointer.size }

    fun newTempFile(): File {
        tmpDir.mkdirs()
        return File.createTempFile("lfs-", ".part", tmpDir)
    }

    /** Moves [temp] into the store if it hashes to [pointer]; deletes it and returns false otherwise. */
    fun commit(temp: File, pointer: LfsPointer): Boolean {
        if (temp.length() != pointer.size || sha256Hex(temp) != pointer.oid.hex) {
            temp.delete()
            return false
        }
        moveIntoPlace(temp, file(pointer.oid))
        return true
    }

    /** Streams [input] into the store (clean filter) and returns the pointer that now describes it. */
    fun ingest(input: InputStream): LfsPointer {
        val temp = newTempFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            temp.outputStream().buffered().use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    size += n
                }
            }
            val pointer = LfsPointer(LfsOid(digest.digest().toHex()), size)
            val dest = file(pointer.oid)
            if (dest.isFile && dest.length() == size) temp.delete() else moveIntoPlace(temp, dest)
            return pointer
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun moveIntoPlace(temp: File, dest: File) {
        dest.parentFile.mkdirs()
        try {
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
