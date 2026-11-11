// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.right
import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.Equal
import dev.stapler.stelekit.fusion.Fixed
import dev.stapler.stelekit.fusion.Offset
import dev.stapler.stelekit.fusion.Sum
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
class ProjectFusionSourceTest {
    private val slug = "proj-0a1b2c"
    private val zoneCheck = SumCheck("Zones sum to opening", constraintId = 1, total = "niche.width", parts = ClosetFusion.zones)
    private val spec = FusionSpec(ClosetFusion.constraints(), listOf(zoneCheck), ClosetFusion.zones)

    @Test
    fun everyConstraintKindRoundTripsThroughTheSpecJson() {
        val all = FusionSpec(
            constraints = listOf(
                Fixed(1, "a", Rational(3, 2)), Sum(2, "t", listOf("a", "b", "a")), Equal(3, "a", "b"), Offset(4, "a", "b", Rational(-1, 16)),
            ),
            checks = listOf(zoneCheck),
            zoneOrder = listOf("a", "b"),
        )
        assertEquals(all, FusionSpecJson.decode(Json.parseToJsonElement(FusionSpecJson.encode(all).toString())))
        assertEquals(spec, FusionSpecJson.decode(FusionSpecJson.encode(spec)))
    }

    @Test
    fun aDamagedSpecDecodesToNullNotAPartialConstraintSet() {
        listOf(
            """{"constraints":[{"id":1,"type":"mystery"}]}""",
            """{"constraints":[{"id":1,"type":"fixed","dimension":"a","num":1,"den":0}]}""",
            """{"constraints":[{"type":"sum","total":"t","parts":[]}]}""",
            """{"constraints":"nope"}""",
        ).forEach { assertNull(FusionSpecJson.decode(Json.parseToJsonElement(it)), it) }
    }

    @Test
    fun settingsCarryTheRegistryAndSpecAndNothingIdentifying() {
        val settings = ProjectFusionSettings.with(JsonObject(emptyMap()), ClosetFusion.registry, spec)
        val setup = checkNotNull(ProjectFusionSettings.setupOf(slug, settings))

        assertEquals(slug, setup.slug)
        assertEquals(ClosetFusion.registry, setup.registry)
        assertEquals(spec, setup.spec)
        listOf("content://", "file://", "/home", ".md", ".yaml").forEach { assertTrue(!settings.toString().contains(it), it) }
    }

    @Test
    fun noRegistryMeansNoSetupAndABadRegistryOrSpecIsNotGuessedAt() {
        assertNull(ProjectFusionSettings.setupOf(slug, JsonObject(emptyMap())))
        val badRegistry = JsonObject(mapOf(ProjectFusionSettings.REGISTRY_KEY to Json.parseToJsonElement("""{"schema":99,"dimensions":[]}""")))
        assertNull(ProjectFusionSettings.setupOf(slug, badRegistry))
        val registryOnly = JsonObject(mapOf(ProjectFusionSettings.REGISTRY_KEY to Json.parseToJsonElement(dev.stapler.stelekit.fusion.DimensionRegistry.toJson(ClosetFusion.registry))))
        assertEquals(FusionSpec(), checkNotNull(ProjectFusionSettings.setupOf(slug, registryOnly)).spec)
    }

    @Test
    fun loadFusionSetupReadsTheFirstProjectOrNull() = runTest {
        val repos = inMemorySketchRepositories()
        assertNull(loadFusionSetup(repos))
        val project = ProjectFactory.create("Closet", slug, ProjectUuid("pr1"), 1L)
        repos.projects.saveProject(project.copy(settings = ProjectFusionSettings.with(project.settings, ClosetFusion.registry, spec)))
        assertEquals(spec, checkNotNull(loadFusionSetup(repos)).spec)
    }

    /** The app path: registry and spec from the project, YAML from the snapshot, tables from the bound page; no injected collector or fusion. */
    @Test
    fun theJobFusesFromTheProjectRegistryAndSpecAndWritesTheDigest() = runTest {
        val repos = inMemorySketchRepositories()
        val fs = SidecarFs()
        val store = newStore(fs)
        val project = ProjectFactory.create("Closet", slug, ProjectUuid("pr1"), 1L)
        repos.projects.saveProject(project.copy(settings = ProjectFusionSettings.with(project.settings, ClosetFusion.registry, spec)))
        assertIs<YamlImportOutcome.Imported>(
            YamlSnapshotImporter(repos, { store }, { it() }).import("pr1", ClosetFixtureText.load("closet.yaml").encodeToByteArray()),
        )
        val settings = BridgeMapSettings().also { enableBridge(it) }
        val written = mutableListOf<MergePage>()
        val writer = DigestWriter(
            replace = { _, _, page -> written += page; WriteOutcome.Created("p", "h").right() },
            readBack = { _, _ -> written.lastOrNull().right() },
            hasWriter = { true },
        )
        val deps = bridgeDeps(
            settings, InProcessJobs(), router = { newBridgeTestRouter() },
            boundSources = { SourceReadResult(tablePages = listOf(TablePageText("page:0a1b2c3d", ClosetFixtureText.load("closet-tables.md")))) },
            yamlSnapshot = { readYamlSnapshot(repos, store) },
            fusionSetup = { loadFusionSetup(repos) },
        )

        val outcome = PostSyncProjectJob(deps, digestWriter = writer).run(BRIDGE_GRAPH) { false }

        assertIs<PostSyncRunOutcome.Completed>(outcome, outcome.toString())
        val body = written.single().blocks.joinToString("\n") { it.content }
        listOf("Left long hang width: 33 7/16\"", "Shelf zone width: 24 3/16\"").forEach { assertTrue(it in body, "$it missing:\n$body") }
    }

    @Test
    fun withoutARegistryTheJobWritesNothing() = runTest {
        val settings = BridgeMapSettings().also { enableBridge(it) }
        val written = mutableListOf<MergePage>()
        val writer = DigestWriter(
            replace = { _, _, page -> written += page; WriteOutcome.Created("p", "h").right() },
            readBack = { _, _ -> written.lastOrNull().right() },
            hasWriter = { true },
        )
        val deps = bridgeDeps(settings, InProcessJobs(), router = { newBridgeTestRouter() })

        assertIs<PostSyncRunOutcome.Completed>(PostSyncProjectJob(deps, digestWriter = writer).run(BRIDGE_GRAPH) { false })
        assertTrue(written.isEmpty())
    }
}
