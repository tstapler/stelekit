// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.ui.components.settings.isDeveloperCategoryVisible
import dev.stapler.stelekit.wiring.WiringAudit
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 3.1 "Session scope ownership": no `rememberCoroutineScope` value reaches the session, and the
 * session builds its own guarded scope. A text audit, so it proves the shape, not the runtime
 * behavior (`ConsoleSessionTest` covers the OutOfMemoryError case).
 */
class ConsoleScopeOwnershipAuditTest {
    private val consoleFiles: Map<String, String> by lazy {
        WiringInputs.productionSources
            .filterKeys { "/stelekit/console/" in it || "Console" in it.substringAfterLast('/') }
            .mapValues { WiringAudit.stripCommentsAndStrings(it.value) }
    }

    @Test
    fun consoleSources_should_NeverUseRememberCoroutineScope_ToBuildOrFeedASession() {
        assertTrue(consoleFiles.keys.any { it.endsWith("console/ConsoleSession.kt") }, "audit found no console sources: ${consoleFiles.keys}")
        val offenders = consoleFiles.filterValues { "rememberCoroutineScope" in it && "ConsoleSession" in it }
        // ConsoleScreen may use rememberCoroutineScope for the export button only; it must not construct or take a scope for the session.
        val constructing = consoleFiles.filterValues { Regex("""ConsoleSession\s*\(""").containsMatchIn(it) }
        assertEquals(setOf("commonMain/dev/stapler/stelekit/console/ConsoleSession.kt", "commonMain/dev/stapler/stelekit/ui/GraphContentConsoleSetup.kt"), constructing.keys)
        constructing.forEach { (path, text) -> assertFalse("rememberCoroutineScope" in text, "$path builds a session next to rememberCoroutineScope") }
        assertTrue(offenders.keys.all { it.endsWith("ConsoleScreen.kt") }, "unexpected rememberCoroutineScope use: ${offenders.keys}")
    }

    @Test
    fun consoleSession_should_OwnItsScope_WithAnExceptionHandler() {
        val source = consoleFiles.entries.first { it.key.endsWith("console/ConsoleSession.kt") }.value
        assertTrue(Regex("""CoroutineScope\(\s*SupervisorJob\(\)""").containsMatchIn(source))
        assertTrue("CoroutineExceptionHandler" in source)
        assertFalse(Regex("""class ConsoleSession\([^)]*CoroutineScope""").containsMatchIn(source), "scope must not be a constructor parameter")
    }

    @Test
    fun developerCategory_should_BeVisible_When_AnyRowExists_IncludingWebWhereOnlyTheConsoleToggleIsPresent() {
        val toggle: (Boolean) -> Unit = {}
        assertFalse(isDeveloperCategoryVisible(null, null, null))
        assertTrue(isDeveloperCategoryVisible(null, null, toggle))
        assertTrue(isDeveloperCategoryVisible(toggle, null, null))
        assertTrue(isDeveloperCategoryVisible(null, toggle, null))
    }
}
