// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest fixture; no UI)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.ui.sketch.v2.V2EditorDeps

/** A settings store in memory. */
class RigSettings : Settings {
    private val map = mutableMapOf<String, Any>()
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun putString(key: String, value: String) { map[key] = value }
    override fun containsKey(key: String) = map.containsKey(key)
}

/**
 * One project with one v2 sheet over in-memory repositories and a scripted sidecar store: what the editor opens in a test.
 * [deps] is what `StelekitViewModel.v2EditorDeps` builds from the real project bridge.
 */
@OptIn(DirectRepositoryWrite::class)
class V2EditorRig(
    val repos: SketchRepositories = inMemorySketchRepositories(),
    val store: ScriptedSidecarStore = ScriptedSidecarStore(),
    mode: V2Mode = V2Mode.On,
    settings: Settings = RigSettings(),
    leases: SheetLeaseRegistry? = null,
) {
    val sheet = SheetUuid("s2")
    val slug = "proj-0a1b2c"
    val project = SketchProject(ProjectUuid("p1"), slug, "Kitchen", 1L)
    val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)
    val deps = V2EditorDeps(
        repos = repos,
        store = { store },
        leases = leases,
        settings = ProjectBridgeSettings(settings).also { it.setV2Mode(GRAPH, mode) },
        graphId = { GRAPH },
    )

    /** Writes [doc] as the sheet's v2 file and its row; returns the bytes. */
    suspend fun seed(doc: SketchDocumentV2 = SketchDocumentV2(sheetUuid = sheet.value)): ByteArray {
        val bytes = SheetCodecV2.encode(doc).getOrNull()!!
        val row = SketchSheet(sheet, project.uuid, "plan", "top", "in", 16, schemaVersion = SheetPaths.SCHEMA_V2)
        val bundle = ProjectBundle(project, listOf(row), emptyList(), emptyList(), mapOf(sheet to SheetPayload.V2Opaque.of(bytes)))
        repos.projects.replaceProjectContents(project.uuid, bundle)
        store.files.putAll(SidecarCodec.encode(bundle))
        return bytes
    }

    fun diskDoc(): SketchDocumentV2 = SheetCodecV2.decode(store.files.getValue(path)).getOrNull()!!

    companion object {
        const val GRAPH = "graph-1"
    }
}
