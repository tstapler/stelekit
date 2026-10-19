package dev.stapler.stelekit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Temp directory under `/tmp` when it exists. A Unix domain socket path is limited to 108 bytes, and
 * Bazel's default `java.io.tmpdir` sits deep inside the sandbox, so sockets bound there fail with "path too long".
 */
internal fun createShortTempDirectory(prefix: String): Path {
    val shortRoot = Paths.get("/tmp")
    return if (Files.isDirectory(shortRoot) && Files.isWritable(shortRoot)) {
        Files.createTempDirectory(shortRoot, prefix)
    } else {
        Files.createTempDirectory(prefix)
    }
}
