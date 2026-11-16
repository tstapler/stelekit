// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A random id persisted once per install; two processes racing to create it converge on the same value. */
object InstallId {
    /** Null when the file can neither be read nor created (the caller then proves no machine identity). */
    fun loadOrCreate(file: File): String? = try {
        read(file) ?: create(file)
    } catch (_: IOException) {
        null
    }

    private fun read(file: File): String? = file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    // Content is written to a temp file, then published with a hard link, which fails if the id already exists.
    private fun create(file: File): String? {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("install-id", ".tmp", file.parentFile)
        return try {
            val id = UUID.randomUUID().toString()
            temp.writeText(id)
            try {
                Files.createLink(file.toPath(), temp.toPath())
                id
            } catch (_: IOException) {
                read(file) ?: run {
                    // Hard links refused: an atomic rename is the best remaining publication.
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    id
                }
            }
        } finally {
            temp.delete()
        }
    }
}
