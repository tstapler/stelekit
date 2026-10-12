// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

/** Lets the guard judge a file without loading a large binary. */
interface LfsContentProbe {
    /** Byte size of [path], or null when it cannot be determined (missing or unreadable). */
    suspend fun size(path: String): Long?

    /** At most [maxBytes] leading bytes of [path], or null when unreadable. */
    suspend fun readHead(path: String, maxBytes: Int): ByteArray?
}

enum class LfsViolationReason {
    /** Content at an LFS-tracked path is not a pointer (or could not be verified to be one). */
    RawBinary,

    /** Content looks like a pointer but violates the spec (CRLF, BOM, unsorted keys, ...). */
    CorruptPointer,

    /** A pointer file that still contains merge conflict markers. */
    ConflictMarkers,

    /** Already pushed: a raw blob at an LFS-tracked path that reached the remote outside the guard (detection only). */
    RawBinaryPushed,
}

data class LfsViolation(
    val path: String,
    val reason: LfsViolationReason,
    /** The `.gitattributes` file and line that made this path LFS-tracked, when known. */
    val ruleSource: String? = null,
)

/** Hold-back, not block: [held] stays out of the commit, [allowed] proceeds. */
data class LfsGuardVerdict(val held: List<LfsViolation>, val allowed: List<String>) {
    val heldPaths: Set<String> get() = held.mapTo(LinkedHashSet()) { it.path }
    val isClean: Boolean get() = held.isEmpty()

    companion object {
        val CLEAN = LfsGuardVerdict(emptyList(), emptyList())
    }
}

/**
 * Pure decision: a changed path is a violation iff `.gitattributes` stores it with LFS and its content
 * is neither a spec-valid pointer nor empty. Reads at most [LfsPointerCodec.MAX_POINTER_BYTES] bytes
 * of any file, and none at all for files that are too large to be a pointer.
 */
object LfsGuard {
    private const val CONFLICT_START = "<<<<<<<"
    private const val CONFLICT_SEPARATOR = "======="
    private const val CONFLICT_END = ">>>>>>>"

    suspend fun evaluate(changedPaths: Collection<String>, policy: LfsPathPolicy, probe: LfsContentProbe): LfsGuardVerdict {
        if (!policy.hasAnyRules) return LfsGuardVerdict(emptyList(), changedPaths.toList())
        val held = ArrayList<LfsViolation>()
        val allowed = ArrayList<String>()
        for (path in changedPaths) {
            val source = policy.ruleSource(path)
            if (source == null) {
                allowed += path
                continue
            }
            val reason = judge(path, probe)
            if (reason == null) allowed += path else held += LfsViolation(path, reason, source)
        }
        return LfsGuardVerdict(held, allowed)
    }

    /** Null means the content is acceptable at an LFS-tracked path. */
    private suspend fun judge(path: String, probe: LfsContentProbe): LfsViolationReason? {
        val size = probe.size(path) ?: return LfsViolationReason.RawBinary // cannot verify: fail closed
        if (size == 0L) return null
        if (size >= LfsPointerCodec.MAX_POINTER_BYTES) return LfsViolationReason.RawBinary
        val head = probe.readHead(path, LfsPointerCodec.MAX_POINTER_BYTES) ?: return LfsViolationReason.RawBinary
        if (head.isEmpty()) return null
        if (isPointerWithConflictMarkers(head)) return LfsViolationReason.ConflictMarkers
        return when (LfsPointerCodec.parse(head)) {
            is LfsPointerParse.Valid -> null
            is LfsPointerParse.Corrupt -> LfsViolationReason.CorruptPointer
            LfsPointerParse.NotAPointer -> LfsViolationReason.RawBinary
        }
    }

    private fun isPointerWithConflictMarkers(head: ByteArray): Boolean {
        val text = head.decodeToString()
        if ("git-lfs" !in text && "oid sha256:" !in text) return false
        val lines = text.lines()
        return lines.any { it.startsWith(CONFLICT_START) } &&
            lines.any { it.startsWith(CONFLICT_SEPARATOR) } &&
            lines.any { it.startsWith(CONFLICT_END) }
    }
}
