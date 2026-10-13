// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LfsGuardCoordinatorTest {
    private class MapSettings : Settings {
        val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val jvm = LfsPlatformCapabilities(transferSupported = true, androidLfsEnabled = false)
    private val photo = "assets/photo.png"

    private fun fs(content: ByteArray = LfsRepoFixture.PNG) = MemoryFileSystem(
        mutableMapOf("/repo/.gitattributes" to "assets/** filter=lfs\n".encodeToByteArray(), "/repo/$photo" to content),
    )

    private fun coordinator(
        fs: MemoryFileSystem = fs(),
        mode: LfsMode = LfsMode.GUARD_ONLY,
        capabilities: LfsPlatformCapabilities = jvm,
        delegated: Boolean = false,
    ): LfsGuardCoordinator {
        val settings = MapSettings()
        LfsModeSource(settings).set(sampleConfig.graphId, mode)
        return LfsGuardCoordinator(fs, LfsModeSource(settings), capabilities, delegated = delegated)
    }

    @Test
    fun inputFor_should_BeWorkingTree_When_ModeIsGuardOnlyOrOff() {
        for (mode in listOf(LfsMode.GUARD_ONLY, LfsMode.OFF)) {
            assertEquals(LfsGuardInput.WorkingTree, coordinator(mode = mode).inputFor(sampleConfig.graphId, cleanFilterActive = true), "$mode")
        }
    }

    @Test
    fun inputFor_should_BePostCleanIndex_When_AutoModeAndAConvertingFilterIsActive() {
        assertEquals(LfsGuardInput.PostCleanIndex, coordinator(mode = LfsMode.AUTO).inputFor(sampleConfig.graphId, cleanFilterActive = true))
    }

    @Test
    fun inputFor_should_BeWorkingTree_When_AutoModeButNoFilterIsActive() {
        assertEquals(LfsGuardInput.WorkingTree, coordinator(mode = LfsMode.AUTO).inputFor(sampleConfig.graphId, cleanFilterActive = false))
    }

    @Test
    fun inputFor_should_BeWorkingTree_When_PlatformCannotTransfer() {
        val c = coordinator(mode = LfsMode.AUTO, capabilities = LfsPlatformCapabilities.GUARD_ONLY_PLATFORM)

        assertEquals(LfsGuardInput.WorkingTree, c.inputFor(sampleConfig.graphId, cleanFilterActive = true))
    }

    @Test
    fun inputFor_should_BeDelegated_When_ThePlatformGuardsInItsOwnWritePath() {
        assertEquals(LfsGuardInput.Delegated, coordinator(delegated = true).inputFor(sampleConfig.graphId, cleanFilterActive = false))
    }

    @Test
    fun evaluate_should_HoldTheRawFile_When_InputIsWorkingTree() = runBlocking {
        val verdict = coordinator().evaluate(sampleConfig, listOf(photo, "pages/a.md"), cleanFilterActive = false)

        assertEquals(listOf(photo), verdict.held.map { it.path })
        assertEquals(listOf("pages/a.md"), verdict.allowed)
    }

    @Test
    fun evaluate_should_NotHoldARawFile_When_InputIsPostCleanIndex() = runBlocking {
        val verdict = coordinator(mode = LfsMode.AUTO).evaluate(sampleConfig, listOf(photo), cleanFilterActive = true)

        assertTrue(verdict.isClean, "the clean filter converts it at git add")
    }

    @Test
    fun evaluate_should_StillHoldAPointerWithConflictMarkers_When_InputIsPostCleanIndex() = runBlocking {
        val p = LfsRepoFixture.POINTER.decodeToString()
        val merged = "<<<<<<< HEAD\n$p=======\n$p>>>>>>> origin\n".encodeToByteArray()

        val verdict = coordinator(fs(merged), mode = LfsMode.AUTO).evaluate(sampleConfig, listOf(photo), cleanFilterActive = true)

        assertEquals(LfsViolationReason.ConflictMarkers, verdict.held.single().reason)
    }

    @Test
    fun evaluate_should_ReturnEverythingAllowed_When_Delegated() = runBlocking {
        val verdict = coordinator(delegated = true).evaluate(sampleConfig, listOf(photo), cleanFilterActive = false)

        assertTrue(verdict.isClean)
        assertEquals(listOf(photo), verdict.allowed)
    }

    @Test
    fun holdBack_should_DefaultToTrue_SoOwnerDecisionO6CanBeFlippedInOnePlace() {
        assertTrue(coordinator().holdBack)
        assertEquals(true, LfsGuardCoordinator.DEFAULT_HOLD_BACK)
    }
}
