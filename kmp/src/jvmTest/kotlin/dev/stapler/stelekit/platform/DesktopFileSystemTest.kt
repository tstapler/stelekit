// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real-filesystem tests; `validatePath` pins every path under `user.home`, so temp dirs live there. */
class DesktopFileSystemTest {
    private val fs = DesktopFileSystem()
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory(Paths.get(System.getProperty("user.home")), "dfs-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun p(name: String) = File(root, name).path

    @Test
    fun `expandTilde replaces leading tilde with home dir`() {
        assertEquals("${fs.homeDir}/notes", fs.expandTilde("~/notes"))
    }

    @Test
    fun `expandTilde leaves other paths unchanged`() {
        assertEquals("/var/data", fs.expandTilde("/var/data"))
    }

    @Test
    fun `getDefaultGraphPath is under home`() {
        assertEquals("${fs.homeDir}/Documents/stelekit", fs.getDefaultGraphPath())
    }

    @Test
    fun `readFile returns content of existing file`() {
        File(root, "a.md").writeText("hello")
        assertEquals("hello", fs.readFile(p("a.md")))
    }

    @Test
    fun `readFile returns null for missing file and for directory`() {
        assertNull(fs.readFile(p("missing.md")))
        assertNull(fs.readFile(root.path))
    }

    @Test
    fun `readFile returns null for file over MAX_FILE_SIZE`() {
        val big = File(root, "big.bin")
        RandomAccessFile(big, "rw").use { it.setLength(DesktopFileSystem.MAX_FILE_SIZE + 1L) }
        assertNull(fs.readFile(big.path))
    }

    @Test
    fun `readFile rejects path traversal`() {
        assertNull(fs.readFile("${root.path}/../../etc/passwd"))
    }

    @Test
    fun `readFile rejects path outside home`() {
        assertNull(fs.readFile("/etc/hostname"))
    }

    @Test
    fun `readFile rejects null byte`() {
        assertNull(fs.readFile(p("a\u0000b")))
    }

    @Test
    fun `readFile rejects overlong path`() {
        assertNull(fs.readFile(p("x".repeat(DesktopFileSystem.MAX_PATH_LENGTH))))
    }

    @Test
    fun `writeFile creates parent directories and writes content`() {
        val target = p("nested/deeper/out.md")
        assertTrue(fs.writeFile(target, "body"))
        assertEquals("body", File(target).readText())
    }

    @Test
    fun `writeFile overwrites existing content`() {
        File(root, "o.md").writeText("long old content")
        assertTrue(fs.writeFile(p("o.md"), "new"))
        assertEquals("new", File(root, "o.md").readText())
    }

    @Test
    fun `writeFile returns false for traversal and outside home`() {
        assertFalse(fs.writeFile("${root.path}/../../escape.md", "x"))
        assertFalse(fs.writeFile("/tmp/stelekit-dfs-escape.md", "x"))
    }

    @Test
    fun `writeFile returns false when content exceeds MAX_FILE_SIZE`() {
        val huge = "a".repeat(DesktopFileSystem.MAX_FILE_SIZE + 1)
        assertFalse(fs.writeFile(p("huge.md"), huge))
        assertFalse(File(root, "huge.md").exists())
    }

    @Test
    fun `listFiles returns sorted file names only`() {
        File(root, "b.md").writeText("")
        File(root, "a.md").writeText("")
        File(root, "sub").mkdir()
        assertEquals(listOf("a.md", "b.md"), fs.listFiles(root.path))
    }

    @Test
    fun `listDirectories returns sorted directory names only`() {
        File(root, "zeta").mkdir()
        File(root, "alpha").mkdir()
        File(root, "file.md").writeText("")
        assertEquals(listOf("alpha", "zeta"), fs.listDirectories(root.path))
    }

    @Test
    fun `listFiles and listDirectories return empty for missing dir, file, and invalid path`() {
        File(root, "f.md").writeText("")
        assertEquals(emptyList(), fs.listFiles(p("nope")))
        assertEquals(emptyList(), fs.listFiles(p("f.md")))
        assertEquals(emptyList(), fs.listDirectories(p("nope")))
        assertEquals(emptyList(), fs.listDirectories(p("f.md")))
        assertEquals(emptyList(), fs.listFiles("/etc"))
        assertEquals(emptyList(), fs.listDirectories("/etc"))
    }

    @Test
    fun `fileExists and directoryExists distinguish files from directories`() {
        File(root, "f.md").writeText("")
        File(root, "d").mkdir()
        assertTrue(fs.fileExists(p("f.md")))
        assertFalse(fs.fileExists(p("d")))
        assertFalse(fs.fileExists(p("missing")))
        assertTrue(fs.directoryExists(p("d")))
        assertFalse(fs.directoryExists(p("f.md")))
        assertFalse(fs.fileExists("/etc/hostname"))
        assertFalse(fs.directoryExists("/etc"))
    }

    @Test
    fun `createDirectory creates nested dirs and is idempotent`() {
        val dir = p("one/two")
        assertTrue(fs.createDirectory(dir))
        assertTrue(File(dir).isDirectory)
        assertTrue(fs.createDirectory(dir))
    }

    @Test
    fun `createDirectory returns false when a file occupies the path or path is outside home`() {
        File(root, "f").writeText("")
        assertFalse(fs.createDirectory(p("f")))
        assertFalse(fs.createDirectory("/tmp/stelekit-dfs-outside"))
    }

    @Test
    fun `deleteFile removes file and directory trees and tolerates missing`() {
        File(root, "f.md").writeText("x")
        File(root, "tree/inner").mkdirs()
        File(root, "tree/inner/x.md").writeText("x")
        assertTrue(fs.deleteFile(p("f.md")))
        assertFalse(File(root, "f.md").exists())
        assertTrue(fs.deleteFile(p("tree")))
        assertFalse(File(root, "tree").exists())
        assertTrue(fs.deleteFile(p("never-existed")))
        assertFalse(fs.deleteFile("/etc/hostname"))
    }
}
