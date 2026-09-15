package dev.stapler.stelekit.git.merge

/**
 * Merge strategy for Logseq files that contain page references ([[links]], #tags, ((block-refs))).
 * Uses a three-way merge algorithm that tracks per-base-line fate from both local and remote diffs.
 */
class LogseqPageReferenceMergeStrategy : MergeStrategy {

    private sealed class LineFate {
        data class Equal(val line: String) : LineFate()
        data class Replace(val lines: List<String>) : LineFate()
        data object Delete : LineFate()
    }

    override fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean {
        return (base + local + remote).any { line ->
            line.contains("[[") || line.contains("((")
        }
    }

    override fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val (localFate, localInsertions) = buildFateMaps(base, local)
        val (remoteFate, remoteInsertions) = buildFateMaps(base, remote)

        // Build result. Deduplicate only within the same insertion position so that a
        // bullet added independently by both sides at the same base index appears once,
        // while the same bullet legitimately present at different positions is kept.
        val result = mutableListOf<String>()

        for (baseIdx in 0..base.size) {
            // Deduplicate insertions at this position only — not globally.
            val localIns = localInsertions[baseIdx] ?: emptyList()
            val remoteIns = remoteInsertions[baseIdx] ?: emptyList()
            val seenAtPosition = mutableSetOf<String>()
            for (line in localIns) { if (seenAtPosition.add(line)) result.add(line) }
            for (line in remoteIns) { if (seenAtPosition.add(line)) result.add(line) }

            if (baseIdx < base.size) {
                val localFateEntry = localFate[baseIdx] ?: LineFate.Equal(base[baseIdx])
                val remoteFateEntry = remoteFate[baseIdx] ?: LineFate.Equal(base[baseIdx])

                when {
                    localFateEntry is LineFate.Replace && remoteFateEntry is LineFate.Replace -> {
                        val localLines = localFateEntry.lines
                        val remoteLines = remoteFateEntry.lines
                        if (localLines == remoteLines) {
                            // Convergent edit — both sides made the same change, safe to keep one copy
                            result.addAll(localLines)
                        } else {
                            val localFirst = localLines.firstOrNull()?.trim() ?: ""
                            val remoteFirst = remoteLines.firstOrNull()?.trim() ?: ""
                            val localLooksEmpty = localFirst == "-" || localFirst.isEmpty()
                            val remoteHasContent = remoteFirst != "-" && remoteFirst.isNotEmpty()
                            if (localLooksEmpty && remoteHasContent) {
                                // Local cleared a stub bullet; remote added content — keep remote
                                result.addAll(remoteLines)
                                result.addAll(localLines.drop(1))
                            } else {
                                // Genuine conflict — emit markers rather than silently concatenating
                                result.add(GitConflictMarkers.LOCAL_START)
                                result.addAll(localLines)
                                result.add(GitConflictMarkers.DIVIDER)
                                result.addAll(remoteLines)
                                result.add(GitConflictMarkers.REMOTE_END)
                            }
                        }
                    }
                    localFateEntry is LineFate.Replace -> result.addAll(localFateEntry.lines)
                    remoteFateEntry is LineFate.Replace -> result.addAll(remoteFateEntry.lines)
                    localFateEntry is LineFate.Delete -> { /* skip */ }
                    remoteFateEntry is LineFate.Delete -> { /* skip */ }
                    else -> result.add(base[baseIdx])
                }
            }
        }

        return result
    }

    /** Builds fate and insertion maps for a single side (local or remote) vs base. */
    private fun buildFateMaps(
        base: List<String>,
        side: List<String>,
    ): Pair<MutableMap<Int, LineFate>, MutableMap<Int, List<String>>> {
        val fate = mutableMapOf<Int, LineFate>()
        val insertions = mutableMapOf<Int, List<String>>()

        for (op in diff(base, side)) {
            when (op) {
                is SequenceOp.Equal -> {
                    for (k in op.i1 until op.i2) {
                        fate[k] = LineFate.Equal(base[k])
                    }
                }
                is SequenceOp.Replace -> {
                    val baseCount = op.i2 - op.i1
                    val sideCount = op.j2 - op.j1
                    val replacementLines = side.subList(op.j1, op.j2)
                    if (baseCount == sideCount) {
                        for (k in 0 until baseCount) {
                            fate[op.i1 + k] = LineFate.Replace(listOf(replacementLines[k]))
                        }
                    } else {
                        fate[op.i1] = LineFate.Replace(replacementLines)
                        for (k in 1 until baseCount) {
                            fate[op.i1 + k] = LineFate.Replace(emptyList())
                        }
                    }
                }
                is SequenceOp.Delete -> {
                    for (k in op.i1 until op.i2) {
                        fate[k] = LineFate.Delete
                    }
                }
                is SequenceOp.Insert -> {
                    val existing = insertions[op.i1] ?: emptyList()
                    insertions[op.i1] = existing + side.subList(op.j1, op.j2)
                }
            }
        }

        return Pair(fate, insertions)
    }
}
