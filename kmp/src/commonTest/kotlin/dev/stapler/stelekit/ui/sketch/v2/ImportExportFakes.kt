// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.platform.PickedImportFile
import dev.stapler.stelekit.platform.SketchFilePort
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement

/** A sheet over a real [HistoryV2], so "one undo step" and "undo import" in the tests are the engine's own. */
internal class FakeImportSheetPort(var history: HistoryV2 = HistoryV2.of()) : ImportSheetPort {
    val created = mutableListOf<SketchDocumentV2>()
    var selected: Set<ElementId> = emptySet()
    var failCreate = false

    override fun document(): SketchDocumentV2 = history.present

    override fun viewportCentre(): Pair<Double, Double> = CENTRE_X to CENTRE_Y

    override fun commit(command: Command): Either<CommandError, Applied> = either {
        val committed = Transaction.begin(history.present).apply(command).bind().commit().bind()
        history = history.commit(committed).bind()
        Applied(history.present, committed.changedIds)
    }

    override fun undo(): Boolean {
        if (!history.canUndo) return false
        history = history.undo()
        return true
    }

    override fun select(ids: Set<ElementId>) {
        selected = ids
    }

    override suspend fun createSheet(document: SketchDocumentV2): Either<String, Unit> =
        if (failCreate) "refused".left() else created.add(document).let { Unit.right() }

    companion object {
        const val CENTRE_X = 400.0
        const val CENTRE_Y = 300.0

        /** A sheet that already holds [count] rectangles, as one history-free starting document. */
        fun holding(count: Int, rect: (Int) -> RectElement): FakeImportSheetPort {
            val doc = Transaction.begin(SketchDocumentV2()).apply(ImportScene((1..count).map(rect))).fold({ error("$it") }, { it.doc })
            return FakeImportSheetPort(HistoryV2.of(doc))
        }
    }
}

/** A file port that answers from queues; [pickCalls] proves the picker was or was not opened. */
internal class FakeSketchFiles(override val canPick: Boolean = true) : SketchFilePort {
    val picks = ArrayDeque<PickedImportFile?>()
    var pickCalls = 0
    val saved = mutableListOf<String>()
    var outcome: () -> SketchSaveOutcome = { SketchSaveOutcome.Cancelled }

    override suspend fun pickImportFile(title: String, extensions: List<String>): PickedImportFile? {
        pickCalls += 1
        return picks.removeFirstOrNull()
    }

    override suspend fun save(suggestedName: String, mimeType: String, bytes: ByteArray): SketchSaveOutcome {
        saved += suggestedName
        return outcome()
    }
}
