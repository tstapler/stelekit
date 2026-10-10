// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig

/**
 * Minimal valid [GitConfig] for tests that just need *a* config, not specific field values — the
 * same literal repeated across `jvmTest`/`androidUnitTest`/`businessTest` `GitSyncService`-adjacent
 * tests before this extraction. See [StubGitRepository]'s KDoc for why `commonTest` is shared.
 */
val sampleConfig: GitConfig = GitConfig(
    graphId = "test-graph",
    repoRoot = "/repo",
    wikiSubdir = "",
    authType = GitAuthType.NONE,
)
