// jvmTest-reason: pixels
// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import arrow.core.Either
import dev.stapler.stelekit.sketch.constraints.Solver
import dev.stapler.stelekit.sketch.constraints.SolverCorpus
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 6.2.2 (Task 6.2.2a): the CI gate of the sketch engine's performance budgets, `benchmarks/budgets/sketch-engine-budgets.json`.
 * One test per `sketch.*` key; each measures on this host, prints a `SKETCH-BENCH {...}` line and fails when the figure breaches the
 * budget of the file. The scene size is `SceneLimits.maxElements`, the solver corpus is the 100-constraint one of Spike 0.1.2.
 *
 * The frame rule is the one baselines.md settled on: the p95 frame time of the real `ScenePainter` with 500 elements is at most
 * the file's `max_ratio_to_prebuilt_paths` times 500 prebuilt filled and stroked paths drawn with no painter in the same run, and
 * under the file's `ceiling_ms`. The plan's earlier "1.5x an empty canvas" cannot be met because an empty canvas is a bare clear.
 * It runs on the JVM software rasteriser only; the Pixel_Fold number is owner-run and advisory (Story 6.2.4c), never a CI check.
 */
class SketchEngineBenchmark {
    private val limits = SceneLimits.Default
    private val metrics = FixedAdvanceTextMetricsProvider()

    private fun unit(v: Int) = SceneUnit.ofInt(v)

    private fun element(i: Int, columns: Int): RectElement = RectElement(
        ElementBase(ElementId("r$i"), style = ElementStyle(backgroundColor = BACKGROUND, roughness = 0), seed = i + 1),
        unit((i % columns) * STEP_X), unit((i / columns) * STEP_Y), unit(RECT_W), unit(RECT_H),
    )

    private fun mixedScene(count: Int): SketchSceneV2 = (0 until count).fold(SketchSceneV2.empty(limits)) { s, i ->
        val base = ElementBase(ElementId("e$i"), style = ElementStyle(backgroundColor = BACKGROUND), seed = i + 1)
        val x = (i % WIDE_COLUMNS) * STEP_X
        val y = (i / WIDE_COLUMNS) * STEP_Y
        val e = if (i % 2 == 0) RectElement(base, unit(x), unit(y), unit(RECT_W), unit(RECT_H))
        else EllipseElement(base, unit(x + RECT_W / 2), unit(y + RECT_H / 2), unit(RECT_W / 2), unit(RECT_H / 2))
        s.add(e).getOrNull()!!
    }

    private fun p95(xs: List<Double>): Double = xs.sorted()[(xs.size * P95 - 1).toInt().coerceAtLeast(0)]

    private fun bestOfMs(rounds: Int = ROUNDS, warmups: Int = WARMUPS, block: () -> Unit): Double {
        repeat(warmups) { block() }
        return (0 until rounds).minOf { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / NANOS_PER_MS }
    }

    private fun report(key: String, figure: Double, extra: String = "") {
        println("SKETCH-BENCH {\"key\":\"$key\",\"value\":$figure$extra}")
    }

    @Test
    fun budgetsFile_should_NameEveryMetricOfTheStory() {
        for (key in KEYS) assertTrue(entry(key).isNotEmpty(), "sketch-engine-budgets.json has no entry $key")
    }

    @Test
    fun frame500_should_StayWithinTheRatioToPrebuiltPaths_AndTheCeiling() {
        val scene = (0 until FRAME_ELEMENTS).fold(SketchSceneV2.empty(limits)) { s, i -> s.add(element(i, FRAME_COLUMNS)).getOrNull()!! }
        val ops = DrawListBuilder(metrics).build(scene, RenderStyle.Clean)
        val painter = ScenePainter()
        val bitmap = ImageBitmap(FRAME_WIDTH, FRAME_HEIGHT, ImageBitmapConfig.Argb8888)
        val painted = ArrayList<Double>()
        for (frame in 0 until WARMUP_FRAMES + FRAMES) {
            val t0 = System.nanoTime()
            drawTo(bitmap) { painter.paint(this, ops, SketchViewport.of(panX = -frame * PAN_X, panY = -frame * PAN_Y), null) }
            if (frame >= WARMUP_FRAMES) painted += (System.nanoTime() - t0) / NANOS_PER_MS
        }
        val reference = prebuiltPathFrames()
        val painterP95 = p95(painted)
        val referenceP95 = p95(reference)
        val budget = entry("sketch.frame_ms_500_elements")
        val maxRatio = budget.num("max_ratio_to_prebuilt_paths")
        val ceiling = budget.num("ceiling_ms")
        report("sketch.frame_ms_500_elements", painterP95, ",\"referenceP95\":$referenceP95,\"ratio\":${painterP95 / referenceP95}")
        assertTrue(painterP95 <= referenceP95 * maxRatio, "painter p95 $painterP95 ms against $referenceP95 ms of prebuilt paths (x$maxRatio)")
        assertTrue(painterP95 <= ceiling, "painter p95 $painterP95 ms is over the $ceiling ms ceiling")
    }

