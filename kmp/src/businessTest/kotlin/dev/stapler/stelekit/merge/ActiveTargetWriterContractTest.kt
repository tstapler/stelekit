package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.db.GraphEpoch
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real file/DB hop: in-memory repositories, the real actor, `GraphWriter` and `GraphLoader`. */
class ActiveTargetWriterContractTest : TargetWriterContractSuite() {
    override fun newHarness(): TargetHarness = ActiveHarness()

    private val extra = mutableListOf<ActiveHarness>()
    private fun active(): ActiveHarness = ActiveHarness().also { extra += it }

    @AfterTest
    fun closeExtra() {
        runBlocking { extra.forEach { it.close() } }
        extra.clear()
    }

    private val key = PageKey("Target")
    private val incoming = MergePage("Target", blocks = listOf(MergeBlock("s1", "alpha"), MergeBlock("s2", "beta")))
    private val policy = MergePolicy(GraphId("src"), "Source")

    private suspend fun ActiveHarness.copy(from: MergePage = incoming, to: PageKey = key): Either<DomainError, WriteOutcome> {
        val outcome = mergePage(writer.readExisting(to).fold({ error("$it") }, { it }), from.copy(name = to.name), policy)
        val page = (outcome as? MergeOutcome.New)?.page ?: (outcome as MergeOutcome.Merged).page
        return writer.write(to, page)
    }

    @Test
    fun unloadedJournalStubKeepsExistingBlocks() = runBlocking<Unit> {
        val h = active()
        val journal = PageKey("2026-10-07", isJournal = true)
        h.seedStub("/graphs/active/journals/2026_10_07.md", journal, "- morning\n- evening")

        val result = h.copy(MergePage("2026-10-07", isJournal = true, blocks = listOf(MergeBlock("s1", "alpha"))), journal)

        assertTrue(result.isRight(), "$result")
        val text = checkNotNull(h.fs.readFile("/graphs/active/journals/2026_10_07.md"))
        assertTrue("- morning" in text && "- evening" in text && "alpha" in text, text)
    }

    @Test
    fun graphWriterRefusesToReplaceANonEmptyFileFromAnUnloadedPage() = runBlocking<Unit> {
        val h = active()
        h.seedStub("/graphs/active/pages/Stub.md", PageKey("Stub"), "- keep me")

        val result = h.rawSave(PageKey("Stub"), emptyList())

        assertTrue(result.isLeft())
        assertEquals("- keep me", h.fs.readFile("/graphs/active/pages/Stub.md"))
    }

    @Test
    fun noDiskConflictIsEmittedByTheWrites() = runBlocking<Unit> {
        val h = active()
        h.copy()
        h.editContent(key, "beta", "beta edited")
        h.copy()
        assertEquals(0, h.conflicts.get(), "self-writes must not look like external changes")
    }

    @Test
    fun uuidOwnedByAnotherPageIsNeverClobbered() = runBlocking<Unit> {
        val h = active()
        h.copy()
        val victim = assertNotNull(h.snapshot(key))
        val stolen = victim.blocks[0].uuid!!
        val other = PageKey("Other")

        val result = h.writer.write(other, MergePage("Other", blocks = listOf(MergeBlock(stolen, "intruder"))))

        val err = assertIs<DomainError.DatabaseError.WriteFailed>((result as Either.Left).value)
        assertTrue("uuid collision" in err.message)
        assertEquals(victim, h.snapshot(key))
        assertNull(h.fileText(other))
    }

    @Test
    fun uuidLookupErrorFailsClosedAndWritesNothing() = runBlocking<Unit> {
        val h = active()
        h.copy()
        val before = h.fileText(key)
        h.failUuidLookup = true

        val result = h.copy(MergePage("Target", blocks = listOf(MergeBlock("s9", "late"))))

        assertTrue(result.isLeft())
        assertEquals(before, h.fileText(key))
    }

    @Test
    fun pageWithPendingEditsIsDeferredNotClobbered() = runBlocking<Unit> {
        val h = active()
        h.copy()
        val before = h.fileText(key)
        h.editInEditor(key, "beta", "typing...")
        val late = MergePage("Target", blocks = listOf(MergeBlock("s9", "late arrival")))

        val result = h.copy(late)

        val err = assertIs<DomainError.MergeError.Retryable>((result as Either.Left).value)
        assertEquals(WriteRetryReason.PageBeingEdited.message, err.message)
        assertEquals(before, h.fileText(key))
        h.flushEditor(key)
        assertTrue(h.copy(late).isRight())
        val blocks = assertNotNull(h.snapshot(key)).blocks
        assertTrue(blocks.any { it.content == "late arrival" })
        assertTrue(blocks.any { it.content == "typing..." })
    }

