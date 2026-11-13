@file:OptIn(DirectRepositoryWrite::class, ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketch.SketchPreview
import dev.stapler.stelekit.ui.sketch.SketchPreviewLoader
import dev.stapler.stelekit.ui.sketch.SketchViewModel
import dev.stapler.stelekit.units.Inches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SketchPreviewLoaderTest {
    @Test
    fun unknownSlugIsMissingAndSheetlessProjectIsEmpty() = runTest {
        val repos = inMemorySketchRepositories()
        repos.projects.saveProject(SketchTestSupport.project())
        val loader = SketchPreviewLoader(repos)
        assertIs<SketchPreview.Missing>(loader.load("proj-ffffff"))
        assertIs<SketchPreview.Empty>(loader.load("proj-0a1b2c"))
    }

    @Test
    fun savedShapesLoadAsADrawingAndSavesAreAnnounced() = runTest {
        val repos = inMemorySketchRepositories()
        repos.projects.saveProject(SketchTestSupport.project())
        var n = 0
        val vm = SketchViewModel(repos, SketchTestSupport.P, null, StandardTestDispatcher(testScheduler)) { "id-${n++}" }
        runCurrent()
        vm.drawShape(SketchPoint(Inches(0), Inches(0)), SketchPoint(Inches(160), Inches(80)))
        vm.flush()
        val loader = SketchPreviewLoader(repos)
        val drawing = assertIs<SketchPreview.Drawing>(loader.load("proj-0a1b2c"))
        assertEquals(1, drawing.document.scene.size)

        val announced = async(StandardTestDispatcher(testScheduler)) { loader.saved.first() }
        runCurrent()
        loader.notifySaved("proj-0a1b2c")
        runCurrent()
        assertEquals("proj-0a1b2c", announced.await())
    }
}
