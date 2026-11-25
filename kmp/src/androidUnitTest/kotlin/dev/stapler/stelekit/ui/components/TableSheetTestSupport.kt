// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.LadderAction
import dev.stapler.stelekit.table.LadderKey
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import dev.stapler.stelekit.table.formula.TableEngine
import dev.stapler.stelekit.ui.components.sheet.BlockStateManagerTablePort
import dev.stapler.stelekit.ui.components.sheet.DraftText
import dev.stapler.stelekit.ui.components.sheet.RecoveredDraftStore
import dev.stapler.stelekit.ui.components.sheet.SheetBodyHooks
import dev.stapler.stelekit.ui.components.sheet.SheetCloseReason
import dev.stapler.stelekit.ui.components.sheet.SheetBody
import dev.stapler.stelekit.ui.components.sheet.TableManagerPort
import dev.stapler.stelekit.ui.components.sheet.TableSheetHost
import dev.stapler.stelekit.ui.components.sheet.TableSheetSession
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock

internal fun withCell(content: String, address: CellAddress, text: String): String {
    val doc = (TableParser.parse(content) as TableParseResult.Parsed).doc
    val outcome = TableEngine.editor().apply(doc, TableEdit.SetCell(address, CellText(text))) as TableEditOutcome.Applied
    return TableSerializer.serialize(outcome.doc)
}

/** A body that records every call the host makes into it; its draft is set by the test. */
internal class FakeBodyHooks(private val session: () -> TableSheetSession?) : SheetBodyHooks {
    var draft: DraftText? = null
    var anchor: CellAddress? = CellAddress(2, 2)
    val rebuilds = mutableListOf<Pair<String, CellAddress?>>()
    val restored = mutableListOf<DraftText>()
    var discards = 0
    var pendingCommits = 0
    var backRung = LadderAction.NotHandled
    override val hasDirtyDraft get() = draft != null
    override val selectionAnchor get() = anchor
    override fun dirtyDraft() = draft
    override fun commitPendingDraft() {
        pendingCommits++
        val d = draft ?: return
        val s = session() ?: return
        draft = null
        s.commit(withCell(s.content, d.address, d.text), "edit ${d.address}", d)
    }
    override fun discardDraft() {
        discards++
        draft = null
    }
    override fun rebuild(content: String, keep: CellAddress?) {
        rebuilds += content to keep
    }
    override fun restoreDraft(address: CellAddress, text: String) {
        restored += DraftText(address, text)
        draft = DraftText(address, text)
    }
    override fun onBack(key: LadderKey) = backRung
}

private object NoFiles : FileSystem {
    override fun getDefaultGraphPath() = "/fake/graph"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = null
    override fun writeFile(path: String, content: String) = false
    override fun listFiles(path: String): List<String> = emptyList()
    override fun listDirectories(path: String): List<String> = emptyList()
    override fun fileExists(path: String) = false
    override fun directoryExists(path: String) = false
    override fun createDirectory(path: String) = false
    override fun deleteFile(path: String) = false
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
}

/** A repository whose content writes can be made to fail (the DB write returns Left). */
@OptIn(DirectRepositoryWrite::class)
private class FlakyBlockRepository(val inner: InMemoryBlockRepository) : BlockRepository by inner {
    var failContentWrites = false
    override suspend fun updateBlockContentOnly(blockUuid: BlockUuid, content: String): Either<DomainError, Unit> =
        if (failContentWrites) DomainError.DatabaseError.WriteFailed("test: write refused").left()
        else inner.updateBlockContentOnly(blockUuid, content)
}

/**
 * One sheet over a real `BlockStateManager` and in-memory repositories (the `BlockStateManagerTest` setup, not a mock).
 * The manager runs on `Dispatchers.Unconfined` so writes complete without a scheduler; the committer shares the
 * compose test dispatcher, which is the single context the host expects.
 */
