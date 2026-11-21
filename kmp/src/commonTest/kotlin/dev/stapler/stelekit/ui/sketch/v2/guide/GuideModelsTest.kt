// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.KeyAction
import dev.stapler.stelekit.ui.sketch.v2.KeyContext
import dev.stapler.stelekit.ui.sketch.v2.KeyDispatcher
import dev.stapler.stelekit.ui.sketch.v2.KeyOutcome
import dev.stapler.stelekit.ui.sketch.v2.KeyPress
import dev.stapler.stelekit.ui.sketch.v2.KeymapEnv
import dev.stapler.stelekit.ui.sketch.v2.PanelTab
import dev.stapler.stelekit.ui.sketch.v2.PlaceRect
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGuideStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared by the guide tests: a Settings that keeps its values in a map. */
class MapSettings : Settings {
    private val map = HashMap<String, Any>()
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) { map[key] = value }
    override fun containsKey(key: String) = key in map
}

class GuideModelsTest {
    private fun controller() = TourController(GuideContent.registry(), TourProgress(MapSettings()))

    @Test
    fun presentation_should_BeNull_WhenNoTourShows() {
        assertNull(TourPresentation.of(controller()))
    }

    @Test
    fun presentation_should_DescribeTheCurrentStep_AndTrackFirstAndLast() {
        val c = controller()
        c.replay(GuideContent.POINT_DRAG)
        val first = TourPresentation.of(c)!!
        assertEquals(SketchGuideStrings.indicator(1, 2), first.indicator)
        assertEquals(c.step!!.title, first.title)
        assertEquals("${first.indicator}. ${first.title}. ${first.body}", first.announcement)
        assertFalse(first.canGoBack)
        assertFalse(first.last)
        c.next()
        val second = TourPresentation.of(c)!!
        assertTrue(second.canGoBack)
        assertTrue(second.last)
        assertEquals(c.active!!.title, second.tourTitle)
    }

    @Test
    fun relative_should_ShiftTheAnchorIntoTheOverlaySpace_AndKeepNull() {
        val origin = PlaceRect(10f, 20f, 110f, 220f)
        assertEquals(PlaceRect(5f, 5f, 15f, 25f), TourPlacement.relative(PlaceRect(15f, 25f, 25f, 45f), origin))
        assertNull(TourPlacement.relative(null, origin))
    }

    @Test
    fun spot_should_CentreWithoutAnAnchor_AndStayInsideTheContainerWithOne() {
        val container = PlaceRect(0f, 0f, 400f, 800f)
        assertEquals(150f to 350f, TourPlacement.spot(null, 100f, 100f, container, LayoutDirection.Ltr))
        val (x, y) = TourPlacement.spot(PlaceRect(0f, 700f, 400f, 800f), 100f, 100f, container, LayoutDirection.Ltr)
        assertTrue(x in 0f..300f && y in 0f..700f)
    }

    @Test
    fun ofPanel_should_NameOnlyTheConstraintsAndTechnicalTabs() {
        assertEquals(GuideFeatures.CONSTRAINTS, GuideTriggers.ofPanel(PanelTab.Constraints))
        assertEquals(GuideFeatures.TECHNICAL_TAB, GuideTriggers.ofPanel(PanelTab.Technical))
        for (tab in PanelTab.entries - setOf(PanelTab.Constraints, PanelTab.Technical)) assertNull(GuideTriggers.ofPanel(tab), tab.name)
        assertNull(GuideTriggers.ofPanel(null))
    }

    private class Recorder : GuideEffects {
        val calls = mutableListOf<String>()
        override fun undo() { calls += "undo" }
        override fun redo() { calls += "redo" }
        override fun delete() { calls += "delete" }
        override fun constrain() { calls += "constrain" }
        override fun shortcuts() { calls += "shortcuts" }
        override fun goToElement() { calls += "goto" }
        override fun fit() { calls += "fit" }
        override fun grid() { calls += "grid" }
        override fun snap() { calls += "snap" }
        override fun export() { calls += "export" }
        override fun import() { calls += "import" }
        override fun useTool(tool: ToolId) { calls += "tool:${tool.name}" }
    }

    @Test
    fun run_should_CallTheMatchingEffect_ForEveryOfferedAction() {
        val every = GuideRunner.offered(constraints = true, export = true, import = true)
        val fx = Recorder()
        for (name in every) GuideRunner.run(SearchAction.Run(name), fx)
        assertEquals(listOf("undo", "redo", "delete", "shortcuts", "goto", "fit", "grid", "snap", "constrain", "export", "import"), fx.calls)
    }

    @Test
    fun run_should_UseATool_AndDoNothingForATopic() {
        val fx = Recorder()
        GuideRunner.run(SearchAction.UseTool(ToolId.Arc), fx)
        GuideRunner.run(SearchAction.OpenTopic("keyboard"), fx)
        assertEquals(listOf("tool:Arc"), fx.calls)
    }

    @Test
    fun offered_should_LeaveOutWhatTheHostCannotDo() {
        val bare = GuideRunner.offered(constraints = false, export = false, import = false)
        assertFalse(GuideActions.CONSTRAIN in bare || GuideActions.EXPORT in bare || GuideActions.IMPORT in bare)
        assertTrue(GuideActions.UNDO in bare)
    }

    @Test
    fun ctrlSlash_should_OpenHelpSearch_OnEveryPlatform() {
        for (env in listOf(KeymapEnv(isMac = false, isWeb = false), KeymapEnv(isMac = false, isWeb = true))) {
            val ctx = KeyContext(env, singleKeyEnabled = true, canvasFocused = true, textInputOrDialogFocused = false, hasSelection = false, handToolActive = false)
            val outcome = KeyDispatcher.dispatch(KeyPress("/", ctrl = true, meta = false, shift = false, alt = false), ctx)
            assertEquals(KeyAction.OpenHelpSearch, assertIs<KeyOutcome.Handled>(outcome).action)
        }
        val mac = KeyContext(KeymapEnv(isMac = true, isWeb = false), singleKeyEnabled = true, canvasFocused = true, textInputOrDialogFocused = false, hasSelection = false, handToolActive = false)
        assertEquals(KeyAction.OpenHelpSearch, assertIs<KeyOutcome.Handled>(KeyDispatcher.dispatch(KeyPress("/", ctrl = false, meta = true, shift = false, alt = false), mac)).action)
    }
}
