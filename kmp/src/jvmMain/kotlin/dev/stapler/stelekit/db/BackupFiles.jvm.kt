// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import java.io.File

actual fun platformBackupFiles(): BackupFiles? = FileBackupFiles

actual fun resolveDatabasePath(jdbcUrl: String): String? {
    if (!jdbcUrl.startsWith("jdbc:sqlite:") || jdbcUrl.contains(":memory:")) return null
    return File(jdbcUrl.substringAfter("jdbc:sqlite:")).absolutePath
}

private object FileBackupFiles : BackupFiles {
    override fun ensureDir(path: String) = File(path).let { it.isDirectory || it.mkdirs() }
    override fun list(dir: String) = File(dir).listFiles().orEmpty().filter { it.isFile }.map { BackupFileInfo(it.name, it.lastModified()) }
    override fun delete(path: String) = File(path).delete()
    override fun rename(from: String, to: String) = File(from).renameTo(File(to))
    override fun sizeOf(path: String) = File(path).takeIf { it.isFile }?.length()
    override fun usableSpace(path: String) = generateSequence(File(path).absoluteFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace
}
