package dev.stapler.stelekit.git.merge

/**
 * Merge strategy for Logseq files that contain page references ([[links]], #tags, ((block-refs))).
 * Uses a three-way merge algorithm that tracks per-base-line fate from both local and remote diffs.
 */
class LogseqPageReferenceMergeStrategy : MergeStrategy {

    override fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean {
        return (base + local + remote).any { line ->
            line.contains("[[") || line.contains("#") || line.contains("((")
        }
    }

    override fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val baseToLocal = diff(base, local)
        val baseToRemote = diff(base, remote)

        // Build fate maps: base_idx -> (tag, lines)
        // Tags: "equal", "replace", "delete"
        val localFate = mutableMapOf<Int, Pair<String, List<String>>>()
        val localInsertions = mutableMapOf<Int, List<String>>() // insertions before base_idx

        for (op in baseToLocal) {
            when (op) {
                is SequenceOp.Equal -> {
                    for (k in op.i1 until op.i2) {
                        localFate[k] = Pair("equal", listOf(base[k]))
                    }
                }
                is SequenceOp.Replace -> {
                    val baseCount = op.i2 - op.i1
                    val localCount = op.j2 - op.j1
                    val replacementLines = local.subList(op.j1, op.j2)
                    if (baseCount == localCount) {
                        // 1:1 replacement
                        for (k in 0 until baseCount) {
                            localFate[op.i1 + k] = Pair("replace", listOf(replacementLines[k]))
                        }
                    } else {
                        // First base idx gets all replacement lines, rest get empty
                        localFate[op.i1] = Pair("replace", replacementLines)
                        for (k in 1 until baseCount) {
                            localFate[op.i1 + k] = Pair("replace", emptyList())
                        }
                    }
                }
                is SequenceOp.Delete -> {
                    for (k in op.i1 until op.i2) {
                        localFate[k] = Pair("delete", emptyList())
                    }
                }
                is SequenceOp.Insert -> {
                    // Pure insertion: i1 == i2, inserted before base index i1
                    val existing = localInsertions[op.i1] ?: emptyList()
                    localInsertions[op.i1] = existing + local.subList(op.j1, op.j2)
                }
            }
        }

        val remoteFate = mutableMapOf<Int, Pair<String, List<String>>>()
        val remoteInsertions = mutableMapOf<Int, List<String>>()

        for (op in baseToRemote) {
            when (op) {
                is SequenceOp.Equal -> {
                    for (k in op.i1 until op.i2) {
                        remoteFate[k] = Pair("equal", listOf(base[k]))
                    }
                }
                is SequenceOp.Replace -> {
                    val baseCount = op.i2 - op.i1
                    val remoteCount = op.j2 - op.j1
                    val replacementLines = remote.subList(op.j1, op.j2)
                    if (baseCount == remoteCount) {
                        for (k in 0 until baseCount) {
                            remoteFate[op.i1 + k] = Pair("replace", listOf(replacementLines[k]))
                        }
                    } else {
                        remoteFate[op.i1] = Pair("replace", replacementLines)
                        for (k in 1 until baseCount) {
                            remoteFate[op.i1 + k] = Pair("replace", emptyList())
                        }
                    }
                }
                is SequenceOp.Delete -> {
                    for (k in op.i1 until op.i2) {
                        remoteFate[k] = Pair("delete", emptyList())
                    }
                }
                is SequenceOp.Insert -> {
                    val existing = remoteInsertions[op.i1] ?: emptyList()
                    remoteInsertions[op.i1] = existing + remote.subList(op.j1, op.j2)
                }
            }
        }

        // Build result
        val result = mutableListOf<String>()
        val seenLines = mutableSetOf<String>()

        fun addLine(line: String) {
            if (line !in seenLines) {
                seenLines.add(line)
                result.add(line)
            }
        }

        // Iterate base_idx from 0 to base.size (inclusive for trailing insertions)
        for (baseIdx in 0..base.size) {
            // Add local insertions at this position
            localInsertions[baseIdx]?.forEach { addLine(it) }
            // Add remote insertions at this position
            remoteInsertions[baseIdx]?.forEach { addLine(it) }

            if (baseIdx < base.size) {
                val localEntry = localFate[baseIdx]
                val remoteEntry = remoteFate[baseIdx]

                val localTag = localEntry?.first ?: "equal"
                val remoteTag = remoteEntry?.first ?: "equal"
                val localLines = localEntry?.second ?: listOf(base[baseIdx])
                val remoteLines = remoteEntry?.second ?: listOf(base[baseIdx])

                when {
                    localTag == "replace" && remoteTag == "replace" -> {
                        // Both replaced — prefer based on content heuristic
                        val localFirst = localLines.firstOrNull()?.trim() ?: ""
                        val remoteFirst = remoteLines.firstOrNull()?.trim() ?: ""
                        val localLooksEmpty = localFirst == "-" || localFirst.isEmpty()
                        val remoteHasContent = remoteFirst != "-" && remoteFirst.isNotEmpty()
                        if (localLooksEmpty && remoteHasContent) {
                            // Local looks like deletion/empty, prefer remote first then local tail
                            remoteLines.forEach { addLine(it) }
                            localLines.drop(1).forEach { addLine(it) }
                        } else {
                            // Prefer local, then remote
                            localLines.forEach { addLine(it) }
                            remoteLines.forEach { addLine(it) }
                        }
                    }
                    localTag == "replace" -> {
                        localLines.forEach { addLine(it) }
                    }
                    remoteTag == "replace" -> {
                        remoteLines.forEach { addLine(it) }
                    }
                    localTag == "delete" -> {
                        // skip
                    }
                    remoteTag == "delete" -> {
                        // skip
                    }
                    else -> {
                        // both equal or unknown — keep base line
                        addLine(base[baseIdx])
                    }
                }
            }
        }

        return result
    }
}
