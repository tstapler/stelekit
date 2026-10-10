package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.StorageLocation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownTargetWriterTest {
    private val root = "/graphs/b"
    private val source = GraphId("src")
    private val policy = MergePolicy(source, "Source")

    /** Records every content write and can fail on demand. */
    private class RecordingFs : FakeRelocationFileSystem() {
        var writes = 0
        var failWrite = false
        var failRename = false

        override fun writeFileBytes(path: String, data: ByteArray): Boolean {
            if (failWrite) throw IllegalStateException("disk full")
            writes++
            return super.writeFileBytes(path, data)
        }

        override fun renameFile(from: String, to: String): Boolean = !failRename && super.renameFile(from, to)

        fun text(path: String) = readFileBytes(path)?.decodeToString()
        fun seed(path: String, text: String) { super.writeFileBytes(path, text.encodeToByteArray()) }
    }

    private val fs = RecordingFs()
    private val target = OffGraphTarget(GraphId("b"), root, isActive = false)
    private val caps = TargetWriterCapabilities(platformSupportsOffGraphWrite = true)
    private fun writer(
        t: OffGraphTarget = target,
        c: TargetWriterCapabilities = caps,
        canon: (String) -> String = { it },
    ) = MarkdownTargetWriter(fs, t, c, canon)

    private fun pagePath(name: String) = "$root/pages/$name.md"
    private fun block(uuid: String, content: String) = MergeBlock(
        uuid, content, mapOf(MergePropertyKeys.SRC_ID to "src:$uuid"),
    )

    private fun <L, R> Either<L, R>.ok(): R = if (this is Either.Right) value else error("expected Right but was $this")
    private fun refusal(e: Either<DomainError, *>): WriteRefusedReason {
        val err = (e as Either.Left).value
        return assertIs<DomainError.MergeError.WriteRefused>(err).reason
    }

    private fun isSubsequence(small: String, big: String): Boolean {
        var i = 0
        for (c in big) if (i < small.length && c == small[i]) i++
        return i == small.length
    }

    @Test
    fun mergeIntoExistingFileSplicesOnlyTheNewBlock() = runTest {
        fs.seed(pagePath("Projects"), "- A\n- B")
        fs.seed(pagePath("Other"), "- untouched\n")
        val w = writer()
        val existing = w.readExisting(PageKey("Projects")).ok()!!
        val merged = existing.copy(blocks = existing.blocks + block("c-uuid", "C"))

        val outcome = w.write(PageKey("Projects"), merged).ok()

        assertIs<WriteOutcome.Updated>(outcome)
        assertEquals("- A\n- B\n- C\n  id:: c-uuid\n  src-id:: src:c-uuid", fs.text(pagePath("Projects")))
        assertEquals("- untouched\n", fs.text(pagePath("Other")))
        assertEquals(1, outcome.insertedBlocks)
    }

    @Test
    fun nestedInsertionLandsAsLastChildOfTheMatchedParent() = runTest {
        fs.seed(pagePath("Tree"), MergeFixtures.UNLABELED_NESTED)
        val w = writer()
        val existing = w.readExisting(PageKey("Tree")).ok()!!
        val alpha = existing.blocks.first()
        val merged = existing.copy(blocks = listOf(alpha.copy(children = alpha.children + block("n-uuid", "nested new"))) + existing.blocks.drop(1))

        w.write(PageKey("Tree"), merged).ok()

        val after = w.readExisting(PageKey("Tree")).ok()!!
        assertEquals("nested new", after.blocks.first().children.last().content)
        assertEquals(existing.blocks.drop(1), after.blocks.drop(1))
    }

    @Test
    fun cleanFixturesKeepEveryOriginalByteInOrder() = runTest {
        val fixtures = mapOf(
            "crlf" to MergeFixtures.REAL_CRLF_CLEAN,
            "flat" to MergeFixtures.UNLABELED_FLAT,
            "nested" to MergeFixtures.UNLABELED_NESTED,
            "mixed" to MergeFixtures.MIXED_LABELED,
            "spaces" to MergeFixtures.SPACE_INDENTED,
        )
        for ((name, text) in fixtures) {
            fs.seed(pagePath(name), text)
            val w = writer()
            val readable = w.readExisting(PageKey(name))
            if (readable.isLeft()) continue // guard refusal is covered separately
            val existing = readable.ok()!!
            // A CRLF file cannot take a labeled block: the parser keeps the \r in the id:: value (RoundTripGuard refuses it).
            val added = if (name == "crlf") MergeBlock(null, "one new block") else block("new-$name", "one new block")
            val merged = existing.copy(blocks = existing.blocks + added)
            assertIs<WriteOutcome.Updated>(w.write(PageKey(name), merged).ok(), name)
            val result = fs.text(pagePath(name))!!
            assertTrue(isSubsequence(text, result), "$name: original bytes not preserved in order")
            assertTrue(result.contains("one new block"), name)
        }
    }

    @Test
    fun crlfFileRefusesALabeledBlockAndStaysUntouched() = runTest {
        fs.seed(pagePath("Crlf"), MergeFixtures.REAL_CRLF_CLEAN)
        val w = writer()
        val existing = w.readExisting(PageKey("Crlf")).ok()!!

        val result = w.write(PageKey("Crlf"), existing.copy(blocks = existing.blocks + block("x", "labeled")))

        assertIs<WriteRefusedReason.NotRoundTrippable>(refusal(result))
        assertEquals(MergeFixtures.REAL_CRLF_CLEAN, fs.text(pagePath("Crlf")))
    }

    @Test
    fun fixturesFailingTheGuardAreRefusedAndLeftUntouched() = runTest {
        listOf(MergeFixtures.FENCE_WITH_DASH_LINES, MergeFixtures.REAL_CRLF_TAB).forEach { text ->
            fs.seed(pagePath("Bad"), text)
            val writesBefore = fs.writes
            val merged = MergePage("Bad", blocks = listOf(block("x", "new")))

            val result = writer().write(PageKey("Bad"), merged)

            assertIs<WriteRefusedReason.NotRoundTrippable>(refusal(result))
            assertEquals(text, fs.text(pagePath("Bad")))
            assertEquals(writesBefore, fs.writes)
        }
    }

    @Test
    fun readExistingRefusesGuardFailuresAndReturnsNullForMissingPages() = runTest {
        fs.seed(pagePath("Bad"), MergeFixtures.FENCE_WITH_DASH_LINES)
        assertIs<WriteRefusedReason.NotRoundTrippable>(refusal(writer().readExisting(PageKey("Bad"))))
        assertNull(writer().readExisting(PageKey("Missing")).ok())
    }

    @Test
    fun identicalResultWritesNothing() = runTest {
        fs.seed(pagePath("Same"), "- a\n- b\n")
        val w = writer()
        val existing = w.readExisting(PageKey("Same")).ok()!!
        val writesBefore = fs.writes

        assertEquals(WriteOutcome.Unchanged, w.write(PageKey("Same"), existing).ok())
        assertEquals(writesBefore, fs.writes)
    }

    @Test
    fun repeatCopyThroughMergePageIsUnchangedAndWritesNothing() = runTest {
        fs.seed(pagePath("Projects"), "- A\n- B\n")
        val incoming = MergePage("Projects", blocks = listOf(MergeBlock("s1", "C"), MergeBlock("s2", "D", children = listOf(MergeBlock("s3", "E")))))
        val w = writer()
        val key = PageKey("Projects")

        val first = mergePage(w.readExisting(key).ok(), incoming, policy)
        assertIs<MergeOutcome.Merged>(first)
        assertIs<WriteOutcome.Updated>(w.write(key, first.page).ok())
        val afterFirst = fs.text(pagePath("Projects"))
        val writes = fs.writes

        // Re-read from disk: the second merge must see the first copy by src-id and add nothing.
        assertEquals(MergeOutcome.Unchanged, mergePage(w.readExisting(key).ok(), incoming, policy))
        assertEquals(WriteOutcome.Unchanged, w.write(key, w.readExisting(key).ok()!!).ok())
        assertEquals(afterFirst, fs.text(pagePath("Projects")))
        assertEquals(writes, fs.writes)
    }

    @Test
    fun brandNewPageIsRenderedWholeAndCreatesTheFolder() = runTest {
        val page = MergePage("Fresh", properties = mapOf("alias" to "f"), blocks = listOf(block("n1", "hello")))

        val outcome = writer().write(PageKey("Fresh"), page).ok()

        val created = assertIs<WriteOutcome.Created>(outcome)
        assertEquals(MergeRenderer.renderNewPage(page), fs.text(pagePath("Fresh")))
        assertEquals(created.contentHash, writer().fileHash(PageKey("Fresh")).ok())
        assertNull(writer().fileHash(PageKey("Nope")).ok())
        assertFalse(fs.allFilePaths().any { it.endsWith(".tmp") })
    }

    @Test
    fun writeFailingMidwayLeavesTheOriginalAndNoTempFile() = runTest {
        fs.seed(pagePath("P"), "- a\n")
        fs.failWrite = true
        val merged = MergePage("P", blocks = listOf(MergeBlock(null, "a"), block("x", "b")))

        val result = writer().write(PageKey("P"), merged)

        assertIs<DomainError.FileSystemError.WriteFailed>((result as Either.Left).value)
        assertEquals("- a\n", fs.text(pagePath("P")))
        assertFalse(fs.allFilePaths().any { it.endsWith(".tmp") }, fs.allFilePaths().toString())
    }

    @Test
    fun failedRenameRestoresTheOriginal() = runTest {
        fs.seed(pagePath("P"), "- a\n")
        fs.failRename = true
        val merged = MergePage("P", blocks = listOf(MergeBlock(null, "a"), block("x", "b")))

        assertTrue(writer().write(PageKey("P"), merged).isLeft())

        assertEquals("- a\n", fs.text(pagePath("P")))
        assertFalse(fs.allFilePaths().any { it.endsWith(".tmp") })
    }

    @Test
    fun mergedPageThatDropsAnExistingBlockIsRefused() = runTest {
        fs.seed(pagePath("P"), "- a\n- b\n")
        val result = writer().write(PageKey("P"), MergePage("P", blocks = listOf(MergeBlock(null, "a"))))
        assertIs<WriteRefusedReason.NotRoundTrippable>(refusal(result))
        assertEquals("- a\n- b\n", fs.text(pagePath("P")))
    }

    @Test
    fun encryptedAndPlatformUnsupportedTargetsAreRefusedWithoutWriting() = runTest {
        val merged = MergePage("P", blocks = listOf(block("x", "b")))
        val encrypted = writer(t = target.copy(encrypted = true)).write(PageKey("P"), merged)
        assertEquals(WriteRefusedReason.Unwritable(WriteCapabilityReason.Encrypted), refusal(encrypted))
        val unsupported = writer(c = TargetWriterCapabilities()).write(PageKey("P"), merged)
        assertEquals(WriteRefusedReason.Unwritable(WriteCapabilityReason.PlatformUnsupported), refusal(unsupported))
        assertEquals(0, fs.writes)
        assertTrue(fs.allFilePaths().isEmpty())
    }

    @Test
    fun grantLostMidRunFailsLaterPagesAndRetryAfterRegrantSucceeds() = runTest {
        var granted = true
        val saf = target.copy(storage = StorageLocation.SafFolder("b", "content://tree"))
        val c = TargetWriterCapabilities(
            platformSupportsOffGraphWrite = true,
            hasVerifiedSafGrant = { granted },
            safAtomicReplaceVerified = true,
        )
        val w = writer(t = saf, c = c)
        val results = (1..10).map { i ->
            if (i == 4) granted = false
            w.write(PageKey("P$i"), MergePage("P$i", blocks = listOf(block("u$i", "b$i"))))
        }

        assertTrue(results.take(3).all { it.isRight() })
        results.drop(3).forEach { assertIs<WriteCapabilityReason.NoGrant>((refusal(it) as WriteRefusedReason.Unwritable).reason) }
        assertEquals(3, fs.allFilePaths().size)

        granted = true
        val retried = (4..10).map { i -> w.write(PageKey("P$i"), MergePage("P$i", blocks = listOf(block("u$i", "b$i")))) }
        assertTrue(retried.all { it.isRight() })
        assertEquals(10, fs.allFilePaths().size)
    }

    @Test
    fun safTargetStaysInboxOnlyUntilAtomicReplaceIsVerified() = runTest {
        val saf = target.copy(storage = StorageLocation.SafFolder("b", "content://tree"))
        val c = TargetWriterCapabilities(platformSupportsOffGraphWrite = true, hasVerifiedSafGrant = { true })
        val result = writer(t = saf, c = c).write(PageKey("P"), MergePage("P", blocks = listOf(block("x", "b"))))
        assertEquals(WriteRefusedReason.Unwritable(WriteCapabilityReason.SafInboxOnly), refusal(result))
        assertTrue(fs.allFilePaths().isEmpty())
    }

    @Test
    fun awkwardPageNamesNeverTouchAnythingOutsideTheGraphRoot() = runTest {
        val names = listOf("..", "../escape", "a/../../b", "/etc/passwd", "..\\..\\win", "...", "pages/../../x")
        for (name in names) {
            val result = writer().write(PageKey(name), MergePage(name, blocks = listOf(block("x", "b"))))
            assertTrue(result.isRight() || result.isLeft())
        }
        assertTrue(fs.allFilePaths().isNotEmpty())
        assertTrue(fs.allFilePaths().all { it.startsWith("$root/pages/") && !it.removePrefix("$root/pages/").contains('/') }, fs.allFilePaths().toString())
    }

    @Test
    fun nulInPageNameIsInvalidPageName() = runTest {
        val result = writer().write(PageKey("a\u0000b"), MergePage("a\u0000b", blocks = listOf(block("x", "b"))))
        assertIs<WriteRefusedReason.InvalidPageName>(refusal(result))
        assertTrue(fs.allFilePaths().isEmpty())
    }

    @Test
    fun pathResolvingOutsideTheRootAfterCanonicalizationIsRefused() = runTest {
        fs.seed(pagePath("Linked"), "- a\n")
        val escaping: (String) -> String = { if (it.endsWith("Linked.md")) "/elsewhere/Linked.md" else it }
        val w = writer(canon = escaping)

        val result = w.write(PageKey("Linked"), MergePage("Linked", blocks = listOf(MergeBlock(null, "a"), block("x", "b"))))

        assertIs<WriteRefusedReason.PathOutsideGraph>(refusal(result))
        assertIs<WriteRefusedReason.PathOutsideGraph>(refusal(w.readExisting(PageKey("Linked"))))
        assertEquals("- a\n", fs.text(pagePath("Linked")))
    }

    @Test
    fun journalReusesAnExistingHyphenatedFileForTheSameDate() = runTest {
        fs.seed("$root/journals/2026-10-07.md", "- morning\n")
        val w = writer()
        val key = PageKey("2026_10_07", isJournal = true)
        val existing = w.readExisting(key).ok()!!

        w.write(key, existing.copy(blocks = existing.blocks + block("j1", "captured"))).ok()

        assertTrue(fs.text("$root/journals/2026-10-07.md")!!.contains("captured"))
        assertFalse(fs.fileExists("$root/journals/2026_10_07.md"))
    }

    @Test
    fun deletePageFileRequiresTheExpectedHash() = runTest {
        val created = writer().write(PageKey("Gone"), MergePage("Gone", blocks = listOf(block("x", "b")))).ok()
        val hash = (created as WriteOutcome.Created).contentHash

        val wrong = writer().deletePageFile(PageKey("Gone"), "deadbeef")
        assertIs<DomainError.ConflictError.ConcurrentWrite>((wrong as Either.Left).value)
        assertTrue(fs.fileExists(pagePath("Gone")))

        writer().deletePageFile(PageKey("Gone"), hash).ok()
        assertFalse(fs.fileExists(pagePath("Gone")))
        writer().deletePageFile(PageKey("Gone"), hash).ok() // already gone is success
    }

    @Test
    fun removeBlocksRestoresTheOriginalBytesWhenNothingWasEdited() = runTest {
        val original = "- A\n- B\n"
        fs.seed(pagePath("U"), original)
        val w = writer()
        val key = PageKey("U")
        val existing = w.readExisting(key).ok()!!
        val parent = MergeBlock("p1", "parent", mapOf(MergePropertyKeys.SRC_ID to "src:p1"), listOf(block("c1", "child")))
        w.write(key, existing.copy(blocks = existing.blocks + parent + block("p2", "second"))).ok()
        val hashes = mapOf("p1" to BlockContentHash.of(parent), "p2" to BlockContentHash.of(block("p2", "second")))

        val report = w.removeBlocks(key, setOf("p1", "c1", "p2"), hashes).ok()

        assertEquals(setOf("p1", "c1", "p2"), report.removed)
        assertEquals(original, fs.text(pagePath("U")))
    }

    @Test
    fun removeBlocksLeavesEditedAndReportsMissing() = runTest {
        fs.seed(pagePath("U"), "- A\n")
        val w = writer()
        val key = PageKey("U")
        val added = block("p1", "mine")
        w.write(key, MergePage("U", blocks = listOf(MergeBlock(null, "A"), added))).ok()
        val edited = fs.text(pagePath("U"))!!.replace("mine", "mine, edited")
        fs.seed(pagePath("U"), edited)

        val report = w.removeBlocks(key, setOf("p1", "ghost"), mapOf("p1" to BlockContentHash.of(added))).ok()

        assertEquals(RemoveReport(emptySet(), setOf("p1"), setOf("ghost")), report)
        assertEquals(edited, fs.text(pagePath("U")))
    }

    @Test
    fun removeBlocksOnMissingPageReportsEverythingMissing() = runTest {
        val report = writer().removeBlocks(PageKey("Nope"), setOf("a"), emptyMap()).ok()
        assertEquals(setOf("a"), report.missing)
        assertNotNull(report)
    }
}
