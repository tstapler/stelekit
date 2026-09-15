// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Shared isolation setup for `GraphRelocationCoordinator*Test` (Story 3.1.5) — redirects
 * `DriverFactory`'s SQLite files to a per-test temp directory via `stelekit.devDataDir`, mirroring
 * `GraphManagerDatabaseLifecycleTest`/`GraphManagerOnGraphLocationDeterminedTest`'s identical
 * precedent. Without this, every real-`GraphManager` test in this suite would write to (and
 * collide on) the actual dev-machine data directory.
 */
abstract class RelocationCoordinatorTestSupport {
    private var originalDevDataDir: String? = null
    private lateinit var tempDataDir: java.io.File

    @BeforeTest
    fun setUpIsolatedDataDir() {
        originalDevDataDir = System.getProperty("stelekit.devDataDir")
        tempDataDir = createTempDirectory("stelekit_relocation_coordinator_test_").toFile()
        System.setProperty("stelekit.devDataDir", tempDataDir.absolutePath)
    }

    @AfterTest
    fun tearDownIsolatedDataDir() {
        if (originalDevDataDir != null) {
            System.setProperty("stelekit.devDataDir", originalDevDataDir!!)
        } else {
            System.clearProperty("stelekit.devDataDir")
        }
        tempDataDir.deleteRecursively()
    }
}
