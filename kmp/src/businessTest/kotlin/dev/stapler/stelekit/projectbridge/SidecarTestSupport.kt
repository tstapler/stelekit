// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.db.FakeRelocationFileSystem
import dev.stapler.stelekit.platform.AtomicFileWriter
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.model.ProjectBundle

internal const val GRAPH = "/g"

/** In-memory file system with a switchable crash (every mutation throws once [dead]), like a killed process. */
internal class SidecarFs : FakeRelocationFileSystem() {
    var tempRenameSupported = true
    var crashAtRename = false
    var dead = false

    override val supportsTempRenameWrites get() = tempRenameSupported
    override fun listDirectories(path: String): List<String> {
        val prefix = "${path.trimEnd('/')}/"
        return allFilePaths().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotEmpty() }.distinct()
    }

    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        if (dead) error("process is gone")
        return super.writeFileBytes(path, data)
    }

    override fun deleteFile(path: String): Boolean {
        if (dead) error("process is gone")
        return super.deleteFile(path)
    }

    override fun renameFile(from: String, to: String): Boolean {
        if (dead) error("process is gone")
        if (crashAtRename && to.endsWith("project-model.json")) { dead = true; error("crash between delete and rename") }
        return super.renameFile(from, to)
    }

    fun seed(path: String, bytes: ByteArray) { super.writeFileBytes(path, bytes) }
    fun revive() { dead = false; crashAtRename = false }
    fun tmps() = allFilePaths().filter { it.endsWith(".tmp") }
}

internal fun sidecarPath(slug: String, rel: String) = "$GRAPH/assets/stelekit-sketch/$slug/$rel"

internal fun newStore(fs: SidecarFs, busy: () -> Boolean = { false }) =
    FileProjectSidecarStore(fs, GRAPH, AtomicFileWriter(fs, { it }, ".tmp") { 1_000_000_000L }, busy)

/** One device: its own repositories, its own idea of the file system (a git working tree). */
internal class Device(val fs: SidecarFs = SidecarFs(), var busy: Boolean = false) {
    val repos: SketchRepositories = SketchTestSupport.backends().first { it.name == "sqldelight" }.repos
    val store = newStore(fs) { busy }
    val reconciler = SidecarReconciler(repos, store)

    /** Copies the sidecar directory of [from], as a git pull would. */
    fun pullFrom(other: Device) {
        other.fs.allFilePaths().filter { it.contains("/assets/stelekit-sketch/") && !it.endsWith(".tmp") }
            .forEach { fs.seed(it, other.fs.readFileBytes(it)!!) }
    }
}

internal fun ProjectBundle.withName(name: String) = copy(project = project.copy(name = name))
