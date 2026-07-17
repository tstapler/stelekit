// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [FileSystem]'s default (no-op) method implementations — Story 1.3.2 of
 * `web-local-folder-livesync`. [FakeFileSystem] does not override [FileSystem.hostDirectoryAccessState],
 * so calling it through a plain [FileSystem] implementation exercises the interface default
 * exactly as every non-wasmJs platform (JVM/Android/iOS) does — no I/O, no wasmJs override.
 */
class FileSystemDefaultsTest {
    @Test
    fun hostDirectoryAccessState_should_ReturnNotApplicable_When_NoOverrideExists() = runTest {
        val fileSystem: FileSystem = FakeFileSystem()

        val state = fileSystem.hostDirectoryAccessState("/any/path")

        assertEquals(HostAccessState.NotApplicable, state)
    }
}
