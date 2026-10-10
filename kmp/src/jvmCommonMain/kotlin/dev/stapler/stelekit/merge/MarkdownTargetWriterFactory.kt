package dev.stapler.stelekit.merge

import dev.stapler.stelekit.platform.FileSystem
import java.io.File

/** JVM and Android writer with symlink resolution on, so a symlinked page file or folder cannot escape the root. */
fun MarkdownTargetWriter.Companion.forFilePaths(
    fs: FileSystem,
    target: OffGraphTarget,
    capabilities: TargetWriterCapabilities,
): MarkdownTargetWriter = MarkdownTargetWriter(fs, target, capabilities) { File(it).canonicalPath }

/** Startup sweep against the real disk (JVM and Android); blocking IO, call off the main thread. */
fun sweepMergeArtifactsOnDisk(appDataDir: String, nowEpochMs: Long = System.currentTimeMillis()) =
    sweepMergeArtifacts(okio.FileSystem.SYSTEM, appDataDir, nowEpochMs)
