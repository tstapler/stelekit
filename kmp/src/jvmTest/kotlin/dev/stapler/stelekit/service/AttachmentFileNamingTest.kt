// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentFileNamingTest {
    private lateinit var tempDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("attachment-naming-test")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test fun `returns base name when no conflict`() {
        val assetsDir = tempDir.toOkioPath()
        assertEquals("photo.jpg", uniqueFileName(assetsDir, "photo", "jpg"))
    }

    @Test fun `returns dash-1 when base exists`() {
        val assetsDir = tempDir.toOkioPath()
        Files.createFile(tempDir.resolve("photo.jpg"))
        assertEquals("photo-1.jpg", uniqueFileName(assetsDir, "photo", "jpg"))
    }

    @Test fun `returns dash-2 when dash-1 also exists`() {
        val assetsDir = tempDir.toOkioPath()
        Files.createFile(tempDir.resolve("photo.jpg"))
        Files.createFile(tempDir.resolve("photo-1.jpg"))
        assertEquals("photo-2.jpg", uniqueFileName(assetsDir, "photo", "jpg"))
    }

    @Test fun `no extension base case`() {
        val assetsDir = tempDir.toOkioPath()
        assertEquals("file", uniqueFileName(assetsDir, "file", ""))
    }
}
