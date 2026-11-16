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

    private val mtimes = HashMap<String, Long>()
    private var clock = 0L

    /** Number of file writes so far; a test asserts "zero file writes" by comparing it before and after. */
    val writeCount get() = clock

    override fun getLastModifiedTime(path: String): Long? = if (fileExists(path)) mtimes[path] ?: 0L else null

    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        if (dead) error("process is gone")
        mtimes[path] = ++clock
        return super.writeFileBytes(path, data)
    }

    override fun deleteFile(path: String): Boolean {
        if (dead) error("process is gone")
        return super.deleteFile(path)
    }

    override fun renameFile(from: String, to: String): Boolean {
        if (dead) error("process is gone")
        if (crashAtRename && to.endsWith("project-model.json")) { dead = true; error("crash between delete and rename") }
        val moved = super.renameFile(from, to)
        if (moved) mtimes[to] = mtimes.remove(from) ?: ++clock
        return moved
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

/** A project with v1 sheet `p1-s0` (2 primitives) and v2 sheet `p1-v2` whose scene bytes are [V2Fixtures.bytes]. */
internal object V2Fixtures {
    const val V2_SHEET = "p1-v2"
    val bytes = "{\"schema\":2,\"sheetUuid\":\"$V2_SHEET\",\"scene\":{\"elements\":[]}}\n".encodeToByteArray()
    val v2Path = SheetPaths.pathFor(dev.stapler.stelekit.model.SheetUuid(V2_SHEET), SheetPaths.SCHEMA_V2)

    fun mixedBundle(v2Bytes: ByteArray = bytes): ProjectBundle {
        val base = SketchTestSupport.bundle(sheets = 1)
        val v2 = SketchTestSupport.sheet(V2_SHEET, position = 1).copy(schemaVersion = SheetPaths.SCHEMA_V2)
        return base.copy(
            sheets = base.sheets + v2,
            sheetPayloads = mapOf(v2.uuid to dev.stapler.stelekit.model.SheetPayload.V2Opaque.of(v2Bytes)),
        )
    }
}

internal fun ProjectBundle.withName(name: String) = copy(project = project.copy(name = name))
