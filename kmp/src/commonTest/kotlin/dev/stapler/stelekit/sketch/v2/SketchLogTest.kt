package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.AdoptionCounts
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualityCounts
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SheetOrigin
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.migration.SignalsReadoutModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 6.2.3, Task 6.2.3a: log lines carry ids, tokens and counts only. */
class SketchLogTest {
    private val lines = mutableListOf<String>()
    private val log = SketchLog(SketchLogSink { lines += it })

    @Test
    fun solveFailed_should_LogIdsAndNotElementText_When_SheetHoldsSecretText() {
        val sheetText = "Secret"
        log.solveFailed("Conflict", listOf("c1", "c2"))
        assertEquals(listOf("sketch.solve.failed kind=Conflict ids=c1,c2"), lines)
        assertFalse(lines.any { sheetText in it })
    }

    @Test
    fun anyField_should_BecomeQuestionMark_When_ValueIsNotAnIdToken() {
        log.importFailed("My Secret Diagram.excalidraw")
        log.persistFailed("")
        log.sheetRejected("x".repeat(SceneLimits.Default.maxLayerIdChars + 1))
        log.solveFailed("Conflict", listOf("c1", "label with spaces"))
        assertEquals(
            listOf(
                "sketch.import.failed reason=?",
                "sketch.persist.failed reason=?",
                "sketch.sheet.rejected reason=?",
                "sketch.solve.failed kind=Conflict ids=c1,?",
            ),
            lines,
        )
    }

    @Test
    fun solveFailed_should_SummariseTheTail_When_MoreIdsThanTheConstraintCap() {
        val cap = SceneLimits.Default.maxConstraints
        log.solveFailed("Conflict", List(cap + 3) { "c$it" })
        assertTrue(lines.single().endsWith(",+3"), lines.single())
    }

    @Test
    fun scriptedRun_should_EmitEachLineExactlyOnce_When_EveryTriggerFires() {
        log.toolActivated("rectangle")
        log.solveFailed("Conflict", listOf("c1"))
        log.importFailed("NotJson")
        log.importSummary(imported = 4, skipped = 1)
        log.migrateOutcome("Converted")
        log.persistFailed("IoError")
        log.sheetRejected("TooLarge")
        log.saveConflict("s1")
        val names = lines.map { it.substringBefore(' ') }
        assertEquals(
            listOf(
                "sketch.tool.activated", "sketch.solve.failed", "sketch.import.failed", "sketch.import.summary",
                "sketch.migrate.outcome", "sketch.persist.failed", "sketch.sheet.rejected", "sketch.save.conflict",
            ),
            names,
        )
        assertEquals("sketch.import.summary imported=4 skipped=1", lines[3])
    }

    @Test
    fun noticeAndOnboarding_should_LogTheirFixedIdOnly_AndTurnFreeTextIntoAQuestionMark() {
        log.noticeShown("LayerLocked")
        log.noticeShown("Locked layer: Secret Walls.")
        log.onboardingShown("CoachMark")
        assertEquals(
            listOf("sketch.notice.shown key=LayerLocked", "sketch.notice.shown key=?", "sketch.onboarding.shown item=CoachMark"),
            lines,
        )
    }

    @Test
    fun toolActivated_should_LogOnlyOnChange_When_SameToolIsReselected() {
        repeat(1_000) { log.toolActivated("select") }
        log.toolActivated("hand")
        assertEquals(listOf("sketch.tool.activated tool=select", "sketch.tool.activated tool=hand"), lines)
    }

    @Test
    fun counts_should_NeverBeNegative_When_CallerPassesNegative() {
        log.importSummary(imported = -2, skipped = 0)
        assertEquals("sketch.import.summary imported=0 skipped=0", lines.single())
    }

    private class MapSettings : Settings {
        val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    private val settings = MapSettings()
    private val signals = QualitySignals(settings)
    private val signalLog = SketchLog(SketchLogSink { lines += it }, signals = signals)

    @Test
    fun adoption_should_EmitCountsOnlyAndNeverTransmit_When_AuthoredSheetCreatedOrExcalidrawImported() {
        signals.sheetCreated("Secret Diagram", SheetOrigin.Authored)
        repeat(QualitySignals.AUTHORED_MIN_DRAWN) { signals.elementDrawn("Secret Diagram") }
        signals.excalidrawImported()
        signals.endSession(signalLog)
        assertEquals(1, lines.count { it == "sketch.adoption authored=1 imported=1" }, lines.toString())
        assertFalse(lines.any { "Secret" in it })
    }

