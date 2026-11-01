// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

/** A file in the console backup directory. */
data class BackupFileInfo(val name: String, val modifiedMs: Long)

/** The few file operations [ConsoleBackup] needs, so its policy is testable without a real disk. */
interface BackupFiles {
    fun ensureDir(path: String): Boolean
    fun list(dir: String): List<BackupFileInfo>
    fun delete(path: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun sizeOf(path: String): Long?

    /** Bytes free on the volume holding [path], or null when unknown. */
    fun usableSpace(path: String): Long?

    /** Copies [from] to the not-yet-existing [to]; false (and no partial file left) on any failure. */
    fun copy(from: String, to: String): Boolean

    /** The whole text file at [path], or null when it does not exist or cannot be read. */
    fun readText(path: String): String?

    /** Replaces the text file at [path] durably (written beside it, then renamed). */
    fun writeText(path: String, text: String): Boolean
}

/** File access for console backups, or null where the platform has no database file (iOS, wasm). */
expect fun platformBackupFiles(): BackupFiles?

/** The database file behind [jdbcUrl] as an absolute path, or null for in-memory and unsupported platforms. */
expect fun resolveDatabasePath(jdbcUrl: String): String?
