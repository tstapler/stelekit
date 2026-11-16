// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.telemetry

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/** Receives one finished `sketch.*` line. */
fun interface SketchLogSink {
    fun emit(line: String)
}

/**
 * The only way the sketch engine writes a `sketch.*` log line (plan Story 6.2.3). Every parameter is an id, a
 * token or a count, never element text: a value that is not `[A-Za-z0-9_.-]` up to the id length cap becomes
 * `?`, so a label, a file name or any user text passed by mistake cannot reach the log. There is deliberately no
 * function for pointer or drag events: pointer-move paths must not log (UX-70, UX-73).
 * `sketch.adoption` and `sketch.quality` (counters) are added by Task 6.2.3b.
 */
class SketchLog(
    private val sink: SketchLogSink = LoggerSink,
    private val limits: SceneLimits = SceneLimits.Default,
) {
    private var lastTool: String? = null

    /** Emitted when the active tool changes; re-selecting the active tool logs nothing. */
    fun toolActivated(toolId: String) {
        val tool = clean(toolId)
        if (tool == lastTool) return
        lastTool = tool
        emit("sketch.tool.activated", "tool" to tool)
    }

    fun solveFailed(kind: String, constraintIds: List<String>) =
        emit("sketch.solve.failed", "kind" to clean(kind), "ids" to ids(constraintIds))

    fun importFailed(reason: String) = emit("sketch.import.failed", "reason" to clean(reason))

    fun importSummary(imported: Int, skipped: Int) =
        emit("sketch.import.summary", "imported" to count(imported), "skipped" to count(skipped))

    fun migrateOutcome(outcome: String) = emit("sketch.migrate.outcome", "outcome" to clean(outcome))

    fun persistFailed(reason: String) = emit("sketch.persist.failed", "reason" to clean(reason))

    fun sheetRejected(reason: String) = emit("sketch.sheet.rejected", "reason" to clean(reason))

    fun saveConflict(sheetId: String) = emit("sketch.save.conflict", "sheet" to clean(sheetId))

    /** A notice or banner row reached the screen; [key] is its fixed id (`AddedConstraint`), never its text. */
    fun noticeShown(key: String) = emit("sketch.notice.shown", "key" to clean(key))

    /** An onboarding item became visible; [item] is the `OnboardingItem` name. */
    fun onboardingShown(item: String) = emit("sketch.onboarding.shown", "item" to clean(item))

    private fun emit(name: String, vararg fields: Pair<String, String>) {
        sink.emit(buildString {
            append(name)
            for ((key, value) in fields) append(' ').append(key).append('=').append(value)
        })
    }

    private fun ids(values: List<String>): String {
        if (values.isEmpty()) return "-"
        val shown = values.take(limits.maxConstraints).joinToString(",") { clean(it) }
        val omitted = values.size - limits.maxConstraints
        return if (omitted > 0) "$shown,+$omitted" else shown
    }

    private fun count(value: Int): String = value.coerceAtLeast(0).toString()

    private fun clean(value: String): String =
        if (value.isNotEmpty() && value.length <= limits.maxLayerIdChars && value.all(::isTokenChar)) value else "?"

    private fun isTokenChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == '-'

    private object LoggerSink : SketchLogSink {
        private val logger = Logger("sketch")
        override fun emit(line: String) = logger.info(line)
    }
}