    @Test
    fun closedActorIsARetryableErrorAndWritesNothing() = runBlocking<Unit> {
        val h = active()
        h.copy()
        val before = h.fileText(key)
        h.actor.close()

        val result = h.copy(MergePage("Target", blocks = listOf(MergeBlock("s9", "late"))))

        assertEquals(WriteRetryReason.GraphClosed.message, assertIs<DomainError.MergeError.Retryable>((result as Either.Left).value).message)
        assertEquals(before, h.fileText(key))
    }

    @Test
    fun cancelledActorScopeIsARetryableError() = runBlocking<Unit> {
        val h = active()
        h.copy()
        h.actorScope.coroutineContext[Job]!!.cancelAndJoin()

        val result = h.copy(MergePage("Target", blocks = listOf(MergeBlock("s9", "late"))))

        assertEquals(WriteRetryReason.GraphClosed.message, assertIs<DomainError.MergeError.Retryable>((result as Either.Left).value).message)
    }

    @Test
    fun invalidPageNameIsRefused() = runBlocking<Unit> {
        val h = active()
        val result = h.writer.write(PageKey("a\u0000b"), MergePage("a\u0000b", blocks = listOf(MergeBlock("s1", "x"))))
        assertIs<DomainError.MergeError.WriteRefused>((result as Either.Left).value)
    }

    /**
     * @param awaitDirtyClear also treat the editor's in-memory dirty set as pending. That flag clears on an
     *   async DB confirmation that occasionally never arrives in this rig, so the property test opts out.
     */
    class ActiveHarness(repos: RepositorySet? = null, private val awaitDirtyClear: Boolean = true) : TargetHarness {
        private val root = "/graphs/active"
        val fs = FakeRelocationFileSystem()
        val conflicts = java.util.concurrent.atomic.AtomicInteger()
        private val pages: PageRepository = repos?.pageRepository ?: InMemoryPageRepository()
        private val blocks: BlockRepository = repos?.blockRepository ?: InMemoryBlockRepository()
        val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = repos?.writeActor ?: DatabaseWriteActor(blocks, pages, scope = actorScope)
        private val loader = GraphLoader(fs, pages, blocks, externalWriteActor = actor)
        private val editorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val graphWriter = GraphWriter(
            fs, actor,
            onFileWritten = loader::markFileWrittenByUs,
            onPreWrite = { loader.preMarkFileWrite(it) },
            onClearPendingWrite = { loader.clearFilePendingWrite(it) },
            checkPreWriteConflict = { path, disk ->
                val known = loader.fileRegistry.getContentHash(FilePath(path))
                known != null && disk.hashCode() != known
            },
            onPreWriteConflict = { _, _, _ -> conflicts.incrementAndGet() },
        )
        private val bsm: BlockStateManager

        init {
            graphWriter.currentEpoch = GraphEpoch(GraphId("active"), root, 1L)
            graphWriter.startAutoSave(editorScope, 50L)
            bsm = BlockStateManager(
                blockRepository = blocks, graphLoader = loader, scope = editorScope, graphWriter = graphWriter,
                pageRepository = pages, graphPathProvider = { root }, writeActor = actor,
                invalidationSource = actor.blockInvalidations, pushSource = actor.blocksPushed,
            )
        }

        @Volatile var failUuidLookup = false
        private val lookupBlocks = object : BlockRepository by blocks {
            override suspend fun getBlocksByUuids(uuids: List<BlockUuid>): Either<DomainError, List<Block>> =
                if (failUuidLookup) Either.Left(DomainError.DatabaseError.ReadFailed("boom")) else blocks.getBlocksByUuids(uuids)
        }

        override val writer = ActiveTargetWriter(
            pageRepository = pages, blockRepository = lookupBlocks, writeActor = actor, graphWriter = graphWriter, fs = fs, graphPath = root,
            isPageDirty = { id -> isEditing(id.value) },
        )

        private suspend fun isEditing(id: String) =
            bsm.hasPendingDiskWrite(id) || (awaitDirtyClear && id in bsm.dirtyPageUuids.value)

        private fun path(key: PageKey) = "$root/pages/${key.name}.md"

        fun fileText(key: PageKey): String? = fs.readFile(path(key))

        private suspend fun pageRow(key: PageKey): Page? = pages.getPageByName(key.name).first().fold({ null }, { it })

        private suspend fun rows(page: Page): List<Block> = blocks.getBlocksForPage(page.uuid).first().fold({ emptyList() }, { it })

        override suspend fun seed(key: PageKey, text: String) {
            fs.writeFile(path(key), text)
            loader.applyExternalFileChange(FilePath(path(key)), text, ParseMode.FULL, DatabaseWriteActor.Priority.HIGH)
        }

        override suspend fun seedUnloaded(key: PageKey, text: String) = seedStub(path(key), key, text)

