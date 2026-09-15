package dev.stapler.stelekit.git.merge

/**
 * Fallback merge strategy using a set-based union approach.
 * Always handles any input (canHandle returns true).
 *
 * Algorithm:
 * 1. Track which sides (base/local/remote) each line appears in.
 * 2. Iterate local lines in order, adding to result (deduplicating).
 * 3. Iterate remote lines — add if new to remote (not in base), or if deleted from local but in base+remote.
 */
class FallbackMergeStrategy : MergeStrategy {

    override fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean = true

    override fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        // Build lineSources: line content -> set of sides containing it
        val lineSources = mutableMapOf<String, MutableSet<String>>()
        for (line in base) lineSources.getOrPut(line) { mutableSetOf() }.add("base")
        for (line in local) lineSources.getOrPut(line) { mutableSetOf() }.add("local")
        for (line in remote) lineSources.getOrPut(line) { mutableSetOf() }.add("remote")

        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // First pass: iterate local in order
        for (line in local) {
            if (line !in seen) {
                seen.add(line)
                result.add(line)
            }
        }

        // Second pass: iterate remote
        for (line in remote) {
            val sources = lineSources[line] ?: emptySet()
            val inBase = "base" in sources
            val inLocal = "local" in sources

            when {
                line in seen -> continue // already included
                !inBase -> {
                    // New in remote (not in base) — add it
                    seen.add(line)
                    result.add(line)
                }
                !inLocal && inBase -> {
                    // Deleted in local but present in base and remote — add back
                    seen.add(line)
                    result.add(line)
                }
            }
        }

        return result
    }
}
