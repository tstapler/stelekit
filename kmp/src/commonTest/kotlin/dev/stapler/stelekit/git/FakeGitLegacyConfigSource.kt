// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** Scripted [GitLegacyConfigSource]; counts calls (no @Test methods). */
class FakeGitLegacyConfigSource(var result: LegacyGitConfigResult = LegacyGitConfigResult.Unavailable) : GitLegacyConfigSource {
    var throwOnRead = false
    var calls = 0

    override suspend fun read(graphId: String): LegacyGitConfigResult {
        calls++
        if (throwOnRead) throw IllegalStateException("db open failed")
        return result
    }
}
