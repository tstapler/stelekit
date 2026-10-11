// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphPathTest {

    @Test
    fun `WikiSubdir detects empty or blank as root`() {
        assertTrue(WikiSubdir("").isRoot)
        assertTrue(WikiSubdir("  ").isRoot)
        assertFalse(WikiSubdir("logseq").isRoot)
    }

    @Test
    fun `EffectiveNotesPath appends pages and journals subdirectories`() {
        val path = EffectiveNotesPath("/tmp/my-repo/logseq")
        assertEquals("/tmp/my-repo/logseq/pages", path.pagesDir())
        assertEquals("/tmp/my-repo/logseq/journals", path.journalsDir())
    }

    @Test
    fun `SafUri identifies SAF URIs and decodes tree URI`() {
        val saf = SafUri("saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki")
        assertTrue(saf.isSaf)
        assertEquals("content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki", saf.decodeTreeUri())

        val local = SafUri("/tmp/my-repo")
        assertFalse(local.isSaf)
    }

    @Test
    fun `GraphLocation Local computes effective path with wikiSubdir`() {
        val locWithoutSubdir = GraphLocation.Local("/tmp/my-repo", WikiSubdir(""))
        assertEquals("/tmp/my-repo", locWithoutSubdir.effectivePath.value)

        val locWithSubdir = GraphLocation.Local("/tmp/my-repo", WikiSubdir("logseq"))
        assertEquals("/tmp/my-repo/logseq", locWithSubdir.effectivePath.value)
    }

    @Test
    fun `GraphLocation Saf computes effective path with wikiSubdir`() {
        val uri = SafUri("saf://tree-uri")
        val loc = GraphLocation.Saf(uri, WikiSubdir("notes"))
        assertEquals("saf://tree-uri/notes", loc.effectivePath.value)
    }

    @Test
    fun `GraphInfo exposes strongly typed path objects`() {
        val info = GraphInfo(
            id = GraphId("test-id"),
            path = "/tmp/my-repo",
            displayName = "my-repo",
            addedAt = 1000L,
            detectedRepoRoot = "/tmp/my-repo",
            detectedWikiSubdir = "logseq",
            effectivePath = "/tmp/my-repo/logseq",
        )

        assertEquals("logseq", info.wikiSubdirObj.value)
        assertEquals("/tmp/my-repo", info.repoRootObj.value)
        assertEquals("/tmp/my-repo/logseq", info.effectiveNotesPath.value)
    }
}
