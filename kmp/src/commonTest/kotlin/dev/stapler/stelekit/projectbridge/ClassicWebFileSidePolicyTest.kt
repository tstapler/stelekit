// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.jobs.schedule.MapSettings
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.platform.V2StorageStatus
import dev.stapler.stelekit.sketch.v2.ScriptedSidecarStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassicWebFileSidePolicyTest {
    private val verdicts = WebFileSideVerdict.entries
    private val settings = ClassicFileSideSetting.entries
    private val kinds = SheetFileKind.entries

    @Test
    fun everyRowOfTheRequirementsBlock_should_Hold() {
        val rows = listOf(
            Triple(WebFileSideVerdict.Available, ClassicFileSideSetting.On, SheetFileKind.Classic) to true,
            Triple(WebFileSideVerdict.Available, ClassicFileSideSetting.On, SheetFileKind.V2) to true,
            Triple(WebFileSideVerdict.Available, ClassicFileSideSetting.Off, SheetFileKind.Classic) to false,
            Triple(WebFileSideVerdict.Available, ClassicFileSideSetting.Off, SheetFileKind.V2) to true,
            Triple(WebFileSideVerdict.Unavailable, ClassicFileSideSetting.On, SheetFileKind.Classic) to false,
            Triple(WebFileSideVerdict.Unavailable, ClassicFileSideSetting.On, SheetFileKind.V2) to false,
            Triple(WebFileSideVerdict.Checking, ClassicFileSideSetting.On, SheetFileKind.Classic) to false,
            Triple(WebFileSideVerdict.Checking, ClassicFileSideSetting.Off, SheetFileKind.V2) to false,
        )
        for ((input, expected) in rows) {
            assertEquals(expected, ClassicWebFileSidePolicy.fileSideRuns(input.first, input.second, input.third), "$input")
        }
    }

    @Test
    fun verdictUnavailable_should_BeOffWhateverTheSetting() {
        for (setting in settings) for (kind in kinds) {
            assertFalse(ClassicWebFileSidePolicy.fileSideRuns(WebFileSideVerdict.Unavailable, setting, kind))
            assertFalse(ClassicWebFileSidePolicy.fileSideRuns(WebFileSideVerdict.Checking, setting, kind))
        }
        assertTrue(verdicts.size == 3)
    }

    @Test
    fun defaultSetting_should_BeOff() {
        assertEquals(ClassicFileSideSetting.Off, ProjectBridgeSettings(MapSettings()).webClassicFileSide())
        assertEquals(ClassicFileSideSetting.Off, ClassicFileSideSetting.parse("garbage"))
    }

    @Test
    fun theSetting_should_BeReadFromItsOwnKey() {
        val store = MapSettings()
        store.putString("sketch.web.classicFileSide", "On")
        assertEquals(ClassicFileSideSetting.On, ProjectBridgeSettings(store).webClassicFileSide())
    }

    @Test
    fun v2Sheet_should_BeReconciledRegardlessOfTheSetting() {
        for (setting in settings) {
            assertTrue(ClassicWebFileSidePolicy.fileSideRuns(WebFileSideVerdict.Available, setting, SheetFileKind.V2))
        }
    }

    @Test
    fun classicSheetOfAMixedProject_should_StayDatabaseOnlyUnderOff() {
        assertEquals(SheetFileKind.Classic, ClassicWebFileSidePolicy.kindOfSheetPath("sheets/a.json"))
        assertEquals(SheetFileKind.V2, ClassicWebFileSidePolicy.kindOfSheetPath("sheets/b.v2.json"))
        assertFalse(ClassicWebFileSidePolicy.fileSideRuns(WebFileSideVerdict.Available, ClassicFileSideSetting.Off, SheetFileKind.Classic))
        assertEquals(null, ClassicWebFileSidePolicy.kindOfSheetPath("project-model.json"))
    }

    @Test
    fun verdictOf_should_FollowTheStorageStatus() {
        assertEquals(WebFileSideVerdict.Available, WebFileSideVerdict.of(V2StorageStatus.Ready(false, false)))
        assertEquals(WebFileSideVerdict.Checking, WebFileSideVerdict.of(V2StorageStatus.Checking))
        assertEquals(WebFileSideVerdict.Unavailable, WebFileSideVerdict.of(V2StorageStatus.Unavailable(StorageReason.NoOpfs)))
    }

    @Test
    fun gatedStore_should_KeepAClassicOnlyProjectDeviceLocalUnderOff() = runTest {
        val inner = ScriptedSidecarStore()
        inner.files["project-model.json"] = "{}".encodeToByteArray()
        inner.files["sheets/a.json"] = "{}".encodeToByteArray()
        val gated = ClassicGatedSidecarStore(inner, gateActive = true) { ClassicFileSideSetting.Off }

        assertEquals(SidecarRead.Unavailable, gated.read("p"))
        assertEquals(SidecarWriteOutcome.Unavailable, gated.write("p", mapOf("sheets/c.json" to ByteArray(1))))
        assertEquals(SidecarWriteOutcome.Unavailable, gated.writeNow("p", mapOf("sheets/c.json" to ByteArray(1))))
        assertFalse(inner.files.containsKey("sheets/c.json"))
    }

    @Test
    fun gatedStore_should_PassAV2ProjectAndEveryProjectUnderOnOrOnDesktop() = runTest {
        val inner = ScriptedSidecarStore()
        inner.files["sheets/b.v2.json"] = "{}".encodeToByteArray()
        assertTrue(ClassicGatedSidecarStore(inner, true) { ClassicFileSideSetting.Off }.read("p") is SidecarRead.Present)

        val classic = ScriptedSidecarStore()
        classic.files["sheets/a.json"] = "{}".encodeToByteArray()
        assertTrue(ClassicGatedSidecarStore(classic, true) { ClassicFileSideSetting.On }.read("p") is SidecarRead.Present)
        assertTrue(ClassicGatedSidecarStore(classic, false) { ClassicFileSideSetting.Off }.read("p") is SidecarRead.Present)
        assertEquals(SidecarWriteOutcome.Written, ClassicGatedSidecarStore(classic, false) { ClassicFileSideSetting.Off }.write("p", mapOf("sheets/a.json" to ByteArray(1))))
    }
}
