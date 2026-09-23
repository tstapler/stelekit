package dev.stapler.stelekit.git.merge

/**
 * Handles three-way merge when all three sides have the same number of lines.
 * Performs per-line merge with conflict markers when both local and remote changed the same line.
 */
class NonOverlappingChangeMergeStrategy : MergeStrategy {

    override fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean {
        return base.size == local.size && local.size == remote.size
    }

    override fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (i in base.indices) {
            val baseLine = base[i]
            val localLine = local[i]
            val remoteLine = remote[i]
            when {
                localLine == baseLine && remoteLine == baseLine -> result.add(baseLine)
                localLine != baseLine && remoteLine == baseLine -> result.add(localLine)
                localLine == baseLine && remoteLine != baseLine -> result.add(remoteLine)
                localLine == remoteLine -> result.add(localLine) // both changed the same way
                else -> {
                    // Both changed differently — emit conflict markers
                    result.add("<<<<<<< LOCAL")
                    result.add(localLine)
                    result.add("=======")
                    result.add(remoteLine)
                    result.add(">>>>>>> REMOTE")
                }
            }
        }
        return result
    }
}
