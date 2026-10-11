// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.git

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [wikiSubdirError] and [repoNameFromUrl] are pure `commonMain` string logic — moved here (from
 * `jvmTest`'s `WikiSubdirUriGuardTest`) so Android/iOS/wasmJs get the same coverage, not just JVM.
 */
class GitSetupScreenHelpersTest {

    @Test
    fun `wikiSubdirError accepts relative paths and rejects absolute or escaping ones`() {
        assertTrue(wikiSubdirError("") == null)
        assertTrue(wikiSubdirError("notes/pages") == null)
        listOf("/home/me/notes", "~/notes", "C:\\notes", "../up", "a/../b").forEach {
            assertTrue(wikiSubdirError(it) != null, it)
        }
    }

    @Test
    fun `repoNameFromUrl extracts the repository name`() {
        assertTrue(repoNameFromUrl("https://github.com/me/my-notes.git") == "my-notes")
        assertTrue(repoNameFromUrl("git@github.com:me/wiki") == "wiki")
        assertTrue(repoNameFromUrl("https://github.com/me/wiki/") == "wiki")
        assertTrue(repoNameFromUrl("") == null)
    }
}
