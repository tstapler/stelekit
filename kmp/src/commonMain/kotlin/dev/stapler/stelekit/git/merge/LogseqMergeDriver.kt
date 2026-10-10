package dev.stapler.stelekit.git.merge

data class MergeResult(
    val lines: List<String>,
    val hasConflictMarkers: Boolean,
)

class LogseqMergeDriver(
    private val strategies: List<MergeStrategy> = defaultStrategies(),
) {
    fun merge(base: List<String>, local: List<String>, remote: List<String>): MergeResult {
        val strategy = strategies.firstOrNull { it.canHandle(base, local, remote) }
            ?: Diff3MergeStrategy()
        val lines = strategy.applyMerge(base, local, remote)
        val hasConflictMarkers = lines.any { it.startsWith(GitConflictMarkers.LOCAL_START) }
        return MergeResult(lines, hasConflictMarkers)
    }

    companion object {
        // Diff3MergeStrategy (a real, insertion/deletion-aware three-way merge — see its doc) is
        // the terminal, always-canHandle=true strategy, replacing the old FallbackMergeStrategy
        // default. The narrower strategies ahead of it embed Logseq-specific semantics (bullet/
        // page-reference-aware dedup, Android data-loss heuristics) worth trying first; they're
        // otherwise unchanged.
        fun defaultStrategies(): List<MergeStrategy> = listOf(
            AndroidDataLossProtectionStrategy(),
            SimpleAdditionMergeStrategy(),
            NonOverlappingChangeMergeStrategy(),
            LogseqPageReferenceMergeStrategy(),
            Diff3MergeStrategy(),
        )
    }
}
