// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.screens.git

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the Android SAF picker bug where a picked `content://`/`saf://` URI
 * could land directly in the Wiki subdirectory field (which must always be a plain relative path
 * under the repository root) — see the fix for the "Git Sync" wizard's subdirectory picker.
 */
class WikiSubdirUriGuardTest {

    @Test
    fun `looksLikeUri rejects a saf tree URI`() {
        assertTrue(looksLikeUri("saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki"))
    }

    @Test
    fun `looksLikeUri rejects a bare content URI`() {
        assertTrue(looksLikeUri("content://com.android.externalstorage.documents/tree/primary:personal-wiki"))
    }

    @Test
    fun `looksLikeUri accepts a plain relative subdirectory`() {
        assertFalse(looksLikeUri("logseq"))
        assertFalse(looksLikeUri("notes/pages"))
        assertFalse(looksLikeUri(""))
    }

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
