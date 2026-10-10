package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.PlatformFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 2.3.1e: symlinks are only observable on a real directory, with the JVM canonicalizer wired in. */
class MarkdownTargetWriterSymlinkTest {
    private lateinit var graph: File
    private lateinit var outside: File

    @BeforeTest
    fun setUp() {
        graph = Files.createTempDirectory("tw_graph_").toFile().also { File(it, "pages").mkdirs() }
        outside = Files.createTempDirectory("tw_outside_").toFile()
    }

    @AfterTest
    fun tearDown() {
        graph.deleteRecursively()
        outside.deleteRecursively()
    }

    private fun writer(): MarkdownTargetWriter {
        val fs = PlatformFileSystem().also { it.registerGraphRoot(graph.absolutePath); it.registerGraphRoot(outside.absolutePath) }
        val target = OffGraphTarget(GraphId("b"), graph.absolutePath, isActive = false)
        return MarkdownTargetWriter.forFilePaths(fs, target, TargetWriterCapabilities(platformSupportsOffGraphWrite = true))
    }

    private fun refusal(e: Either<DomainError, *>) =
        assertIs<DomainError.MergeError.WriteRefused>((e as Either.Left).value).reason

    private val page = MergePage("Linked", blocks = listOf(MergeBlock("11111111-1111-1111-1111-111111111111", "new")))

    @Test
    fun symlinkedPageFilePointingOutsideTheGraphIsRefusedAndTargetUntouched() = runBlocking {
        val victim = File(outside, "victim.md").also { it.writeText("- secret\n") }
        Files.createSymbolicLink(File(graph, "pages/Linked.md").toPath(), victim.toPath())

        val w = writer()
        assertIs<WriteRefusedReason.PathOutsideGraph>(refusal(w.write(PageKey("Linked"), page)))
        assertIs<WriteRefusedReason.PathOutsideGraph>(refusal(w.readExisting(PageKey("Linked"))))
        assertIs<WriteRefusedReason.PathOutsideGraph>(refusal(w.deletePageFile(PageKey("Linked"), "x")))

        assertEquals("- secret\n", victim.readText())
        assertEquals(listOf("victim.md"), outside.list()!!.toList())
    }

    @Test
    fun symlinkedPagesFolderPointingOutsideTheGraphIsRefusedAndNothingIsCreated() = runBlocking {
        graph.resolve("pages").deleteRecursively()
        Files.createSymbolicLink(File(graph, "pages").toPath(), outside.toPath())

        val result = writer().write(PageKey("Fresh"), page.copy(name = "Fresh"))

        assertIs<WriteRefusedReason.PathOutsideGraph>(refusal(result))
        assertTrue(outside.list()!!.isEmpty(), outside.list()!!.toList().toString())
    }

    @Test
    fun ordinaryPageInsideTheGraphStillWrites() = runBlocking {
        val result = writer().write(PageKey("Plain"), page.copy(name = "Plain"))

        assertIs<WriteOutcome.Created>((result as Either.Right).value)
        assertTrue(File(graph, "pages/Plain.md").readText().contains("new"))
        assertTrue(graph.walkTopDown().none { it.name.endsWith(".tmp") })
    }
}
