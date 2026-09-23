// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 1.7 / B2-fix regression: the JVM [PlatformSettings.containsKey] actual must reflect
 * genuine key presence, not be implemented in terms of the typed getters' default-comparison
 * (which would make it useless for Story 8.2's install-vs-upgrade guard).
 *
 * Redirects `user.home` to an isolated temp directory for the duration of the test so this
 * exercises the real production `PlatformSettings` (backed by `~/.stelekit/prefs.properties`)
 * without touching the real developer/CI machine's home directory.
 */
class PlatformSettingsContainsKeyTest {

    private lateinit var originalUserHome: String
    private lateinit var tempHome: java.io.File

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
        tempHome = createTempDirectory("stelekit_platform_settings_test_").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalUserHome)
        tempHome.deleteRecursively()
    }

    @Test
    fun `containsKey_should_ReturnFalse_When_KeyNeverPut`() {
        val settings = PlatformSettings()
        assertFalse(settings.containsKey("never.put.key"))
    }

    @Test
    fun `containsKey_should_ReturnTrue_When_KeyPutEvenIfValueEqualsTypedDefault`() {
        val settings = PlatformSettings()
        // Put a boolean value equal to getBoolean()'s default (false) — containsKey() must
        // still return true, proving it is NOT implemented via default-comparison.
        settings.putBoolean("explicit.false.key", false)
        assertTrue(settings.containsKey("explicit.false.key"))
        assertEquals(false, settings.getBoolean("explicit.false.key", true))
    }

    @Test
    fun `containsKey_should_ReturnTrue_When_StringKeyPutEvenIfValueEqualsTypedDefault`() {
        val settings = PlatformSettings()
        settings.putString("explicit.default.string", "")
        assertTrue(settings.containsKey("explicit.default.string"))
    }
}
