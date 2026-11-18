// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem

/**
 * The files of one project's `backup/` directory. A kept copy is never overwritten: the first is `<uuid>.<kind>.json`, a later
 * one with different bytes is `<uuid>.<kind>.<n>.json`, and identical bytes reuse the file that already holds them.
 */
internal class SidecarBackupFiles(private val fs: FileSystem, private val writeVerified: (String, ByteArray) -> DomainError?) {
    /** Keeps [bytes] as the next distinct copy of [kind] for [uuid] in [dir]; the path of the copy that holds them. */
    fun keep(dir: String, uuid: String, kind: String, bytes: ByteArray): Either<DomainError, String> {
        val existing = copies(dir, uuid, kind)
        existing.firstOrNull { fs.readFileBytes(it.path)?.contentEquals(bytes) == true }?.let { return it.path.right() }
        val next = (existing.maxOfOrNull { it.number } ?: -1) + 1
        val path = if (next == 0) "$dir/$uuid.$kind.json" else "$dir/$uuid.$kind.$next.json"
        writeVerified(path, bytes)?.let { return it.left() }
        return path.right()
    }

    /** The newest kept copy of [kind] for [uuid], or null. */
    fun latest(dir: String, uuid: String, kind: String): ByteArray? = copies(dir, uuid, kind).lastOrNull()?.let { fs.readFileBytes(it.path) }

    /** Deletes the v1 originals of [dir]; the new-editor copies a restore kept are the only copy of that work and stay. */
    fun deleteV1(dir: String): Either<DomainError, Int> {
        var deleted = 0
        for (name in fs.listFiles(dir)) {
            if (V2_BEFORE_RESTORE in name) continue
            if (!fs.deleteFile("$dir/$name")) return DomainError.FileSystemError.WriteFailed("$dir/$name", "delete failed").left()
            deleted++
        }
        return deleted.right()
    }

    private class Copy(val path: String, val number: Int)

    private fun copies(dir: String, uuid: String, kind: String): List<Copy> {
        val numbered = Regex("${Regex.escape(uuid)}\\.${Regex.escape(kind)}\\.(\\d+)\\.json")
        return fs.listFiles(dir).mapNotNull { name ->
            when {
                name == "$uuid.$kind.json" -> Copy("$dir/$name", 0)
                else -> numbered.matchEntire(name)?.let { Copy("$dir/$name", it.groupValues[1].toInt()) }
            }
        }.sortedBy { it.number }
    }

    companion object {
        const val V1 = "v1"
        const val V2_BEFORE_RESTORE = "v2-before-restore"
    }
}
