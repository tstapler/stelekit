package dev.stapler.stelekit.db

import arrow.core.Either
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real-filesystem check that canonical containment catches symlinks the lexical check cannot see. */
class PathContainmentSymlinkTest {
    private fun canonical(p: Path): String = p.toRealPath().toString()

    private fun resolved(name: String, journal: Boolean, graph: Path): String =
        (PageFileResolver.resolve(name, journal, graph.toString()) as Either.Right).value

    private fun withGraph(block: (graph: Path, outside: Path) -> Unit) {
        val base = Files.createTempDirectory("stelekit_contain_")
        try {
            val graph = Files.createDirectories(base.resolve("graph"))
            Files.createDirectories(graph.resolve("pages"))
            Files.createDirectories(graph.resolve("journals"))
            block(graph, Files.createDirectories(base.resolve("outside")))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun symlinkedFileOutsideGraphIsRejectedByCanonicalCheck() = withGraph { graph, outside ->
        val target = Files.writeString(outside.resolve("secret.md"), "- secret")
        Files.createSymbolicLink(graph.resolve("pages/Evil.md"), target)

        val path = resolved("Evil", false, graph)
        // Lexically inside, canonically outside.
        assertTrue(PageFileResolver.isWithin("$graph/pages", path))
        assertFalse(PageFileResolver.isWithin(canonical(graph.resolve("pages")), canonical(Path.of(path))))
    }

    @Test
    fun symlinkedFolderOutsideGraphIsRejectedByCanonicalCheck() = withGraph { graph, outside ->
        Files.delete(graph.resolve("journals"))
        Files.createSymbolicLink(graph.resolve("journals"), outside)

        val path = resolved("2026_10_07", true, graph)
        // File does not exist yet, so judge its canonical parent directory.
        assertFalse(PageFileResolver.isWithin(canonical(graph), canonical(Path.of(path).parent)))
        assertEquals(0, outside.toFile().list()?.size)
    }

    @Test
    fun regularFileInsideGraphIsAccepted() = withGraph { graph, _ ->
        val file = Files.writeString(graph.resolve("pages/Ok.md"), "- ok")
        assertTrue(PageFileResolver.isWithin(canonical(graph.resolve("pages")), canonical(file)))
        assertEquals(file.toString(), resolved("Ok", false, graph))
    }
}