    @Test
    fun adoption_should_CountOnlySheetsWithAtLeast3DrawnElements_AndReportImportsAndConversionsSeparately() {
        signals.sheetCreated("two-drawn", SheetOrigin.Authored)
        signals.sheetCreated("three-drawn", SheetOrigin.Authored)
        signals.sheetCreated("converted", SheetOrigin.Converted)
        repeat(2) { signals.elementDrawn("two-drawn") }
        repeat(3) { signals.elementDrawn("three-drawn") }
        repeat(5) { signals.elementDrawn("converted") }
        signals.excalidrawImported()
        signals.endSession(signalLog)
        assertEquals("sketch.adoption authored=1 imported=1", lines.first { it.startsWith("sketch.adoption") })
        assertEquals(AdoptionCounts(authored = 1, imported = 1, converted = 1), signals.adoption())
    }

    @Test
    fun adoption_should_CountASheetOnceAndAcrossSessions_When_MoreElementsAreDrawnLater() {
        signals.sheetCreated("s1", SheetOrigin.Authored)
        signals.elementDrawn("s1")
        val later = QualitySignals(settings)
        repeat(10) { later.elementDrawn("s1") }
        assertEquals(1L, later.adoption().authored)
    }

    @Test
    fun adoption_should_NotCountClassicSketchesInExistingProjects_UnderNewProjectsOnly_AndLabelTheResult() {
        // A classic sketch never reaches the tracker: only v2 sheets call sheetCreated.
        val classicElementsDrawn = 7
        assertEquals(AdoptionCounts(), signals.adoption(), "$classicElementsDrawn classic elements counted nothing")
        val model = SignalsReadoutModel(signals.adoption(), signals.counts(), V2Mode.NewProjectsOnly)
        assertTrue(model.modeLabel.orEmpty().contains("existing projects are classic"))
        assertEquals(null, SignalsReadoutModel(signals.adoption(), signals.counts(), V2Mode.On).modeLabel)
    }

    @Test
    fun quality_should_EmitOnceAtSessionEndWithCountsOnly_When_ScriptedSessionRuns() {
        signals.ingestCrashRecord("crash-1")
        signals.beginSession()
        repeat(197) { signals.recordSolve() }
        repeat(3) { signals.recordNoConvergence() }
        signalLog.saveConflict("s1")
        signals.endSession(signalLog)
        assertEquals(1, lines.count { it == "sketch.quality v2Sessions=1 crashes=1 solves=200 noConvergence=3 conflicts=1" }, lines.toString())
        assertEquals(QualityCounts(1, 1, 200, 3, 1), signals.counts())
    }

    @Test
    fun crashRecord_should_BeCountedOnce_AndMarkThePreviousSession() {
        signals.beginSession()
        signals.ingestCrashRecord("c1")
        signals.ingestCrashRecord("c1")
        signals.ingestCrashRecord(null)
        signals.beginSession()
        assertEquals(1L, signals.counts().crashes)
        assertEquals(listOf(true, false), signals.recentSessions())
    }

    @Test
    fun recentWindows_should_KeepTheLast20SessionsAnd100Solves() {
        repeat(QualitySignals.RECENT_SESSIONS + 5) { signals.beginSession() }
        repeat(QualitySignals.RECENT_SOLVES + 5) { signals.recordSolve() }
        signals.recordNoConvergence()
        assertEquals(QualitySignals.RECENT_SESSIONS, signals.recentSessions().size)
        assertEquals(QualitySignals.RECENT_SOLVES, signals.recentSolves().size)
        assertTrue(signals.recentSolves().last())
        assertEquals((QualitySignals.RECENT_SESSIONS + 5).toLong(), signals.counts().v2Sessions, "the totals are not windowed")
    }

    @Test
    fun counters_should_SurviveANewInstanceOverTheSameSettings() {
        signals.beginSession()
        signalLog.saveConflict("s1")
        assertEquals(1L, QualitySignals(settings).counts().v2Sessions)
        assertEquals(1L, QualitySignals(settings).counts().conflicts)
    }

    @Test
    fun readout_should_ShowAuthoredAndImportedOnSeparateLines() {
        val model = SignalsReadoutModel(AdoptionCounts(authored = 2, imported = 3), QualityCounts(), V2Mode.Off)
        assertEquals(listOf("Sketches drawn in the new editor: 2", "Excalidraw files imported: 3"), model.adoptionLines)
        assertEquals(5, model.qualityLines.size)
    }

    @Test
    fun counters_should_EmitNothing_UntilTheSessionEnds() {
        lines.clear()
        repeat(1_000) { signals.recordSolve() }
        assertEquals(emptyList(), lines, "counters are silent until the session ends")
    }
}
