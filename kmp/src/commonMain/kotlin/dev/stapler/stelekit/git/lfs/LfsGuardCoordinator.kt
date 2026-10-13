// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.FileSystem

/** What the service-level guard looks at (ADR-003 section 2a). */
enum class LfsGuardInput {
    /** Judge the working file before staging (guard-only, off, no converting filter, iOS, ...). */
    WorkingTree,

    /** A converting clean filter will run at `git add`: skip the raw check; the index verifier is authoritative. */
    PostCleanIndex,

    /** The platform's own write path is the sole guard (web); the service-level check returns nothing. */
    Delegated,
}

/**
 * The seam [dev.stapler.stelekit.git.GitSyncService] calls before staging, so the service does not
 * grow LFS logic of its own. Owns the O6 policy in one place: [holdBack] true holds only the violating
 * files and syncs the rest (the default); flipping it to false makes [GitSyncService] refuse the whole commit.
 */
class LfsGuardCoordinator(
    private val fileSystem: FileSystem,
    private val modeSource: LfsModeSource = LfsModeSource(null),
    private val capabilities: LfsPlatformCapabilities = LfsPlatformCapabilities.GUARD_ONLY_PLATFORM,
    private val isAndroid: Boolean = false,
    private val delegated: Boolean = false,
    val holdBack: Boolean = DEFAULT_HOLD_BACK,
) {
    fun inputFor(graphId: String, cleanFilterActive: Boolean): LfsGuardInput {
        if (delegated) return LfsGuardInput.Delegated
        val mode = LfsModeResolver.effective(modeSource.get(graphId), capabilities, isAndroid)
        return if (mode == LfsMode.AUTO && cleanFilterActive) LfsGuardInput.PostCleanIndex else LfsGuardInput.WorkingTree
    }

    /** Judges [changedPaths] (repository-relative, `/`-separated) under [config]; never throws on a missing file. */
    suspend fun evaluate(config: GitConfig, changedPaths: Collection<String>, cleanFilterActive: Boolean): LfsGuardVerdict {
        val input = inputFor(config.graphId, cleanFilterActive)
        if (input == LfsGuardInput.Delegated || changedPaths.isEmpty()) return LfsGuardVerdict(emptyList(), changedPaths.toList())
        val policy = LfsPathPolicy(LfsRulesLoader(fileSystem, config.repoRoot).load(changedPaths))
        val verdict = LfsGuard.evaluate(changedPaths, policy, FileSystemLfsContentProbe(fileSystem, config.repoRoot))
        if (input == LfsGuardInput.WorkingTree) return verdict
        // PostCleanIndex: raw working files are about to be converted, so only damaged pointer files matter here.
        val held = verdict.held.filter { it.reason == LfsViolationReason.ConflictMarkers }
        val heldPaths = held.mapTo(HashSet()) { it.path }
        return LfsGuardVerdict(held, changedPaths.filter { it !in heldPaths })
    }

    companion object {
        /** Owner decision O6 (open): hold back the violating files and still sync the rest. */
        const val DEFAULT_HOLD_BACK = true
    }
}
