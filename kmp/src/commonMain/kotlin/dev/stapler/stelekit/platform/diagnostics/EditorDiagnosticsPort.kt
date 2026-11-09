// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.diagnostics

/** Read-only view of the editor the sampler needs; androidMain must not reach into the ViewModel. */
data class EditorDiagnosticsSnapshot(
    val tool: String,
    val screen: String,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val undoDepth: Int,
)

/** Implemented by the editor ViewModel: a cheap, thread-safe snapshot read. */
fun interface EditorDiagnosticsSource {
    fun snapshot(): EditorDiagnosticsSnapshot
}

/**
 * Implemented by the Android sampler. The screen starts it while the editor is composed and stops it
 * on dispose; implementations own their scope and must never throw out of [start] / [stop].
 */
interface EditorDiagnosticsSink {
    fun start(source: EditorDiagnosticsSource)
    fun stop()
}

/** One persisted line per tick. Pure data + formatting so it is testable without Android. */
data class DiagnosticsSample(
    val cpuMsDelta: Long,
    val topThreadGroups: List<ThreadCpuShare>,
    val foreground: Boolean,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val coilCacheKb: Long?,
    val editor: EditorDiagnosticsSnapshot,
) {
    fun format(): String = buildString {
        append(SAMPLE_PREFIX)
        append(" cpuMsDelta=").append(cpuMsDelta)
        append(" threads=").append(topThreadGroups.joinToString(",") { "${it.group}:${it.ticks}" })
        append(" foreground=").append(foreground)
        append(" heapMb=").append(heapUsedMb).append('/').append(heapMaxMb)
        append(" coilCacheKb=").append(coilCacheKb ?: "n/a")
        append(" undoDepth=").append(editor.undoDepth)
        append(" screen=").append(editor.screen)
        append(" tool=").append(editor.tool)
        append(" image=").append(editor.imageWidthPx).append('x').append(editor.imageHeightPx)
    }

    companion object {
        const val SAMPLE_PREFIX = "DiagnosticsSample"
        const val STOPPED_LINE = "DiagnosticsSample stopped"
        const val TICK_INTERVAL_MS = 10_000L
    }
}
