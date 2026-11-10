// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.annotation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import arrow.core.Either
import androidx.compose.ui.graphics.ImageBitmap
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Size
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.fixtures.FixtureGraphSeeds
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.performance.probe.FixtureGraph
import dev.stapler.stelekit.performance.probe.FrameSampler
import dev.stapler.stelekit.performance.probe.Mutations
import dev.stapler.stelekit.performance.probe.Pause
import dev.stapler.stelekit.performance.probe.ProbeReporter
import dev.stapler.stelekit.performance.probe.ProbeRun
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.performance.probe.CoilDecodeStats
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.AnnotationTool
import dev.stapler.stelekit.ui.components.buildSteleKitImageLoader
import dev.stapler.stelekit.ui.components.resetImageLoaderConstructionsForTest
import dev.stapler.stelekit.ui.components.resetSharedImageMemoryCacheForTest
import dev.stapler.stelekit.ui.components.sharedImageMemoryCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import kotlin.test.assertTrue

/**
 * Success Metric 2 scenarios (f) and (g) on the legacy surfaces (`legacy=true` until the merged
 * Library destination lands, plan B.3). Records go to [ProbeReporter]; `scripts/bench-budget-check.js`
 * decides pass or fail, so these tests assert only that the scenario really ran.
 *
 * Run: ./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.performance.annotation.LibraryScrollSwitchResourceTest
 * Mutations: add -Pandroid.testInstrumentationRunnerArguments.annotateMutation=<name>.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScrollSwitchResourceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture by lazy { FixtureGraph(context) }

    @Before
    fun setUp() {
        resetSharedImageMemoryCacheForTest()
        resetImageLoaderConstructionsForTest()
        runBlocking { fixture.seed() }
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun libraryScroll_unfilteredThenAnnotatedFilter() {
        val stats = CoilDecodeStats()
        val loader = loaderForRun(stats)
        var shown by mutableStateOf(emptyList<File>())
        val fullDecode = Mutations.isActive(Mutations.LOUPE_FULL_DECODE)
        rule.setContent {
            LazyVerticalGrid(GridCells.Fixed(COLUMNS), modifier = Modifier.testTag(GRID_TAG)) {
                itemsIndexed(shown) { index, file ->
                    // A sparse sample: full-size decodes of every cell would OOM the 192 MB heap class
                    // and kill the process before the record is written, losing the red evidence.
                    val request = ImageRequest.Builder(context).data(file)
                        .apply { if (fullDecode && index % FULL_DECODE_EVERY == 0) size(Size.ORIGINAL) }.build()
                    AsyncImage(model = request, contentDescription = null, imageLoader = loader, modifier = Modifier.aspectRatio(1f))
                }
            }
        }
        val unfiltered = runBlocking { assetPaths() }
        val annotated = runBlocking { annotatedPaths() }
        assertTrue(unfiltered.size >= MIN_ROWS && annotated.size >= MIN_ROWS, "fixture produced ${unfiltered.size}/${annotated.size} rows")

        val run = ProbeRun(context, "LibraryScroll").begin()
        val frames = FrameSampler(rule.activity).also { it.start() }
        var screenfuls = 0
        for (rows in listOf(unfiltered, annotated)) {
            rule.runOnUiThread { shown = rows }
            rule.waitForIdle()
            screenfuls += scrollToEnd(rows.size)
        }
        val cpuMs = run.cpuMsSinceBegin()
        val record = run.withFrames(frames.stop()).finish(
            mapOf(
                "cpuMsPerScreenful" to cpuMs.toDouble() / screenfuls,
                "maxDecodeBytes" to stats.maxDecodeBytes.toDouble(),
            ) + cacheMetrics(),
        )
        ProbeReporter.report(context, record)
        ProbeReporter.report(context, run.processRecord())
        assertTrue(stats.decodeCount >= 1, "no Coil decode observed; the decode gate would pass vacuously")
    }

    @Test
    fun switchChurn_30CyclesDoesNotRetainEditors() {
        val run = ProbeRun(context, "SwitchChurn").begin()
        val probe = dev.stapler.stelekit.performance.probe.ResourceProbe(context)
        val refs = ArrayList<WeakReference<AnnotationEditorViewModel>>()
        val leaked = ArrayList<AnnotationEditorViewModel>() // only filled by the leakStrongRef mutation
        val heapAfterCycle = ArrayList<Double>()
        repeat(CYCLES) {
            refs += runEditorCycle(leaked)
            heapAfterCycle += probe.settledJavaUsedBytes() / BYTES_PER_MB
        }
        // Dispatcher worker threads can pin the most recent editor's last continuation until they run
        // other work; one untracked cycle displaces it so the check sees only real retention.
        runEditorCycle(ArrayList())
        val cleared = awaitCleared(refs)
        ProbeReporter.report(context, run.finish(mapOf(
            "editorReferenceCleared" to if (cleared) 1.0 else 0.0,
            "heapSlopeMbPerCycle" to slope(heapAfterCycle),
        )))
        ProbeReporter.report(context, run.processRecord())
        assertTrue(refs.size == CYCLES && leaked.size <= CYCLES, "scenario ran $CYCLES cycles")
    }

    /** Own frame, so no local in the caller keeps the editor reachable while the weak reference is checked. */
    @Suppress("NOTHING_TO_INLINE")
    private fun runEditorCycle(leaked: MutableList<AnnotationEditorViewModel>): WeakReference<AnnotationEditorViewModel> {
        val vm = newEditor()
        vm.selectTool(AnnotationTool.DISTANCE)
        vm.addPoint(NormalizedPoint(0.1, 0.5))
        vm.addPoint(NormalizedPoint(0.4, 0.5))
        vm.close()
        if (Mutations.isActive(Mutations.LEAK_STRONG_REF)) leaked += vm
        return WeakReference(vm)
    }

    private fun scrollToEnd(total: Int): Int {
        var index = 0
        var screenfuls = 0
        while (index < total) {
            rule.onNodeWithTag(GRID_TAG).performScrollToIndex(index)
            rule.waitForIdle()
            Pause.ms(DECODE_SETTLE_MS)
            index += SCREENFUL_ITEMS
            screenfuls++
        }
        return screenfuls
    }

    /** `cacheNoLimit` swaps in a cache with no ceiling while the overflow is still judged against the production limit. */
    private fun loaderForRun(stats: CoilDecodeStats): ImageLoader =
        if (Mutations.isActive(Mutations.CACHE_NO_LIMIT)) {
            ImageLoader.Builder(context)
                .memoryCache(unlimitedCache.value)
                .eventListenerFactory(stats.factory)
                .build()
        } else {
            buildSteleKitImageLoader(context, null, null, stats.factory)
        }

    // "No limit" is NO_LIMIT_FACTOR times the production ceiling: truly unbounded would OOM and lose the record.
    private val unlimitedCache = lazy {
        MemoryCache.Builder().maxSizeBytes(sharedImageMemoryCache(context).maxSize * NO_LIMIT_FACTOR).build()
    }

    private fun cacheMetrics(): Map<String, Double> {
        if (!unlimitedCache.isInitialized()) return emptyMap()
        val limit = sharedImageMemoryCache(context).maxSize
        return mapOf("cacheOverflowBytes" to maxOf(0L, unlimitedCache.value.size - limit).toDouble())
    }

    /** Bounded: pages of 100 assets, never a whole-table read. */
    private suspend fun assetPaths(): List<File> {
        val out = ArrayList<File>()
        var offset = 0
        while (true) {
            val batch = fixture.assets.getAssets(PAGE_SIZE, offset).first().getOrNull().orEmpty()
            if (batch.isEmpty()) return out
            batch.mapTo(out) { File(it.filePath) }
            offset += PAGE_SIZE
        }
    }

    /** The Annotated filter: the 100 fixture pages of annotation rows, one bounded query per page. */
    private suspend fun annotatedPaths(): List<File> =
        (0 until ANNOTATION_PAGES).flatMap { page ->
            fixture.annotations.getImageAnnotationsByPage("synthetic-page-$page").first().getOrNull().orEmpty().map { File(it.filePath) }
        }

    private object UnusedDepthEstimator : MonocularDepthEstimator {
        override val isAvailable = false
        override suspend fun initialize(): Either<DomainError, Unit> = Either.Right(Unit)
        override suspend fun estimateDepth(imageBitmap: ImageBitmap): Either<DomainError, FloatArray> = Either.Right(FloatArray(0))
    }

    private fun newEditor() = AnnotationEditorViewModel(
        measurementRepository = InMemoryMeasurementAnnotationRepository(),
        imageWidthPx = FixtureGraphSeeds.JPEG_WIDTH_PX.toDouble(),
        imageHeightPx = FixtureGraphSeeds.JPEG_HEIGHT_PX.toDouble(),
        monocularDepthEstimator = UnusedDepthEstimator,
    ).also {
        it.initialize(
            ImageAnnotation(
                uuid = "synthetic-img-0", blockUuid = "synthetic-blk-0", pageUuid = "synthetic-page-0",
                graphPath = "/synthetic-graph", filePath = "/synthetic-graph/assets/synthetic-0.jpg",
                calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100), unit = MeasurementUnit.METERS,
            ),
        )
    }

    /**
     * Three `System.gc()` calls are not enough on ART: loop with a pause for up to 5 s. The newest
     * [PINNED_TAIL] editors may stay pinned by a dispatcher worker's last frame (observed: 1-2 of 30
     * with no leak), so they are excluded; a real leak holds every cycle (`leakStrongRef` leaves 0 of 30 cleared).
     */
    private fun awaitCleared(refs: List<WeakReference<AnnotationEditorViewModel>>): Boolean {
        val deadline = System.nanoTime() + CLEAR_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            System.gc()
            Runtime.getRuntime().runFinalization()
            if (refs.dropLast(PINNED_TAIL).all { it.get() == null }) return true
            Pause.ms(CLEAR_POLL_MS)
        }
        return false
    }

    /** Least-squares slope of heap MB against cycle index. */
    private fun slope(ys: List<Double>): Double {
        val n = ys.size
        val xMean = (n - 1) / 2.0
        val yMean = ys.average()
        val num = ys.indices.sumOf { (it - xMean) * (ys[it] - yMean) }
        val den = ys.indices.sumOf { (it - xMean) * (it - xMean) }
        return if (den == 0.0) 0.0 else num / den
    }

    private companion object {
        const val GRID_TAG = "synthetic-library-grid"
        const val COLUMNS = 3
        const val SCREENFUL_ITEMS = 12
        const val DECODE_SETTLE_MS = 40L
        const val MIN_ROWS = 1_000
        const val PAGE_SIZE = 100
        const val ANNOTATION_PAGES = 100
        const val CYCLES = 30
        const val PINNED_TAIL = 2
        const val FULL_DECODE_EVERY = 400
        const val NO_LIMIT_FACTOR = 4
        const val BYTES_PER_MB = 1024.0 * 1024.0
        const val CLEAR_TIMEOUT_NANOS = 5_000_000_000L
        const val CLEAR_POLL_MS = 100L
    }
}
