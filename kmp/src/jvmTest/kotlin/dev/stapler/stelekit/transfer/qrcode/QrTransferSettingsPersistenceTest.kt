package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.PlatformSettings
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.1.1 integration test: [QrTransferSettings] round-trips through a REAL
 * [dev.stapler.stelekit.platform.Settings] storage backend, not a fake — mirrors
 * [dev.stapler.stelekit.platform.PlatformSettingsContainsKeyTest]'s precedent of redirecting
 * `user.home` to an isolated temp directory for the duration of the test so this exercises the
 * real production `PlatformSettings` (backed by `~/.stelekit/prefs.properties`) without touching
 * the real developer/CI machine's home directory.
 *
 * Two separate [PlatformSettings] instances are constructed against the same redirected
 * `user.home` — [PlatformSettings.init] loads whatever `prefs.properties` already exists on disk,
 * and every setter immediately `save()`s — so this genuinely models "process-like instance
 * boundaries" (validation.md), not just two references to one in-memory object.
 */
class QrTransferSettingsPersistenceTest {

    private lateinit var originalUserHome: String
    private lateinit var tempHome: java.io.File

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
        tempHome = createTempDirectory("qr_transfer_settings_persistence_test_").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalUserHome)
        tempHome.deleteRecursively()
    }

    @Test
    fun qrTransferSettings_should_PersistAcrossInstances_When_BackedByRealPlatformSettings() {
        val writer = QrTransferSettings(PlatformSettings())
        writer.enabled = true
        writer.framesPerSecond = 2.0
        writer.reduceMotion = true

        // A second QrTransferSettings wrapping a brand-new PlatformSettings() instance — the real
        // JVM actual re-reads `~/.stelekit/prefs.properties` from disk on construction, so this
        // only passes if the writer's setters genuinely persisted, not merely mutated in-memory
        // state shared by reference.
        val reader = QrTransferSettings(PlatformSettings())

        assertTrue(reader.enabled, "enabled must survive across PlatformSettings instances")
        assertEquals(2.0, reader.framesPerSecond, "framesPerSecond must survive across PlatformSettings instances")
        assertTrue(reader.reduceMotion, "reduceMotion must survive across PlatformSettings instances")
    }
}