        suspend fun seedStub(filePath: String, key: PageKey, text: String) {
            fs.writeFile(filePath, text)
            val now = kotlin.time.Clock.System.now()
            val date = if (key.isJournal) dev.stapler.stelekit.outliner.JournalUtils.parseJournalDate(key.name) else null
            actor.savePage(
                Page(
                    uuid = dev.stapler.stelekit.model.PageUuid(dev.stapler.stelekit.util.UuidGenerator.generateV7()),
                    name = key.name, filePath = filePath, createdAt = now, updatedAt = now,
                    isJournal = key.isJournal, journalDate = date, isContentLoaded = false,
                ),
            )
        }

        /** Edit through the actor and `GraphWriter`, file written at once. */
        override suspend fun editContent(key: PageKey, from: String, to: String) {
            val page = checkNotNull(pageRow(key))
            val block = rows(page).first { it.content == from }
            actor.updateBlockContentOnly(block.uuid, to, page.uuid)
            graphWriter.savePage(page, rows(page), root)
        }

        /** Edit through [BlockStateManager]; the disk save stays debounced until [flushEditor]. */
        suspend fun editInEditor(key: PageKey, from: String, to: String) {
            val page = checkNotNull(pageRow(key))
            bsm.observePage(page.uuid)
            val block = rows(page).first { it.content == from }
            withTimeout(10_000) { while (bsm.blocks.value[page.uuid.value].isNullOrEmpty()) delay(10) }
            bsm.updateBlockContent(block.uuid, to, block.version + 1).join()
        }

        /** Edits one block through the editor path (BlockStateManager -> actor -> debounced GraphWriter) and saves. */
        suspend fun editBlock(key: PageKey, uuid: String, content: String) {
            val page = checkNotNull(pageRow(key))
            bsm.observePage(page.uuid)
            withTimeout(10_000) { while (bsm.blocks.value[page.uuid.value].isNullOrEmpty()) delay(10) }
            val block = rows(page).first { it.uuid.value == uuid }
            bsm.updateBlockContent(block.uuid, content, block.version + 1).join()
            bsm.flush()
            // The DB confirmation that clears the dirty flag arrives asynchronously after the disk save.
            withTimeout(10_000) { while (isEditing(page.uuid.value)) delay(10) }
        }

        /** Block uuids a fresh `GraphLoader` derives from the file on disk (the real reload). */
        suspend fun reloadedUuids(key: PageKey): List<String> {
            val p2 = InMemoryPageRepository()
            val b2 = InMemoryBlockRepository()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor2 = DatabaseWriteActor(b2, p2, scope = scope)
            try {
                val loader2 = GraphLoader(fs, p2, b2, externalWriteActor = actor2)
                val text = checkNotNull(fs.readFile(path(key)))
                loader2.applyExternalFileChange(FilePath(path(key)), text, ParseMode.FULL, DatabaseWriteActor.Priority.HIGH)
                val page = checkNotNull(p2.getPageByName(key.name).first().fold({ null }, { it }))
                val byParent = b2.getBlocksForPage(page.uuid).first().fold({ emptyList() }, { it }).groupBy { it.parentUuid }
                fun walk(parent: BlockUuid?): List<String> =
                    byParent[parent].orEmpty().sortedBy { it.position }.flatMap { listOf(it.uuid.value) + walk(it.uuid) }
                return walk(null)
            } finally {
                actor2.close()
                scope.cancel()
            }
        }

        suspend fun rawSave(key: PageKey, blocksToSave: List<Block>): Either<DomainError, Unit> =
            graphWriter.savePage(checkNotNull(pageRow(key)), blocksToSave, root)

        suspend fun flushEditor(key: PageKey) {
            val id = checkNotNull(pageRow(key)).uuid.value
            bsm.flush()
            withTimeout(10_000) { while (isEditing(id)) delay(10) }
        }

        override suspend fun snapshot(key: PageKey): MergePage? {
            val text = fs.readFile(path(key)) ?: return null
            val fromFile = MergeConverters.parseMarkdown(text, path(key), key.name, key.isJournal).mergePage
            val page = checkNotNull(pageRow(key)) { "file without DB page" }
            val fromDb = MergeConverters.toMergePage(page, rows(page))
            assertEquals(fromFile.blocks, fromDb.blocks, "file re-parsed with the loader's pagePath must equal the DB")
            assertEquals(fromFile.properties, fromDb.properties)
            return fromFile
        }

        override suspend fun blockUuids(key: PageKey): List<String> {
            val page = pageRow(key) ?: return emptyList()
            val byParent = rows(page).groupBy { it.parentUuid }
            fun walk(parent: BlockUuid?): List<String> =
                byParent[parent].orEmpty().sortedBy { it.position }.flatMap { listOf(it.uuid.value) + walk(it.uuid) }
            return walk(null)
        }

        override suspend fun close() {
            runCatching { bsm.close() }
            editorScope.cancel()
            actor.close()
            actorScope.cancel()
        }
    }
}
