// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import java.io.File

/**
 * Plain `java.io` file system for app-private data (the share inbox) on JVM and Android. Unlike
 * the platform graph file system it has no allowed-root list: Android's rejects `filesDir` outside
 * `graphs/`. Callers must pass only app-owned paths; it never touches user graphs or SAF.
 */
class AppPrivateFileSystem : FileSystem {
    override fun getDefaultGraphPath(): String = ""
    override fun expandTilde(path: String): String = path
    override fun readFile(path: String): String? = runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
    override fun writeFile(path: String, content: String): Boolean = runCatching { File(path).writeText(content) }.isSuccess
    override fun listFiles(path: String): List<String> = File(path).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
    override fun listDirectories(path: String): List<String> = File(path).listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
    override fun fileExists(path: String): Boolean = File(path).isFile
    override fun directoryExists(path: String): Boolean = File(path).isDirectory
    override fun createDirectory(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }
    override fun deleteFile(path: String): Boolean = File(path).let { !it.exists() || it.delete() }
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = File(path).takeIf { it.exists() }?.lastModified()
    override fun readFileBytes(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()
    override fun writeFileBytes(path: String, data: ByteArray): Boolean = runCatching { File(path).writeBytes(data) }.isSuccess

    /** Non-overwriting like the platform file systems: an existing destination reports success and is left alone. */
    override fun renameFile(from: String, to: String): Boolean {
        val source = File(from)
        val destination = File(to)
        return when {
            !source.exists() -> false
            destination.exists() -> true
            else -> source.renameTo(destination)
        }
    }
}
