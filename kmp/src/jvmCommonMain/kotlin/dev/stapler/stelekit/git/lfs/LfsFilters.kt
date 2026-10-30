// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import org.eclipse.jgit.attributes.FilterCommand
import org.eclipse.jgit.attributes.FilterCommandFactory
import org.eclipse.jgit.attributes.FilterCommandRegistry
import org.eclipse.jgit.lib.Repository
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream

/**
 * Clean/smudge filters for `filter=lfs` paths. Neither touches the network: clean moves content into the
 * local store and emits a pointer; smudge restores content from the store, or leaves the pointer text in
 * place when the object has not been downloaded (so caps and auth stay in [LfsTransferService]).
 */
object LfsFilters {
    const val CLEAN = "jgit://stelekit/lfs/clean"
    const val SMUDGE = "jgit://stelekit/lfs/smudge"

    @Synchronized
    fun ensureRegistered() {
        if (!FilterCommandRegistry.isRegistered(CLEAN)) {
            FilterCommandRegistry.register(CLEAN, FilterCommandFactory { db, input, out -> CleanCommand(db, input, out) })
        }
        if (!FilterCommandRegistry.isRegistered(SMUDGE)) {
            FilterCommandRegistry.register(SMUDGE, FilterCommandFactory { db, input, out -> SmudgeCommand(db, input, out) })
        }
    }

    private fun store(db: Repository) = LfsObjectStore(db.directory)

    private fun InputStream.readUpTo(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private class CleanCommand(private val db: Repository, private val input: InputStream, out: OutputStream) :
        FilterCommand(input, out) {
        override fun run(): Int {
            val head = ByteArray(LfsPointerCodec.MAX_POINTER_BYTES)
            val n = input.readUpTo(head)
            if (n == 0) return -1 // empty files are never pointers (git-lfs behaviour)
            if (n < head.size && LfsPointerCodec.parse(head.copyOf(n)) is LfsPointerParse.Valid) {
                out.write(head, 0, n) // already a pointer (deferred object): never hash a pointer into a pointer
                return -1
            }
            val whole = SequenceInputStream(ByteArrayInputStream(head, 0, n), input)
            out.write(LfsPointerCodec.serialize(store(db).ingest(whole)))
            return -1
        }
    }

    private class SmudgeCommand(private val db: Repository, private val input: InputStream, out: OutputStream) :
        FilterCommand(input, out) {
        override fun run(): Int {
            val head = ByteArray(LfsPointerCodec.MAX_POINTER_BYTES)
            val n = input.readUpTo(head)
            val pointer = (LfsPointerCodec.parse(head.copyOf(n)) as? LfsPointerParse.Valid)?.pointer
                ?.takeIf { n < head.size }
            val store = store(db)
            if (pointer != null && store.has(pointer)) {
                store.file(pointer.oid).inputStream().use { it.copyTo(out) }
            } else {
                out.write(head, 0, n)
                input.copyTo(out)
            }
            return -1
        }
    }
}
