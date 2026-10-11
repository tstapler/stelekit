package dev.stapler.stelekit.git.merge

/**
 * Real three-way merge via [Diff3] — the safe, general-purpose last resort in
 * [LogseqMergeDriver]'s strategy chain, replacing [FallbackMergeStrategy]'s set-union heuristic
 * (which never emits a conflict marker, even when both sides genuinely diverge on the same
 * content — see [FallbackMergeStrategy]'s doc for the concrete failure mode).
 */
class Diff3MergeStrategy : MergeStrategy {
    override fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean = true

    override fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (chunk in Diff3.merge(base, local, remote)) {
            when (chunk) {
                is Diff3Chunk.Stable -> result.addAll(chunk.lines)
                is Diff3Chunk.Conflict -> {
                    result.add(GitConflictMarkers.LOCAL_START)
                    result.addAll(chunk.local)
                    if (chunk.base.isNotEmpty()) {
                        result.add(GitConflictMarkers.BASE_DIVIDER)
                        result.addAll(chunk.base)
                    }
                    result.add(GitConflictMarkers.DIVIDER)
                    result.addAll(chunk.remote)
                    result.add(GitConflictMarkers.REMOTE_END)
                }
            }
        }
        return result
    }
}
