// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual fun platformBackupFiles(): BackupFiles? = FileBackupFiles

/** Same resolution as [openSqlReadHandle]: a bare name lives under the app's databases directory. */
actual fun resolveDatabasePath(jdbcUrl: String): String? {
    val name = jdbcUrl.substringAfter("jdbc:sqlite:")
    if (name.isEmpty() || jdbcUrl.contains(":memory:")) return null
    if (name.startsWith("/")) return File(name).absolutePath
    return DriverFactory.staticContext?.getDatabasePath(name)?.absolutePath
}

private object FileBackupFiles : BackupFiles {
    override fun ensureDir(path: String) = File(path).let { it.isDirectory || it.mkdirs() }
    override fun list(dir: String) = File(dir).listFiles().orEmpty().filter { it.isFile }.map { BackupFileInfo(it.name, it.lastModified()) }
    override fun delete(path: String) = File(path).delete()
    override fun rename(from: String, to: String) = File(from).renameTo(File(to))
    override fun sizeOf(path: String) = File(path).takeIf { it.isFile }?.length()
    override fun usableSpace(path: String) = generateSequence(File(path).absoluteFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace
    override fun copy(from: String, to: String): Boolean = try {
        Files.copy(File(from).toPath(), File(to).toPath())
        true
    } catch (_: java.io.IOException) {
        File(to).delete()
        false
    }
    override fun readText(path: String) = File(path).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    override fun writeText(path: String, text: String): Boolean = try {
        val tmp = File("$path.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), File(path).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: java.io.IOException) {
        false
    }
}
