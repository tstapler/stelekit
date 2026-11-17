// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.ModeDisabled
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.runtime.session.AckLevel
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.ui.sketch.v2.migration.BrowserStorageRow
import dev.stapler.stelekit.ui.sketch.v2.migration.EnableVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.GraphLifetime
import dev.stapler.stelekit.ui.sketch.v2.migration.ModeChange
import dev.stapler.stelekit.ui.sketch.v2.migration.ModeChangePlanner
import dev.stapler.stelekit.ui.sketch.v2.migration.ModeControlModel
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.6h (UX-76, UX-90, UX-129, UX-137; D-88, D-97, D-111): the mode control, its reasons and the confirmation recurrence. */
class FlagModeSettingsTest {
    private fun caps(ready: Boolean = true, offered: Boolean = true, storage: V2Storage = V2Storage.Available) =
        V2Capabilities(ready, offered, storage)

    private val open = caps()

    private fun plan(current: V2Mode, target: V2Mode, level: AckLevel, capabilities: V2Capabilities = open) =
        ModeChangePlanner.plan(current, target, level, capabilities)

    @Test
    fun theRadioLines_should_BeTheUxStrings_AndTheGroupIsNamedNewEditor() {
        val model = ModeControlModel.build(V2Mode.Off, open)
        assertEquals("New editor", model.groupName)
        assertEquals("New editor (this graph, on this device)", model.heading)
        assertEquals(
            "New projects use the new editor, and so do projects that already hold only new-editor sketches. " +
                "Other existing projects stay classic, and so does any sketch you add to one.",
            model.option(V2Mode.NewProjectsOnly).line,
        )
        assertTrue("open read-only" in model.option(V2Mode.Off).line)
        assertTrue("each classic sketch gets an Upgrade offer" in model.option(V2Mode.On).line)
        assertEquals(setOf(V2Mode.Off), model.options.filter { it.selected }.map { it.mode }.toSet())
        model.options.forEach { assertTrue(it.line in it.accessibleDescription) }
    }

    @Test
    fun capped_should_DisableTheOptionsWithTheBuildReason_NeverHideThem() {
        val readyFalse = ModeControlModel.build(V2Mode.Off, caps(ready = false))
        assertEquals(3, readyFalse.options.size)
        assertTrue(readyFalse.option(V2Mode.Off).enabled)
        assertEquals("Not available in this build yet", readyFalse.option(V2Mode.NewProjectsOnly).disabledReason)
        assertEquals("Not available in this build yet", readyFalse.option(V2Mode.On).disabledReason)
        val offeredFalse = ModeControlModel.build(V2Mode.Off, caps(offered = false))
        assertTrue(offeredFalse.option(V2Mode.NewProjectsOnly).enabled)
        assertEquals("Not available in this build yet", offeredFalse.option(V2Mode.On).disabledReason)
        assertTrue("Not available in this build yet" in offeredFalse.option(V2Mode.On).accessibleDescription)
    }

    @Test
    fun theReasonPrecedence_should_PutThePlatformBeforeTheBuild_AndDisableTheRestOfSettings() {
        val both = ModeControlModel.build(V2Mode.Off, caps(ready = false, storage = V2Storage.PlatformUnsupported))
        assertEquals("The new editor can't save sketches on iOS yet.", both.option(V2Mode.On).disabledReason)
        assertEquals("The new editor can't save sketches on iOS yet.", both.restDisabledReason)
        assertEquals("New sketches are classic.", both.option(V2Mode.Off).line, "no clause about read-only new-editor sketches")
        val browser = ModeControlModel.build(V2Mode.Off, caps(storage = V2Storage.Browser(StorageReason.NoOpfs)))
        assertTrue(browser.option(V2Mode.NewProjectsOnly).disabledReason!!.startsWith("This browser window can't keep"))
        assertNull(ModeControlModel.build(V2Mode.Off, open).restDisabledReason)
    }

    @Test
    fun checking_should_DisableTheTwoOnOptionsWithTheCheckingReason_AndKeepOffEnabled() {
        val model = ModeControlModel.build(V2Mode.Off, caps(storage = V2Storage.Checking))
        assertTrue(model.option(V2Mode.Off).enabled)
        assertTrue(model.option(V2Mode.On).disabledReason!!.startsWith("Checking whether this browser"))
        assertEquals(ModeChange.Disabled(ModeDisabled.Checking), plan(V2Mode.Off, V2Mode.On, AckLevel.None, caps(storage = V2Storage.Checking)))
    }

    @Test
    fun aTemporaryGraph_should_ShowTheSessionOnlyLine() {
        assertEquals(SketchMigrationStrings.Mode.TEMPORARY_GRAPH, ModeControlModel.build(V2Mode.Off, open, GraphLifetime.Temporary).temporaryGraphLine)
        assertNull(ModeControlModel.build(V2Mode.Off, open).temporaryGraphLine)
    }

