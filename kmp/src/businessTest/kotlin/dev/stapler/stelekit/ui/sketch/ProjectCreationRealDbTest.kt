// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.projectbridge.BridgeMapSettings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.sqlDelightSketchRepositories
import dev.stapler.stelekit.sketch.StandaloneSketchService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectCreationRealDbTest {
    @Test
    fun createProjectThroughRealActorAndSqlDelightShowsUpInTheList() = runBlocking<Unit> {
        val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        val repos = sqlDelightSketchRepositories(database)
        val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
        val vm = projectListViewModelFor(repos, actor, "g1", BridgeMapSettings())
        try {
            vm.createProject("Hall closet")
            withTimeout(5_000) { vm.state.first { it.projects.isNotEmpty() || it.message != null } }
            assertNull(vm.state.value.message)
            assertEquals(listOf("Hall closet"), vm.state.value.projects.map { it.name })
        } finally {
            vm.close()
            actor.close()
        }
    }

    @Test
    fun newSketchThroughRealActorAndSqlDelightIsListedAsASketchNotAProject() = runBlocking<Unit> {
        val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        val repos = sqlDelightSketchRepositories(database)
        val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
        val service = StandaloneSketchService(repos.projects, { op -> actor.execute(op = op) }, null, { 1L })
        val vm = projectListViewModelFor(repos, actor, "g1", BridgeMapSettings(), newSketch = service)
        try {
            vm.createSketch { }
            withTimeout(5_000) { vm.state.first { it.sketches.isNotEmpty() || it.message != null } }
            assertNull(vm.state.value.message)
            assertEquals(emptyList(), vm.state.value.projects)
        } finally {
            vm.close()
            actor.close()
        }
    }
}