    private fun drawTo(bitmap: ImageBitmap, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            drawRect(Color.White)
            block()
        }
    }

    private fun prebuiltPathFrames(): List<Double> {
        val bitmap = ImageBitmap(FRAME_WIDTH, FRAME_HEIGHT, ImageBitmapConfig.Argb8888)
        val paths = (0 until FRAME_ELEMENTS).map { i ->
            Path().apply {
                addRect(Rect(Offset(((i % FRAME_COLUMNS) * STEP_X).toFloat(), ((i / FRAME_COLUMNS) * STEP_Y).toFloat()), Size(RECT_W.toFloat(), RECT_H.toFloat())))
            }
        }
        val stroke = Stroke(REFERENCE_STROKE_WIDTH)
        val times = ArrayList<Double>()
        for (frame in 0 until WARMUP_FRAMES + FRAMES) {
            val t0 = System.nanoTime()
            drawTo(bitmap) {
                withTransform({ translate(-frame * PAN_X.toFloat(), -frame * PAN_Y.toFloat()) }) {
                    for (path in paths) {
                        drawPath(path, Color(REFERENCE_FILL))
                        drawPath(path, Color(REFERENCE_STROKE), style = stroke)
                    }
                }
            }
            if (frame >= WARMUP_FRAMES) times += (System.nanoTime() - t0) / NANOS_PER_MS
        }
        return times
    }

    @Test
    fun solve100Constraints_should_StayUnderTheBudget() {
        val solver = Solver()
        var worst = 0.0
        for (seed in SolverCorpus.corpusSeeds) {
            val case = SolverCorpus.hundredConstraints(seed)
            worst = maxOf(worst, bestOfMs(rounds = SOLVE_ROUNDS, warmups = 1) { solver.solve(case.problem, case.start) })
        }
        report("sketch.solve_ms_100_constraints", worst)
        assertTrue(worst <= entry("sketch.solve_ms_100_constraints").num("max"), "worst seed $worst ms")
    }

    @Test
    fun hitTestCandidates_should_StayFarBelowTheElementCount() {
        val scene = mixedScene(limits.maxElements)
        val index = SpatialIndex().also { it.rebuild(scene) }
        var worst = 0
        for (i in 0 until HIT_PROBES) {
            val probe = Bounds.around(((i * PROBE_STRIDE) % (WIDE_COLUMNS * STEP_X)).toDouble(), ((i * PROBE_STRIDE_Y) % (limits.maxElements / WIDE_COLUMNS * STEP_Y)).toDouble(), HIT_RADIUS)
            worst = maxOf(worst, index.query(probe).size)
        }
        report("sketch.hit_test_candidates_5000", worst.toDouble())
        assertTrue(worst <= entry("sketch.hit_test_candidates_5000").num("max"), "worst candidate set $worst of ${limits.maxElements}")
    }

    @Test
    fun import5000_should_StayUnderTheBudget() {
        val bytes = excalidrawFile(limits.maxElements).encodeToByteArray()
        val ms = bestOfMs(rounds = IO_ROUNDS, warmups = 1) {
            val result = runBlocking { ExcalidrawImporter(limits).import(ImportSource.ofBytes(bytes)) }
            assertIs<Either.Right<*>>(result, "$result")
            assertEquals(limits.maxElements, (result.value as dev.stapler.stelekit.sketch.v2.importer.ImportedScene).elements.size)
        }
        report("sketch.import_ms_5000_elements", ms, ",\"bytes\":${bytes.size}")
        assertTrue(ms <= entry("sketch.import_ms_5000_elements").num("max"), "import $ms ms")
    }

    @Test
    fun drawListBuild5000_should_StayUnderTheBudget() {
        val scene = mixedScene(limits.maxElements)
        val ms = bestOfMs(rounds = IO_ROUNDS, warmups = 1) { DrawListBuilder(metrics).build(scene, RenderStyle.Clean) }
        val ops: List<DrawOp> = DrawListBuilder(metrics).build(scene, RenderStyle.Clean)
        report("sketch.draw_list_build_ms_5000", ms, ",\"ops\":${ops.size}")
        assertTrue(ms <= entry("sketch.draw_list_build_ms_5000").num("max"), "build $ms ms")
    }

    @Test
    fun encodeDecodeAndSaveBytes5000_should_StayUnderTheirBudgets() {
        val doc = SketchDocumentV2(scene = mixedScene(limits.maxElements), sheetUuid = "bench")
        var bytes = ByteArray(0)
        val encodeMs = bestOfMs(rounds = IO_ROUNDS, warmups = 1) { bytes = SheetCodecV2.encode(doc, limits).getOrNull()!! }
        val decodeMs = bestOfMs(rounds = IO_ROUNDS, warmups = 1) {
            assertTrue(SheetCodecV2.decode(bytes, limits).isRight(), "the encoded sheet decodes")
        }
        report("sketch.encode_ms_5000", encodeMs)
        report("sketch.native_decode_ms_5000", decodeMs)
        report("sketch.save_bytes_5000", bytes.size.toDouble())
        assertTrue(encodeMs <= entry("sketch.encode_ms_5000").num("max"), "encode $encodeMs ms")
        assertTrue(decodeMs <= entry("sketch.native_decode_ms_5000").num("max"), "decode $decodeMs ms")
        assertTrue(bytes.size <= entry("sketch.save_bytes_5000").num("max"), "sheet is ${bytes.size} bytes")
        assertTrue(bytes.size <= limits.maxSheetBytes, "a 5,000-element sheet fits SceneLimits.maxSheetBytes")
    }

    private fun excalidrawFile(count: Int): String {
        val elements = (0 until count).joinToString(",") { i ->
            """{"id":"r$i","type":"rectangle","x":${(i % WIDE_COLUMNS) * STEP_X},"y":${(i / WIDE_COLUMNS) * STEP_Y},"width":$RECT_W,"height":$RECT_H,"angle":0,""" +
                """"strokeColor":"#1e1e1e","backgroundColor":"#a5d8ff","fillStyle":"solid","strokeWidth":2,"strokeStyle":"solid","roughness":1,"opacity":100,""" +
                """"groupIds":[],"frameId":null,"index":"a${i.toString(RADIX).padStart(INDEX_PAD, '0')}","roundness":null,"seed":${i + 1},"version":1,"versionNonce":1,""" +
                """"isDeleted":false,"boundElements":null,"updated":1,"link":null,"locked":false}"""
        }
        return """{"type":"excalidraw","version":2,"source":"bench","elements":[$elements],"appState":{},"files":{}}"""
    }

    private fun JsonObject.num(field: String): Double = getValue(field).jsonPrimitive.doubleOrNull ?: error("$field is not a number")

    private fun entry(key: String): JsonObject {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, BUDGETS_PATH) }.firstOrNull { it.isFile } ?: error("$BUDGETS_PATH not found above ${File("").absolutePath}")
        val entries = Json.parseToJsonElement(file.readText()).jsonObject.getValue("entries").jsonObject
        return entries[key]?.jsonObject ?: JsonObject(emptyMap())
    }

    private companion object {
        const val BUDGETS_PATH = "benchmarks/budgets/sketch-engine-budgets.json"
        val KEYS = listOf(
            "sketch.frame_ms_500_elements", "sketch.solve_ms_100_constraints", "sketch.hit_test_candidates_5000", "sketch.import_ms_5000_elements",
            "sketch.draw_list_build_ms_5000", "sketch.native_decode_ms_5000", "sketch.encode_ms_5000", "sketch.save_bytes_5000",
        )
        const val BACKGROUND = "#e8f0fe"
        const val STEP_X = 50
        const val STEP_Y = 38
        const val RECT_W = 44
        const val RECT_H = 32
        const val FRAME_ELEMENTS = 500
        const val FRAME_COLUMNS = 25
        const val WIDE_COLUMNS = 100
        const val FRAME_WIDTH = 1280
        const val FRAME_HEIGHT = 800
        const val WARMUP_FRAMES = 60
        const val FRAMES = 120
        const val PAN_X = 3.0
        const val PAN_Y = 2.0
        const val P95 = 0.95
        const val NANOS_PER_MS = 1e6
        const val ROUNDS = 5
        const val WARMUPS = 2
        const val SOLVE_ROUNDS = 5
        const val IO_ROUNDS = 5
        const val HIT_PROBES = 1_000
        const val PROBE_STRIDE = 37
        const val PROBE_STRIDE_Y = 29
        const val HIT_RADIUS = 6.0
        const val RADIX = 36
        const val INDEX_PAD = 4
        const val REFERENCE_FILL = 0xFFE8F0FE
        const val REFERENCE_STROKE = 0xFF1E1E1E
        const val REFERENCE_STROKE_WIDTH = 2f
    }
}
