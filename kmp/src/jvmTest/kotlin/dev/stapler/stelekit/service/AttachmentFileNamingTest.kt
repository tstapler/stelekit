// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.service

import okio.FileSystem
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
        assertEquals("photo.jpg", uniqueFileName(assetsDir, "photo", "jpg", FileSystem.SYSTEM))
    }

    @Test fun `returns dash-1 when base exists`() {
        val assetsDir = tempDir.toOkioPath()
        Files.createFile(tempDir.resolve("photo.jpg"))
        assertEquals("photo-1.jpg", uniqueFileName(assetsDir, "photo", "jpg", FileSystem.SYSTEM))
    }

    @Test fun `returns dash-2 when dash-1 also exists`() {
        val assetsDir = tempDir.toOkioPath()
        Files.createFile(tempDir.resolve("photo.jpg"))
        Files.createFile(tempDir.resolve("photo-1.jpg"))
        assertEquals("photo-2.jpg", uniqueFileName(assetsDir, "photo", "jpg", FileSystem.SYSTEM))
    }

    @Test fun `no extension base case`() {
        val assetsDir = tempDir.toOkioPath()
        assertEquals("file", uniqueFileName(assetsDir, "file", "", FileSystem.SYSTEM))
    }

    // Minimal FileSystem stub for uniqueFileName tests. Only fileExists is meaningful.
    private class FakeFileSystem(private val existingPaths: Set<String>) : dev.stapler.stelekit.platform.FileSystem {
        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = path in existingPaths
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    @Test fun `string-path overload returns base name when no conflict`() {
        val fs = FakeFileSystem(emptySet())
        assertEquals("photo.jpg", uniqueFileName("/assets", "photo", "jpg", fs))
    }

    @Test fun `string-path overload returns dash-1 when base exists`() {
        val fs = FakeFileSystem(setOf("/assets/photo.jpg"))
        assertEquals("photo-1.jpg", uniqueFileName("/assets", "photo", "jpg", fs))
    }

    @Test fun `string-path overload returns dash-2 when dash-1 also exists`() {
        val fs = FakeFileSystem(setOf("/assets/photo.jpg", "/assets/photo-1.jpg"))
        assertEquals("photo-2.jpg", uniqueFileName("/assets", "photo", "jpg", fs))
    }
}
