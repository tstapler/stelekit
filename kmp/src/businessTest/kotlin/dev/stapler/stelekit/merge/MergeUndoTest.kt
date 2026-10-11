package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeUndoTest {
    private val root = "/graphs/b"
    private val day = 24L * 60 * 60 * 1000
    private val fs = FakeRelocationFileSystem()
    private val store = MergeManifestStore(FakeFileSystem(), "/app")
    private val target = OffGraphTarget(GraphId("b"), root, isActive = false)
    private val real = MarkdownTargetWriter(fs, target, TargetWriterCapabilities(platformSupportsOffGraphWrite = true)) { it }
    private var writer: TargetWriter = real
    private var now = 1_000L
    private fun undo() = MergeUndo(store, { writer }, { now })

    private fun path(name: String) = "$root/pages/$name.md"
    private fun text(name: String) = fs.readFileBytes(path(name))?.decodeToString()
    private fun seed(name: String, t: String) { fs.writeFileBytes(path(name), t.encodeToByteArray()) }
    private fun <L, R> Either<L, R>.ok(): R = if (this is Either.Right) value else error("expected Right but was $this")

    /** Copies "New" (created) and u1/u2 into an existing "Projects", recording the manifest. */
    private suspend fun copy(started: Long = now) {
        seed("Projects", "- keep-1\n- keep-2")
        val created = real.write(PageKey("New"), MergePage("New", blocks = listOf(MergeBlock("n1", "fresh")))).ok()
        assertIs<WriteOutcome.Created>(created)
        val existing = real.readExisting(PageKey("Projects")).ok()!!
        val added = listOf(MergeBlock("u1", "one"), MergeBlock("u2", "two"))
        real.write(PageKey("Projects"), existing.copy(blocks = existing.blocks + added)).ok()
        val onDisk = real.readExisting(PageKey("Projects")).ok()!!.blocks.associateBy { it.uuid }
        val w = store.begin(MergeId("m1"), "src", "b", started).getOrNull()!!
        w.appendPage(ManifestPageEntry("New", createdFiles = listOf(CreatedFile(path("New"), created.contentHash))))
        w.appendPage(
            ManifestPageEntry(
                "Projects",
                addedBlockUuids = listOf("u1", "u2"),
                addedBlockHashes = listOf("u1", "u2").associateWith { BlockContentHash.of(onDisk.getValue(it)) },
            ),
        )
        w.complete()
    }

    private fun done(r: UndoResult) = assertIs<UndoResult.Done>(r)

    @Test
    fun safeRemovalDeletesCreatedFileAndAddedBlocksOnly() = runTest {
        copy()
        val r = done(undo().undo(MergeId("m1")))

        assertNull(text("New"))
        val projects = text("Projects")!!
        assertFalse("one" in projects || "two" in projects)
        assertTrue("keep-1" in projects && "keep-2" in projects)
        assertEquals(1, r.filesDeleted)
        assertEquals(2, r.blocksRemoved)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun blockEditedSinceStaysAndIsReported() = runTest {
        copy()
        seed("Projects", text("Projects")!!.replace("two", "two EDITED"))

        val r = done(undo().undo(MergeId("m1")))

        assertEquals(1, r.blocksRemoved)
        assertEquals(listOf<UndoIssue>(UndoIssue.BlocksChangedSinceCopy("Projects", setOf("u2"))), r.issues)
        assertTrue("two EDITED" in text("Projects")!!)
        assertFalse("one" in text("Projects")!!)
    }

    @Test
    fun editedCreatedFileIsPreserved() = runTest {
        copy()
        seed("New", text("New")!! + "\n- user added")

        val r = done(undo().undo(MergeId("m1")))

        assertEquals(0, r.filesDeleted)
        assertEquals(listOf<UndoIssue>(UndoIssue.FileChangedSinceCopy("New", path("New"))), r.issues)
        assertTrue("user added" in text("New")!!)
    }

    @Test
    fun undoTwiceIsANoOp() = runTest {
        copy()
        undo().undo(MergeId("m1"))
        val afterFirst = text("Projects")

        val second = done(undo().undo(MergeId("m1")))

        assertTrue(second.nothingLeft)
        assertEquals(afterFirst, text("Projects"))
    }

    @Test
    fun failureOnOnePageDoesNotStopTheRest() = runTest {
        copy()
        writer = object : TargetWriter by real {
            override suspend fun removeBlocks(page: PageKey, uuids: Set<String>, expectedContentHashes: Map<String, String>) =
                DomainError.DatabaseError.WriteFailed("boom").left()
        }

        val r = done(undo().undo(MergeId("m1")))

        assertEquals(1, r.filesDeleted)
        assertNull(text("New"))
        assertEquals("Projects", r.issues.single().pageName)
        assertIs<UndoIssue.Failed>(r.issues.single())
    }

    @Test
    fun expiredManifestIsUnavailable() = runTest {
        copy(started = 0L)
        now = 7 * day + 1

        assertEquals(UndoResult.Unavailable(UndoUnavailableReason.Expired), undo().undo(MergeId("m1")))
        assertTrue(text("New") != null)
    }

    @Test
    fun unknownMergeIdIsUnavailable() = runTest {
        assertEquals(UndoResult.Unavailable(UndoUnavailableReason.NotFound), undo().undo(MergeId("nope")))
    }
}
