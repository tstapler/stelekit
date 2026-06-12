package dev.stapler.stelekit.git.merge

data class MergeResult(
    val lines: List<String>,
    val hasConflictMarkers: Boolean,
)

class LogseqMergeDriver(
    private val strategies: List<MergeStrategy> = defaultStrategies(),
) {
    fun merge(base: List<String>, local: List<String>, remote: List<String>): MergeResult {
        val strategy = strategies.first { it.canHandle(base, local, remote) }
        val lines = strategy.applyMerge(base, local, remote)
        val hasConflictMarkers = lines.any { it.startsWith("<<<<<<< LOCAL") }
        return MergeResult(lines, hasConflictMarkers)
    }

    companion object {
        fun defaultStrategies(): List<MergeStrategy> = listOf(
            AndroidDataLossProtectionStrategy(),
            SimpleAdditionMergeStrategy(),
            NonOverlappingChangeMergeStrategy(),
            LogseqPageReferenceMergeStrategy(),
            FallbackMergeStrategy(),
        )
    }
}
