// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.console.commands.SqlWriteCommand
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleBuildFlagsTest {
    @AfterTest
    fun reset() {
        ConsoleBuildFlags.internalBuild = false
    }

    @Test
    fun writes_should_BeOffByDefault_When_NoBuildMarkedItselfInternal() {
        assertFalse(ConsoleBuildFlags.RELEASE_WRITES_ENABLED, "the release default flips only in the U18 PR that cites pass 12.1")
        assertFalse(ConsoleBuildFlags.WRITES_ENABLED)
    }

    @Test
    fun writes_should_BeOn_When_InternalBuildFlagSet() {
        ConsoleBuildFlags.internalBuild = true
        assertTrue(ConsoleBuildFlags.WRITES_ENABLED)
    }

    @Test
    fun sqlBang_should_DefaultToTheBuildFlag_When_ConstructedWithoutOverride() {
        val cmd = SqlWriteCommand({ null }, { null }, ConsoleWriteLog())
        assertEquals(ConsoleBuildFlags.WRITES_DISABLED_REASON, cmd.disabledReason)
        ConsoleBuildFlags.internalBuild = true
        assertEquals("no writable database on this platform", cmd.disabledReason)
    }
}
