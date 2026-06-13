package dev.stapler.stelekit.git.merge

/**
 * Handles the case where exactly one side adds lines to the end of the base,
 * while the other side is unchanged.
 *
 * Triggers when:
 *   - local is longer than base, local starts with base, and remote equals base, OR
 *   - remote is longer than base, remote starts with base, and local equals base
 */
class SimpleAdditionMergeStrategy : MergeStrategy {

    override fun canHandle(base: List<String>, local: List<String>, remote: List<String>): Boolean {
        val localAdded = local.size > base.size &&
            local.subList(0, base.size) == base &&
            remote == base
        val remoteAdded = remote.size > base.size &&
            remote.subList(0, base.size) == base &&
            local == base
        return localAdded || remoteAdded
    }

    override fun applyMerge(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        return if (local.size >= remote.size) local else remote
    }
}
