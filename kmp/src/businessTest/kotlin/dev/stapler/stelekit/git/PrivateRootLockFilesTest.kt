// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PR #683 round 3: only a repository inside the app-private root is safe from external git. */
class PrivateRootLockFilesTest {
    private val base: File = Files.createTempDirectory("private-root").toFile()

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    @Test
    fun forPrivateRoot_should_ProveNoExternalGitOnlyInsideThePrivateRoot() {
        val privateRoot = File(base, "files").apply { mkdirs() }
        val shadow = File(privateRoot, "graphs/g/gitshadow/.git").apply { mkdirs() }
        val shared = File(base, "storage/emulated/Notes/.git").apply { mkdirs() }
        val sibling = File(base, "files-other/.git").apply { mkdirs() }

        assertEquals(false, JavaIoLockFiles.forPrivateRoot(privateRoot, shadow).gitProcessMayBeRunning())
        // Outside the private root a scan can find a process or say "cannot tell", never "none".
        assertEquals(true, JavaIoLockFiles.forPrivateRoot(privateRoot, shared).gitProcessMayBeRunning() != false)
        assertEquals(true, JavaIoLockFiles.forPrivateRoot(privateRoot, sibling).gitProcessMayBeRunning() != false, "a prefix match is not containment")
    }
}
