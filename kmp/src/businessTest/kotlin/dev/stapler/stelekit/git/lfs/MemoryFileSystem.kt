// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.git.testsupport.StubFileSystem

/** Byte-addressable in-memory file system for the LFS guard tests (keys are absolute paths). */
class MemoryFileSystem(val files: MutableMap<String, ByteArray> = mutableMapOf()) : StubFileSystem() {
    override fun readFile(path: String): String? = files[path]?.decodeToString()
    override fun readFileBytes(path: String): ByteArray? = files[path]
    override fun getFileSize(path: String): Long? = files[path]?.size?.toLong()
    override fun writeFile(path: String, content: String): Boolean {
        files[path] = content.encodeToByteArray()
        return true
    }
}