@OptIn(DirectRepositoryWrite::class)
internal class SheetHarness(private val rule: ComposeContentTestRule) {
    val pageUuid = PageUuid("sheet-page")
    val uuid = BlockUuid("sheet-table-1")
    val c0 = "| Item | Qty |\n|---|---|\n| Apple | 3 |"
    private val now = Clock.System.now()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val memory = InMemoryBlockRepository()
    private val flaky = FlakyBlockRepository(memory)
    val repo: BlockRepository = flaky
    val manager: BlockStateManager
    val port: TableManagerPort
    val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    var session: TableSheetSession? = null
    val hooks = FakeBodyHooks { session }
    val closes = mutableListOf<SheetCloseReason>()
    var flushes = 0
    var parentKeys = 0
    var graphKey by mutableStateOf("graph-1")
    var open by mutableStateOf(true)
    var sheetEnabled by mutableStateOf(true)
    val recovered = RecoveredDraftStore()

    init {
        val pageRepo = InMemoryPageRepository()
        manager = BlockStateManager(repo, GraphLoader(NoFiles, pageRepo, repo), scope)
        runBlocking {
            pageRepo.savePage(Page(uuid = pageUuid, name = "Groceries", createdAt = now, updatedAt = now))
            repo.saveBlock(Block(uuid = uuid, pageUuid = pageUuid, content = c0, position = "a0", blockType = BlockType.Table, version = 1, createdAt = now, updatedAt = now))
            manager.observePage(pageUuid)
            manager.blocks.first { it.containsKey(pageUuid.value) }
        }
        port = BlockStateManagerTablePort(manager) { id -> repo.getBlockByUuid(id).first().getOrNull()?.content }
    }

    fun failContentWrites(fail: Boolean) {
        flaky.failContentWrites = fail
    }

    fun managerContent(): String = manager.blocks.value[pageUuid.value]!!.single { it.uuid == uuid }.content
    fun managerVersion(): Long = manager.blocks.value[pageUuid.value]!!.single { it.uuid == uuid }.version
    fun storedContent(): String? = runBlocking { repo.getBlockByUuid(uuid).first().getOrNull()?.content }
    fun feed(): Flow<Block?> = repo.getBlockByUuid(uuid).map { it.getOrNull() }

    /** The foreign writer: straight to the repository, no invalidation, as the real feed's external edit. */
    fun deleteBlock() = runBlocking { repo.deleteBlock(uuid, false) }

    fun foreignWrite(content: String) = runBlocking { repo.updateBlockContentOnly(uuid, content) }

    fun show(body: SheetBody? = null, readOnly: Boolean = false, wrapParent: Boolean = false, initialContent: String = c0) {
        rule.setContent {
            MaterialTheme {
                val ctx = rememberCoroutineScope().coroutineContext[ContinuationInterceptor]!!
                if (open) {
                    val content = @androidx.compose.runtime.Composable {
                        TableSheetHost(
                            blockUuid = uuid,
                            initialContent = initialContent,
                            title = "Groceries",
                            feed = feed(),
                            port = port,
                            context = ctx,
                            flushScope = flushScope,
                            flush = { flushes++ },
                            recovered = recovered,
                            sheetEnabled = sheetEnabled,
                            graphKey = graphKey,
                            startVersion = managerVersion(),
                            readOnly = readOnly,
                            onSessionReady = { s ->
                                session = s
                                s.bind(hooks)
                            },
                            onClose = { closes += it; open = false },
                            body = body ?: { s -> dev.stapler.stelekit.ui.components.sheet.PlaceholderSheetBody(s) },
                        )
                    }
                    if (wrapParent) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.androidxKey { parentKeys++ },
                        ) { content() }
                    } else {
                        content()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    fun commit(text: String, label: String = "edit"): Boolean {
        var ok = false
        rule.runOnUiThread { ok = session!!.commit(text, label) }
        rule.waitForIdle()
        return ok
    }

    fun close() {
        flushScope.cancel()
        scope.cancel()
    }
}

private fun androidx.compose.ui.Modifier.androidxKey(onKey: () -> Unit): androidx.compose.ui.Modifier =
    this.then(
        Modifier.onKeyEvent {
            if (it.type == KeyEventType.KeyDown) onKey()
            false
        },
    )
