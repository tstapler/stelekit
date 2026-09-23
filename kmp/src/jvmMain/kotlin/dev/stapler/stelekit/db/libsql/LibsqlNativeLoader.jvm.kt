package dev.stapler.stelekit.db.libsql

import java.io.File
import java.io.FileOutputStream
import java.util.logging.Logger

private val log = Logger.getLogger("LibsqlJni")

internal actual fun loadLibsqlNativeLibrary() {
    val libName = System.mapLibraryName("stelekit_libsql") // e.g. "libstelekit_libsql.so"
    val ext = libName.substringAfterLast('.') // "so", "dylib", or "dll"
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val resourceDir = when {
        os.contains("linux") && arch.contains("amd64") -> "linux-x86_64"
        os.contains("linux") && arch.contains("aarch64") -> "linux-aarch64"
        os.contains("mac") && arch.contains("aarch64") -> "macos-aarch64"
        os.contains("mac") -> "macos-x86_64"
        os.contains("win") -> "windows-x86_64"
        else -> error("Unsupported platform: os=$os arch=$arch")
    }
    val resourcePath = "/native/$resourceDir/$libName"
    val stream = LibsqlJni::class.java.getResourceAsStream(resourcePath)
        ?: error("Native library not bundled at $resourcePath. Trigger the 'Build Native Libraries' workflow or run: cd native/libsql && cargo build --release")
    // Read bytes once — used for both the hash and the write.
    val bytes = stream.use { it.readBytes() }
    val crc = java.util.zip.CRC32().also { it.update(bytes) }
    val hash = java.lang.Long.toHexString(crc.value).padStart(8, '0')
    val tmpDir = File(
        System.getProperty("org.sqlite.tmpdir") ?: System.getProperty("java.io.tmpdir"),
        "stelekit-libsql",
    )
    tmpDir.mkdirs()
    // Hash-based filename prevents stale-binary collisions; no separate version needed.
    val tmpLib = File(tmpDir, "libstelekit_libsql-$hash.$ext")
    if (!tmpLib.exists()) {
        FileOutputStream(tmpLib).use { it.write(bytes) }
    }
    try {
        System.load(tmpLib.absolutePath)
    } catch (e: UnsatisfiedLinkError) {
        throw UnsatisfiedLinkError(
            "Failed to load native library from $tmpLib (resource: $resourcePath): ${e.message}"
        )
    }
    log.info("Loaded libsql JNI bridge from $tmpLib")
}
