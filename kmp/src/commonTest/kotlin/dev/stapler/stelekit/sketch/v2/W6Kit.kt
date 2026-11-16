// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource

/** Shared builders for the wave-6 tests: elements, a document, applying commands and a deterministic id source. */
internal object W6Kit {
    fun u(v: Int) = SceneUnit.ofInt(v)

    fun rect(id: String, x: Int, y: Int, w: Int = 10, h: Int = 10, layer: LayerId = LayerId.Default) =
        RectElement(ElementBase(ElementId(id), layerId = layer), u(x), u(y), u(w), u(h))

    fun circle(id: String, cx: Int, cy: Int, r: Int = 5) = EllipseElement(ElementBase(ElementId(id)), u(cx), u(cy), u(r), u(r))

    fun line(id: String, x0: Int, y0: Int, x1: Int, y1: Int) =
        LineElement(ElementBase(ElementId(id)), listOf(ScenePoint(u(x0), u(y0)), ScenePoint(u(x1), u(y1))))

    /** Applies [commands] in order, each as its own transaction; returns the document. Fails the test on a refusal. */
    fun run(doc: SketchDocumentV2, vararg commands: Command): SketchDocumentV2 =
        commands.fold(doc) { d, c -> step(d, c) }

    fun step(doc: SketchDocumentV2, command: Command): SketchDocumentV2 =
        Transaction.begin(doc).apply(command).fold({ error("refused: $it") }, { it.doc })

    fun <L, R> Either<L, R>.get(): R = fold({ error("unexpected Left: $it") }, { it })

    /** One history step per command, so undo steps can be counted. */
    fun history(vararg commands: Command): HistoryV2 {
        var h = HistoryV2.of()
        for (c in commands) {
            val committed = Transaction.begin(h.present).apply(c).get().commit().get()
            h = h.commit(committed).get()
        }
        return h
    }

    class SeqIds(private val prefix: String = "n") : ElementIdSource {
        private var n = 0
        override fun next(): ElementId = ElementId("$prefix${++n}")
    }
}
