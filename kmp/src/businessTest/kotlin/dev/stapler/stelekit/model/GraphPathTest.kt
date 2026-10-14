package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GraphPathTest {
    @Test fun `trailing slash and doubled separators normalize to one value`() {
        assertEquals(GraphPath.of("/data/graphs/a"), GraphPath.of("/data/graphs/a/"))
        assertEquals(GraphPath.of("/data/graphs/a"), GraphPath.of("/data//graphs/a"))
    }

    @Test fun `backslashes normalize to forward slashes`() {
        assertEquals("C:/notes/wiki", GraphPath.of("C:\\notes\\wiki").value)
    }

    @Test fun `uri scheme separator is preserved`() {
        assertEquals("content://tree/primary%3Anotes", GraphPath.of("content://tree/primary%3Anotes/").value)
    }

    @Test fun `root stays a single slash`() {
        assertEquals("/", GraphPath.of("/").value)
    }

    @Test fun `wikiRoot joins repo root and subdirectory`() {
        assertEquals("/repo/logseq", GraphPath.wikiRoot("/repo/", "logseq").value)
        assertEquals("/repo/wiki/notes", GraphPath.wikiRoot("/repo", "/wiki/notes/").value)
    }

    @Test fun `empty subdirectory means the repo root is the graph`() {
        assertEquals(GraphPath.of("/repo"), GraphPath.wikiRoot("/repo", ""))
    }

    @Test fun `repo root and wiki root are different graphs`() {
        assertNotEquals(GraphPath.of("/repo"), GraphPath.wikiRoot("/repo", "logseq"))
    }
}
