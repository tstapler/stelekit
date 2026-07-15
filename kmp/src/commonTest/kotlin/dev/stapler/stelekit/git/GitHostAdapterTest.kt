// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitHostType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [GitHostAdapter] — pure host-detection + auth-header + API-base logic.
 * See project_plans/web-git-writeback/implementation/validation.md Epic 1.1 test mapping.
 */
class GitHostAdapterTest {

    @Test
    fun `TC-1_1_1-A detect classifies github com HTTPS and SSH remotes as GITHUB`() {
        assertEquals(
            GitHostType.GITHUB,
            GitHostAdapter.detect("https://github.com/tstapler/steno-wiki.git"),
        )
        assertEquals(
            GitHostType.GITHUB,
            GitHostAdapter.detect("git@github.com:tstapler/steno-wiki.git"),
        )
    }

    @Test
    fun `TC-1_1_1-A2 detect classifies gitlab com remotes as GITLAB`() {
        assertEquals(
            GitHostType.GITLAB,
            GitHostAdapter.detect("https://gitlab.com/tstapler-notes/wiki.git"),
        )
    }

    @Test
    fun `TC-1_1_1-B detect classifies an unknown host as UNSUPPORTED without throwing`() {
        assertEquals(
            GitHostType.UNSUPPORTED,
            GitHostAdapter.detect("https://git.example.org/tstapler/wiki.git"),
        )
    }

    @Test
    fun `TC-1_1_2-A authHeader returns Authorization Bearer for GITHUB and PRIVATE-TOKEN for GITLAB`() {
        assertEquals(
            "Authorization" to "Bearer ghp_abc123",
            GitHostAdapter.authHeader(GitHostType.GITHUB, "ghp_abc123"),
        )
        assertEquals(
            "PRIVATE-TOKEN" to "glpat-xyz789",
            GitHostAdapter.authHeader(GitHostType.GITLAB, "glpat-xyz789"),
        )
    }

    @Test
    fun `TC-1_1_2-A2 apiBase resolves the correct API root for GITHUB and GITLAB`() {
        assertEquals(
            "https://api.github.com/repos/tstapler/steno-wiki",
            GitHostAdapter.apiBase(GitHostType.GITHUB, "tstapler", "steno-wiki"),
        )
        assertEquals(
            "https://gitlab.com/api/v4/projects/tstapler-notes%2Fwiki",
            GitHostAdapter.apiBase(GitHostType.GITLAB, "tstapler-notes", "wiki"),
        )
    }

    @Test
    fun `TC-1_1_2-B apiBase percent-encodes the GitLab namespace project path without double-encoding`() {
        assertEquals(
            "https://gitlab.com/api/v4/projects/tstapler-notes%2Fwiki",
            GitHostAdapter.apiBase(GitHostType.GITLAB, "tstapler-notes", "wiki"),
        )
        // Boundary case: an owner/repo that already contains percent-encoded characters must not
        // be re-encoded (only the literal "/" separator is encoded).
        assertEquals(
            "https://gitlab.com/api/v4/projects/tstapler%2Dnotes%2Fwiki",
            GitHostAdapter.apiBase(GitHostType.GITLAB, "tstapler%2Dnotes", "wiki"),
        )
    }
}
