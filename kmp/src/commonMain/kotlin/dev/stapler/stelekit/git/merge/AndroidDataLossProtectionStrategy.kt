package dev.stapler.stelekit.git.merge

/**
 * Protects against data loss when a remote side is significantly smaller than the base,
 * suggesting the remote may have lost content (e.g. Android overwriting with partial data).
 *
 * Triggers when: remote is less than 70% the size of base AND local is at least as large as base.
 */
class AndroidDataLossProtectionStrategy : MergeStrategy {

    override fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean {
        if (base.isEmpty()) return false
        return remote.size < base.size * 0.7 && local.size >= base.size
    }

    override fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val remoteOnlyLines = findRemoteOnlyChanges(remote, base)
        return if (remoteOnlyLines.isNotEmpty()) {
            local + listOf("") + remoteOnlyLines
        } else {
            local
        }
    }

    /**
     * Returns lines from [remote] whose stripped content does not match any stripped line in [base].
     * Empty stripped lines are skipped.
     */
    internal fun findRemoteOnlyChanges(remote: List<String>, base: List<String>): List<String> {
        val baseStripped = base.map { it.trim() }.toSet()
        return remote.filter { line ->
            val stripped = line.trim()
            stripped.isNotEmpty() && stripped !in baseStripped
        }
    }
}
