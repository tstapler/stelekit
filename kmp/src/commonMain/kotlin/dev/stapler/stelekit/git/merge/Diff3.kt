package dev.stapler.stelekit.git.merge

/** One chunk of a three-way merge result: either agreed content, or a genuine conflict. */
sealed class Diff3Chunk {
    data class Stable(val lines: List<String>) : Diff3Chunk()
    data class Conflict(val base: List<String>, val local: List<String>, val remote: List<String>) : Diff3Chunk()
}

/**
 * Three-way ("diff3") line merge: aligns [base]/[local]/[remote] using the existing LCS-based
 * [diff] engine — once for base-vs-local, once for base-vs-remote — then walks the two
 * alignments together to find base lines left unchanged on BOTH sides ("anchors"). Between two
 * consecutive anchors, the region auto-resolves to whichever side actually changed (or either,
 * if both made the identical change) and becomes a [Diff3Chunk.Conflict] only when both sides
 * changed the same region differently — an insertion/deletion-aware alignment, unlike a naive
 * same-position line comparison.
 *
 * This is the real, general-purpose replacement this project's `FallbackMergeStrategy` needed:
 * that strategy is a set-union heuristic that never detects a conflict at all (two independent
 * edits to the same base line silently become two adjacent output lines, no marker, and content
 * can be silently duplicated) — see [Diff3MergeStrategy], which wires this engine into
 * [LogseqMergeDriver]'s strategy chain in Fallback's place.
 */
object Diff3 {
    fun merge(base: List<String>, local: List<String>, remote: List<String>): List<Diff3Chunk> {
        val localMatch = matchArray(base, local)
        val remoteMatch = matchArray(base, remote)

        val chunks = mutableListOf<Diff3Chunk>()
        var prevBase = -1
        var prevLocal = -1
        var prevRemote = -1

        fun addGap(baseGap: List<String>, localGap: List<String>, remoteGap: List<String>) {
            if (baseGap.isEmpty() && localGap.isEmpty() && remoteGap.isEmpty()) return
            when {
                localGap == remoteGap -> chunks.add(Diff3Chunk.Stable(localGap))
                localGap == baseGap -> chunks.add(Diff3Chunk.Stable(remoteGap))
                remoteGap == baseGap -> chunks.add(Diff3Chunk.Stable(localGap))
                else -> chunks.add(Diff3Chunk.Conflict(baseGap, localGap, remoteGap))
            }
        }

        var s = 0
        while (s <= base.size) {
            val isEnd = s == base.size
            val isAnchor = !isEnd && localMatch[s] >= 0 && remoteMatch[s] >= 0
            if (isEnd || isAnchor) {
                val thisLocal = if (isEnd) local.size else localMatch[s]
                val thisRemote = if (isEnd) remote.size else remoteMatch[s]
                addGap(
                    base.subList(prevBase + 1, s),
                    local.subList(prevLocal + 1, thisLocal),
                    remote.subList(prevRemote + 1, thisRemote),
                )
                if (!isEnd) chunks.add(Diff3Chunk.Stable(listOf(base[s])))
                prevBase = s
                prevLocal = thisLocal
                prevRemote = thisRemote
            }
            s++
        }

        return mergeAdjacentStable(chunks)
    }

    /** Coalesces adjacent [Diff3Chunk.Stable] runs (anchors are appended one line at a time). */
    private fun mergeAdjacentStable(chunks: List<Diff3Chunk>): List<Diff3Chunk> {
        val result = mutableListOf<Diff3Chunk>()
        for (chunk in chunks) {
            val last = result.lastOrNull()
            if (chunk is Diff3Chunk.Stable && last is Diff3Chunk.Stable) {
                result[result.size - 1] = Diff3Chunk.Stable(last.lines + chunk.lines)
            } else {
                result.add(chunk)
            }
        }
        return result
    }

    /** For each index in [a], the LCS-aligned index in [b], or -1 if [a][index] wasn't matched. */
    private fun matchArray(a: List<String>, b: List<String>): IntArray {
        val result = IntArray(a.size) { -1 }
        for (op in diff(a, b)) {
            if (op is SequenceOp.Equal) {
                for (offset in 0 until (op.i2 - op.i1)) {
                    result[op.i1 + offset] = op.j1 + offset
                }
            }
        }
        return result
    }
}

fun List<Diff3Chunk>.hasConflicts(): Boolean = any { it is Diff3Chunk.Conflict }

/**
 * Formats [Diff3Chunk]s into the plain `<<<<<<< / ======= / >>>>>>>` two-way marker format
 * [dev.stapler.stelekit.git.ConflictResolver.parseConflictFile] parses — no `|||||||` base
 * section, which is [Diff3MergeStrategy]/[GitConflictMarkers]'s journal-merge-specific
 * three-way marker convention instead. Used by platforms with no local JGit-produced working
 * tree (the web/wasmJs platform, which fetches base/local/remote blob content via the GitHub/
 * GitLab REST API) to plug real, line-level conflict hunks into the same downstream
 * hunk-resolution UI Android/Desktop use.
 */
fun List<Diff3Chunk>.toTwoWayConflictMarkerText(localLabel: String = "HEAD", remoteLabel: String = "origin"): String {
    val result = mutableListOf<String>()
    for (chunk in this) {
        when (chunk) {
            is Diff3Chunk.Stable -> result.addAll(chunk.lines)
            is Diff3Chunk.Conflict -> {
                result.add("<<<<<<< $localLabel")
                result.addAll(chunk.local)
                result.add("=======")
                result.addAll(chunk.remote)
                result.add(">>>>>>> $remoteLabel")
            }
        }
    }
    return result.joinToString("\n")
}