    @Test
    fun theConfirmationLevel_should_FollowTheRecurrenceRules() {
        // level none: either On mode asks first.
        assertEquals(ModeChange.Confirm(EnableVariant.NewProjectsOnly, V2Mode.NewProjectsOnly), plan(V2Mode.Off, V2Mode.NewProjectsOnly, AckLevel.None))
        // confirmed NewProjectsOnly: On is above the level and asks again; narrowing and Off never ask.
        assertEquals(ModeChange.Confirm(EnableVariant.On, V2Mode.On), plan(V2Mode.NewProjectsOnly, V2Mode.On, AckLevel.NewProjectsOnly))
        assertEquals(ModeChange.Store(V2Mode.NewProjectsOnly), plan(V2Mode.On, V2Mode.NewProjectsOnly, AckLevel.NewProjectsOnly))
        assertEquals(ModeChange.Store(V2Mode.Off), plan(V2Mode.On, V2Mode.Off, AckLevel.None))
        // level On: returning from Off to either mode stores without a dialog.
        assertEquals(ModeChange.Store(V2Mode.On), plan(V2Mode.Off, V2Mode.On, AckLevel.On))
        assertEquals(ModeChange.Store(V2Mode.NewProjectsOnly), plan(V2Mode.Off, V2Mode.NewProjectsOnly, AckLevel.On))
        // the already-selected mode is a no-op.
        assertEquals(ModeChange.NoOp, plan(V2Mode.On, V2Mode.On, AckLevel.None))
    }

    @Test
    fun theLevel_should_OnlyRise_AndCancelLeavesItUnchanged() {
        val acks = ProjectAckStore(MapSettings()) { "g1" }
        assertEquals(AckLevel.None, acks.level)
        acks.raiseLevel(V2Mode.NewProjectsOnly)
        assertEquals(AckLevel.NewProjectsOnly, acks.level)
        acks.raiseLevel(V2Mode.Off)
        assertEquals(AckLevel.NewProjectsOnly, acks.level, "Off never lowers it")
        acks.raiseLevel(V2Mode.On)
        assertEquals(AckLevel.On, acks.level)
        acks.raiseLevel(V2Mode.NewProjectsOnly)
        assertEquals(AckLevel.On, acks.level)
    }

    @Test
    fun readOnlyUseNew_should_AlwaysAsk_AtAnyLevel_AndBeAbsentWhereTheModeCannotBeTurnedOn() {
        val confirm = assertNotNull(ModeChangePlanner.readOnlyUseNew(open))
        assertEquals(EnableVariant.ReadOnlyUseNew, confirm.variant)
        assertEquals(V2Mode.NewProjectsOnly, confirm.mode)
        assertNull(ModeChangePlanner.readOnlyUseNew(caps(ready = false)), "ConversionReady false: no primary action")
        assertNull(ModeChangePlanner.readOnlyUseNew(caps(storage = V2Storage.PlatformUnsupported)))
    }

    @Test
    fun tryTheNewEditor_should_BePresentOnlyInOff_OpenTheModeGroupAndStoreNothing() {
        assertTrue(ModeChangePlanner.tryTheNewEditorVisible(V2Mode.Off, open))
        assertFalse(ModeChangePlanner.tryTheNewEditorVisible(V2Mode.NewProjectsOnly, open))
        assertFalse(ModeChangePlanner.tryTheNewEditorVisible(V2Mode.On, open))
        assertFalse(ModeChangePlanner.tryTheNewEditorVisible(V2Mode.Off, caps(ready = false)), "a capped build has nothing to try")
        assertFalse(ModeChangePlanner.tryTheNewEditorVisible(V2Mode.Off, caps(storage = V2Storage.NoStore)))
    }

    @Test
    fun theBrowserStorageRow_should_NeverBeSilentAboutARefusal() {
        assertEquals(BrowserStorageRow("Kept", null, null), BrowserStorageRow.of(PersistenceState.Granted))
        assertEquals(SketchMigrationStrings.Mode.ASK_BROWSER, BrowserStorageRow.of(PersistenceState.NotRequested).actionLabel)
        assertEquals("Asking the browser...", BrowserStorageRow.of(PersistenceState.NotRequested, asking = true).statusLine)
        val refused = BrowserStorageRow.of(PersistenceState.Denied)
        assertTrue(refused.statusLine!!.startsWith("The browser said no."))
        assertEquals(SketchMigrationStrings.Mode.ASK_BROWSER, refused.actionLabel)
        val unsupported = BrowserStorageRow.of(PersistenceState.Unsupported)
        assertNull(unsupported.actionLabel)
        assertEquals("This browser can't promise to keep site storage.", unsupported.statusLine)
    }
}
