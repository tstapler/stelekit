@file:OptIn(DirectRepositoryWrite::class, ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketch.SketchViewModel
import dev.stapler.stelekit.units.Inches
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SketchPreviewOnSaveTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private suspend fun kotlinx.coroutines.test.TestScope.drawALine(
        standalone: Boolean, // kibitzer:ignore flag-argument -- test helper, two call sites
        onSaved: (SketchDocument, String) -> Unit,
    ) {
        val repos = inMemorySketchRepositories()
        val project = SketchTestSupport.project().let {
            if (standalone) it.copy(settings = JsonObject(mapOf("standalone" to JsonPrimitive(true)))) else it
        }
        repos.projects.saveProject(project)
        var n = 0
        val vm = SketchViewModel(repos, SketchTestSupport.P, null, StandardTestDispatcher(testScheduler), onSaved) { "id-${n++}" }
        runCurrent()
        vm.placePoint(pt(16, 16))
        vm.placePoint(pt(64, 16))
        advanceTimeBy(SketchViewModel.AUTOSAVE_DEBOUNCE_MS + 1)
        runCurrent()
    }

    @Test
    fun aStandaloneSketchRefreshesItsPreviewAfterEachSave() = runTest {
        val saved = CompletableDeferred<Pair<SketchDocument, String>>()
        drawALine(standalone = true) { doc, slug -> saved.complete(doc to slug) }
        val (doc, slug) = withContext(Dispatchers.Default) { withTimeout(5_000) { saved.await() } }
        assertEquals("proj-0a1b2c", slug)
        assertEquals(1, doc.scene.primitives.size)
    }

    @Test
    fun anOrdinaryProjectNeverWritesAPreview() = runTest {
        var calls = 0
        drawALine(standalone = false) { _, _ -> calls++ }
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
        assertEquals(0, calls)
    }
}
