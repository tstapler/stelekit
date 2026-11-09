# CPU research: annotation placement path (annotation-memory-and-nav-ia)

VERIFIED = I ran it or opened the source. INFERRED = reasoning, not tested.

## Summary

- The stock Android "excessive CPU" kill cannot hit a foreground app (VERIFIED, AOSP source below). Either the app was not foreground/TOP when killed, or the OS/OEM is not stock AOSP, or the reason string was not exactly this. The owner's Logs screen line `Previous process exit: ... importance=<n> <description>` (already written by `SteleKitApplication.previousExitNotes`) answers which; ask for it.
- Emulator CPU repro did NOT reproduce a runaway: ~100 ms CPU per press with real injected touches (about 30-36 % of one core while the finger is down, 2-4 % idle, back to idle after release). Nothing loops after input stops. Root cause of the owner's kill: NOT FOUND (unverified).
- Biggest measured CPU share is main-thread recomposition/draw per pointer move; the loupe is about 30 % of that. Hoisting the ImageLoader (the earlier top suspect) changed nothing measurable.

## 1. Android semantics (VERIFIED unless marked)

- `REASON_EXCESSIVE_RESOURCE_USAGE` = 9, API 30, "killed by the system due to excessive resource usage" (https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_EXCESSIVE_RESOURCE_USAGE). `getImportance()` reports the process importance before death; `getDescription()` is human-readable, format not stable. A newer `REASON_ANOMALY` (18) exists in the current docs ("violated a system resource anomaly rule"), same page.
- AOSP source (android14-release `ActivityManagerService.checkExcessivePowerUsage`, lines ~16133-16260, fetched from android.googlesource.com): the CPU check runs only for processes with `getSetProcState() >= PROCESS_STATE_HOME` (home/cached states; TOP, FGS, service, receiver, top-sleeping are excluded), and the kill re-checks the state again before killing. Kill string: `"excessive cpu <used> during <uptimeSince> dur=<n> limit=<n>"`, reason EXCESSIVE_RESOURCE_USAGE, subreason EXCESSIVE_CPU.
- Thresholds (`ActivityManagerConstants.java`, same branch): check interval 5 min; limit 25 % of CPU time (cpu ms / uptime ms, i.e. of one core's wall time) in the first two windows after the process became "unimportant", 10 % in the third, 2 % after. So a process must burn >=25 % CPU averaged over a ~5 minute window while in the background/cached.
- Phantom (child) processes are checked by the same rule when `SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS` is on; the 32-phantom-process cap is a different kill (`PhantomProcessList`), not this reason. SteleKit spawns no child processes (INFERRED, not grepped for Runtime.exec).
- Input-dispatch ANRs show as `REASON_ANR`, not this reason. LMK shows as `REASON_LOW_MEMORY` or SIGNALED/SIGKILL.
- INFERRED: OEM builds (Samsung/Xiaomi/Oppo) add their own battery/CPU watchdogs that report REASON_OTHER or EXCESSIVE_RESOURCE_USAGE with their own description; not verifiable here. Pixel Fold AVD runs stock AOSP.
- Implication: for stock Android the sequence must be: placement burst, app leaves foreground (screen off, app switch, or the "crash" is the user pressing home), a busy loop keeps running in the background at >25 % of a core for ~5 min (or one window with doCpuKills), kill. What could keep burning CPU after backgrounding: an unbounded coroutine/Flow loop in a long-lived scope (VM `scope`, `GraphLoader`, depth coordinator, BLE), a Coil decode retry loop, or a pointer gesture stuck in "pressed" so the loupe recomposes. None was found in the emulator (postIdle3s CPU = 2-4 %, same as idle).
- OR the owner means the process died within seconds of the burst; then it is not the stock check at all (INFERRED: OEM, or a different reason being paraphrased).

## 2. Static analysis of the gesture hot path (VERIFIED by reading; costs INFERRED unless a measurement is cited)

Per pointer move in DISTANCE (`AnnotationEditorScreen.kt:1052-1059`): `onLoupeOffset(lastPos)` writes `loupeOffset` (a `mutableStateOf` at `:199`). It is read at `:476` inside the Scaffold content lambda, so each move recomposes everything in that lambda: badge, banners, `TagEditorPanel`, the image Box, and the `LoupeOverlay` call. Skippable composables with equal params are skipped; the loupe re-runs fully.

Ranked suspects:

| # | Suspect | file:line | Evidence / measurement |
|---|---|---|---|
| 1 | Whole-screen recomposition from `loupeOffset` read at screen scope (every move) | Screen:199, 467, 476 | Main thread (`r.stelekit.test` = UI thread) is 70-75 % of process CPU in every run. Fix: read the offset inside the loupe (pass a `State`/lambda, use `Modifier.offset { }` / `graphicsLayer` reading state in layout phase). Confirm: Layout Inspector recomposition counts. |
| 2 | Loupe composition and draw per move (`LoupeOverlay`, 3x canvas `requiredSize` image inside `clip(CircleShape)`) | Screen:1610-1684 | Measured: loupe disabled (mode 2) -> 99 ms CPU/press vs 142 ms with loupe (direct injection), main 7.6 s vs 10.2 s, RenderThread 1.03 s vs 1.57 s per 100 presses. About 30 % of the cost. |
| 3 | Per-frame draw cost scales with committed annotations: `CommittedAnnotationsCanvas` (`points.map`, new lists per draw), `MeasurementLabelOverlay` (`textMeasurer.measure` AND `drawText` per label per draw, allocs `TextStyle` per label), `AnnotationSemanticOverlay` | Screen:896-915, MeasurementLabelOverlay.kt:51-118 | Harness run: CPU per 25 presses rose 13.9 s -> 17.4 -> 19.2 -> 19.9 s as committed grew 0 -> 51 (+43 %). Linear in n, not runaway. |
| 4 | New `ImageLoader` per press (`rememberSteleKitImageLoader` inside loupe) | SteleKitAssetFetcher.kt:~112, Screen:1648 | Measured A/B: hoisted loader 792 ms/press vs baseline 705 vs no-loupe 827 (harness runs, noise ~±10 %). No effect. Still a leak-smell; cheap to hoist. |
| 5 | SELECT drag: `updateAnnotationPoints` per move maps the full committed list and recomputes the measurement; `hitTestAnnotations` once per down | VM:687-700, Screen:1004-1046 | Direct injection: SELECT with drag costs 86 ms/press, less than DISTANCE (annotation count 10 vs up to 51). No hotspot. |
| 6 | State writes: `addPoint` and `commitAnnotation` copy `AnnotationEditorState` once or twice per press; `pushUndo` retains <=50 states | VM:365-402, 508-587 | Per press, not per move; negligible (no DefaultDispatcher CPU: thread group "DefaultDispatch" 10-30 ms total). |
| 7 | Persistence: `saveMeasurementAnnotation` per commit on `Dispatchers.Default` | VM:574 | NOT measured with real SQLDelight (repro used in-memory repo). Open gap. |
| 8 | Haptics per commit, depth coordinator, BLE flows | VM:564, 235-260 | Not enabled in the repro (no-op haptic, null estimator). Open gap. Depth estimation is an obvious candidate for sustained CPU if it runs. |
| 9 | Loops: Coil retries, collectAsState loops | n/a | No evidence; idle CPU after runs 2-4 %. |

## 3. Empirical results (VERIFIED; Pixel_Fold AVD, Android 14, 4000x3000 JPEG, DISTANCE tool unless noted)

Method: throwaway instrumented test (appendix); CPU from `Process.getElapsedCpuTime()` and per-thread `/proc/self/task/*/stat` utime+stime (10 ms ticks; UI thread comm is truncated to `r.stelekit.test`, the test driver thread is `roidJUnitRunner`); frames from `Choreographer`. Each press: down, 20 moves 8 ms apart, 50 ms hold, up (about 0.2 s). Caveat: the emulator uses software-ish GL, so RenderThread cost is not representative of a phone GPU.

Important: Compose test `performTouchInput` inflates CPU roughly 5-7x (it syncs/advances the frame clock per event). The first table is the harness; the second is the trustworthy one (events dispatched straight to the Compose view with `dispatchTouchEvent`).

Harness (`performTouchInput`), 100 presses:

| Run | CPU ms/press | CPU % of 1 core | main ms | RenderThread ms | frames>32ms |
|---|---|---|---|---|---|
| DISTANCE | 705 | 71 | 39 360 | 20 560 | 4 |
| DISTANCE, loader hoisted | 792 | 73 | 49 770 | 17 100 | 26 |
| DISTANCE, no loupe | 827 | 72 | 50 380 | 20 170 | 177 |
| SELECT (10 pre-placed, vertex drags) | 829 | 59 | 46 770 | 24 130 | 485 (314 in first 25 = JIT warm-up) |

Sustained 20 s continuous drag (harness): DISTANCE 84 % of a core (main 8.8 s, RenderThread 6.0 s), 4 janky frames, 60 fps; no-loupe 75 %; hoisted 80 %. The SELECT sustained drag did not start on a vertex, so it measured nothing useful (57 %, 0 jank) - not valid.

Direct injection (realistic), 100 presses:

| Run | CPU ms/press | CPU % of 1 core | main ms/100 | RenderThread ms/100 | janky frames (>32 ms) / total | max frame ms |
|---|---|---|---|---|---|---|
| DISTANCE | 142 | 36 | 10 170 | 1 570 | 171 / 1 894 | 399 |
| DISTANCE, no loupe | 99 | 30 | 7 640 | 1 030 | 103 / 1 818 | 83 |
| SELECT drag | 86 | 26 | 5 730 | 1 080 | 90 / 1 789 | 233 |

Other VERIFIED facts: idle CPU 2-4 %; 3 s after the last press CPU is again 2-4 % (no leftover loop); per-thread share: main 70-75 %, RenderThread 11-16 %, JIT 10-13 % (warm-up), test driver 5 %; `DefaultDispatcher-worker` threads <1 %, no Coil thread appears; `getHistoricalProcessExitReasons` was empty after every run (no kill); CPU per press is flat/slightly rising, no monotone leak. Jank is concentrated in the first presses (JIT) and is higher with the loupe.

Not tested: real phone GPU, hardware bitmaps, real SQLDelight, depth estimator, BLE, 48 MP, HEIC/SAF sources, a backgrounded app.

## 4. CPU benchmark design

Metrics (per scenario: place 100 points with drag-to-aim; SELECT drag x50; 20 s continuous drag; background 5 min after a burst):

| Metric | Source | Gate? |
|---|---|---|
| CPU ms per placement (process CPU delta / presses, direct injection) | `Process.getElapsedCpuTime` | Report-only at first, then hard gate at ~2x calibrated median (baseline ~140 ms on CI emulator; calibrate over >=5 CI runs) |
| Main-thread CPU ms per placement | `/proc/self/task/<tid>/stat` of main tid | report-only |
| Sustained CPU % of one core while dragging 20 s | CPU delta / wall | hard gate < 60 % (baseline 36 %) |
| Post-gesture idle CPU % (3 s then 60 s) | same | HARD gate < 10 % (this is the only metric tied to the OS rule; the kill needs >=25 % sustained in background) |
| p95 / p99 frame time, janky frame % | `FrameMetrics` (`Window.addOnFrameMetricsAvailableListener`), or Macrobenchmark `FrameTimingMetric` | report-only (emulator noise; first presses are JIT) |
| CPU growth vs committed annotations (ms per press at n=10 vs n=50) | slope of per-25-press blocks | report-only, regression flag if slope doubles |
| Exit reasons after run | `getHistoricalProcessExitReasons` | HARD gate: no EXCESSIVE_RESOURCE_USAGE/ANR/LOW_MEMORY |
| Recompositions per second | not cheaply measurable in the test; add a debug-only composition counter via `Modifier.onGloballyPositioned`-free `SideEffect` counter behind a `@VisibleForTesting` hook, or Layout Inspector manually | report-only |

Harness: reuse `androidInstrumentedTest` with direct `dispatchTouchEvent` injection (do NOT use `performTouchInput` for CPU numbers; it multiplies CPU 5-7x). Existing hosts: `.github/workflows/android-benchmark.yml` plus the `connectedDebugAndroidTest` job on API 30 x86_64 (from requirements). Add the sampler (appendix `snap()`/`threadCpu()`) as a small test utility, run warm-up of 3 presses, report per-thread table in the assertion/`additionalTestOutputDir`. For frame metrics prefer a Macrobenchmark module later (needs a separate benchmark build type; not present). Both emulator and device numbers should be recorded; budgets are calibrated on the CI emulator only.

Production diagnosis without logcat: `SteleKitApplication.previousExitNotes` (androidApp/.../SteleKitApplication.kt:166-175) already logs reason name (including EXCESSIVE_RESOURCE_USAGE, :182), timestamp, importance, rss and description into the Logs screen via `PreviousSessionLog.replay`. Gaps to add: `getStatus()`, `getPss()`, import name (IMPORTANCE_FOREGROUND vs CACHED, which decides which kill rule could apply), and ask for 10 entries not 3. Add a cheap in-process CPU watchdog logging to `AndroidFileLogSink`: every 10 s write `cpuMs` delta, per-thread top 3 (same `/proc/self/task` parse) and whether the app is foreground, so `previous.log` shows what burned CPU just before death. Also call `ActivityManager.setProcessStateSummary(...)` with the last UI state (e.g. "annotate:tool=DISTANCE,loupe=1") so the exit record carries it (API 30; `getProcessStateSummary`).

## 5. Recommended next steps

1. Ask the owner for the Logs-screen line (`importance=` and description) and whether the app was on screen when it died. This decides between "foreground burst" (not stock AOSP) and "background busy loop".
2. Fix the cheap, measured costs regardless: read `loupeOffset` inside the loupe (layout/draw phase), cache measured label layouts, avoid `map` allocations in draw.
3. Extend the repro to cover real SQLDelight, depth estimator enabled, and an app-backgrounded-after-burst phase with a 5 min idle CPU check (the only configuration that can trigger the stock kill).

## Appendix: throwaway test (deleted from tree)

Saved copy: `/home/tstapler/.cache/tmp/AnnotCpuRepro.kt.txt`. A/B scratch edit (a `CPU_AB_MODE` flag in the screen: 1 = hoisted loader passed to `LoupeOverlay`, 2 = loupe not composed) was reverted; diff in `/home/tstapler/.cache/tmp/cpu/ab.diff`. Source:

```kotlin
package dev.stapler.stelekit

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.AnnotationTool
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class AnnotCpuRepro {
    @get:Rule val rule = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() { SteleKitContext.init(context) }

    private fun writeImage(w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(); val r = Random(1)
        for (i in 0 until 400) {
            p.color = Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            val x = r.nextInt(w); val y = r.nextInt(h)
            c.drawRect(x.toFloat(), y.toFloat(), (x + r.nextInt(w / 4)).toFloat(), (y + r.nextInt(h / 4)).toFloat(), p)
        }
        val f = File(context.cacheDir, "cpurepro-${w}x$h.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return f
    }

    // per-thread cpu ticks (10 ms) keyed by thread-group name
    private fun threadCpu(): Map<String, Long> {
        val m = HashMap<String, Long>()
        File("/proc/self/task").listFiles()?.forEach { t ->
            try {
                val s = File(t, "stat").readText()
                val name = s.substringAfter('(').substringBeforeLast(')').replace(Regex("[-_ ]?\\d+$"), "")
                val f = s.substringAfterLast(')').trim().split(' ')
                val ticks = f[11].toLong() + f[12].toLong()
                m[name] = (m[name] ?: 0L) + ticks
            } catch (_: Exception) {}
        }
        return m
    }

    class Frames { var n = 0; var jank = 0; var last = 0L; var maxMs = 0L
        val cb = object : Choreographer.FrameCallback { override fun doFrame(t: Long) {
            if (last != 0L) { val d = (t - last) / 1_000_000; if (d > 32) jank++; if (d > maxMs) maxMs = d }
            last = t; n++; Choreographer.getInstance().postFrameCallback(this) } } }

    private class Snap(val cpuMs: Long, val wall: Long, val threads: Map<String, Long>)
    private fun snap() = Snap(Process.getElapsedCpuTime(), SystemClock.elapsedRealtime(), threadCpu())
    private fun diff(tag: String, a: Snap, b: Snap, n: Int, fr: Frames?): String {
        val cpu = b.cpuMs - a.cpuMs; val wall = b.wall - a.wall
        val top = b.threads.map { (k, v) -> k to (v - (a.threads[k] ?: 0L)) * 10 }.filter { it.second > 0 }.sortedByDescending { it.second }.take(8)
        return "$tag n=$n procCpuMs=$cpu wallMs=$wall cpuPerUnitMs=${"%.1f".format(cpu.toDouble() / n)} cpuPct=${"%.0f".format(100.0 * cpu / wall)} frames=${fr?.n} jank32=${fr?.jank} maxFrameMs=${fr?.maxMs} threads(ms)=$top\n"
    }

    private fun exits(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 5).joinToString { "reason=${it.reason} desc=${it.description}" }
    }

    private fun setup(select: Boolean): Triple<AnnotationEditorViewModel, ImageAnnotation, () -> androidx.compose.ui.unit.IntSize> {
        val file = writeImage(4000, 3000)
        val img = ImageAnnotation(
            uuid = "img-cpu", blockUuid = "blk", pageUuid = "pg", graphPath = "/test",
            filePath = "file://${file.absolutePath}",
            calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
            unit = MeasurementUnit.METERS,
        )
        val vm = AnnotationEditorViewModel(measurementRepository = InMemoryMeasurementAnnotationRepository()).also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        if (select) {
            val r = Random(3)
            repeat(10) {
                vm.addPoint(NormalizedPoint(0.1 + 0.8 * r.nextDouble(), 0.1 + 0.8 * r.nextDouble()))
                vm.addPoint(NormalizedPoint(0.1 + 0.8 * r.nextDouble(), 0.1 + 0.8 * r.nextDouble()))
            }
            vm.selectTool(AnnotationTool.SELECT)
        }
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }
        val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
        rule.waitUntil(timeoutMillis = 30_000) { val s = canvas.fetchSemanticsNode().size; s.height > 0 && s.width > 0 }
        Thread.sleep(2000); rule.waitForIdle()
        return Triple(vm, img) { canvas.fetchSemanticsNode().size }
    }

    private fun scenario(label: String, select: Boolean, presses: Int, sustainedSec: Int): String {
        val (vm, _, sizeOf) = setup(select)
        val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
        val fr = Frames()
        rule.runOnUiThread { Choreographer.getInstance().postFrameCallback(fr.cb) }
        val rnd = Random(7)
        val sb = StringBuilder("== $label select=$select\n")
        fun gesture(down: Offset, target: Offset, moves: Int) {
            canvas.performTouchInput { down(down) }
            for (k in 1..moves) {
                val f = k / moves.toFloat()
                val p = Offset(down.x + (target.x - down.x) * f, down.y + (target.y - down.y) * f)
                canvas.performTouchInput { moveTo(p) }
                Thread.sleep(8)
            }
            Thread.sleep(50)
            canvas.performTouchInput { up() }
            rule.waitForIdle()
        }
        fun pressOnce() {
            val s = sizeOf()
            if (select) {
                val a = vm.state.value.committedAnnotations[rnd.nextInt(10)]
                val v = a.normalizedPoints[0]
                val st = Offset((v.x * s.width).toFloat(), (v.y * s.height).toFloat())
                gesture(st, Offset(s.width * (0.1f + 0.8f * rnd.nextFloat()), s.height * (0.1f + 0.8f * rnd.nextFloat())), 20)
            } else {
                val st = Offset(s.width * (0.1f + 0.8f * rnd.nextFloat()), s.height * (0.1f + 0.8f * rnd.nextFloat()))
                gesture(st, Offset(s.width * (0.1f + 0.8f * rnd.nextFloat()), s.height * (0.1f + 0.8f * rnd.nextFloat())), 20)
            }
        }
        repeat(3) { pressOnce() } // warmup
        // idle baseline 3s
        val i0 = snap(); Thread.sleep(3000); sb.append(diff("idle3s", i0, snap(), 1, null))
        val f0 = Frames(); fr.n = 0; fr.jank = 0; fr.maxMs = 0
        val a = snap()
        for (i in 1..presses) {
            pressOnce()
            if (i % 25 == 0) sb.append(diff("presses@$i", a, snap(), i, fr))
        }
        // post-run idle: does CPU keep burning after input stops?
        val p0 = snap(); Thread.sleep(3000); sb.append(diff("postIdle3s", p0, snap(), 1, null))
        // sustained continuous drag
        fr.n = 0; fr.jank = 0; fr.maxMs = 0; fr.last = 0
        val s0 = snap()
        val sz = sizeOf()
        canvas.performTouchInput { down(Offset(sz.width * 0.3f, sz.height * 0.3f)) }
        val end = SystemClock.elapsedRealtime() + sustainedSec * 1000L
        var t = 0.0
        while (SystemClock.elapsedRealtime() < end) {
            t += 0.05
            val p = Offset(sz.width * (0.5f + 0.35f * Math.cos(t).toFloat()), sz.height * (0.5f + 0.35f * Math.sin(t).toFloat()))
            canvas.performTouchInput { moveTo(p) }
            Thread.sleep(8)
        }
        sb.append(diff("sustainedDrag", s0, snap(), 1, fr))
        canvas.performTouchInput { up() }
        rule.waitForIdle()
        sb.append("committed=${vm.state.value.committedAnnotations.size}\nexitReasons=${exits()}\n")
        return sb.toString()
    }

    @Test fun distance() { throw AssertionError("REPORT\n" + scenario("DISTANCE", false, 100, 20)) }
    @Test fun distanceHoisted() { dev.stapler.stelekit.ui.annotate.CPU_AB_MODE = 1; throw AssertionError("REPORT\nMODE1 hoisted loader\n" + scenario("DISTANCE", false, 100, 20)) }
    @Test fun distanceNoLoupe() { dev.stapler.stelekit.ui.annotate.CPU_AB_MODE = 2; throw AssertionError("REPORT\nMODE2 no loupe\n" + scenario("DISTANCE", false, 100, 20)) }
    private fun direct(mode: Int, select: Boolean): String {
        dev.stapler.stelekit.ui.annotate.CPU_AB_MODE = mode
        val (vm, _, sizeOf) = setup(select)
        val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
        val view = rule.onRoot().fetchSemanticsNode().root as android.view.View
        val pos = canvas.fetchSemanticsNode().positionInRoot
        val fr = Frames(); rule.runOnUiThread { Choreographer.getInstance().postFrameCallback(fr.cb) }
        val rnd = Random(7); val sb = StringBuilder("== DIRECT mode=$mode select=$select\n")
        fun ev(action: Int, x: Float, y: Float, down: Long) {
            val e = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pos.x + x, pos.y + y, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            view.post { view.dispatchTouchEvent(e); e.recycle() }
        }
        fun gesture(a: Offset, b: Offset) {
            val d = SystemClock.uptimeMillis()
            ev(android.view.MotionEvent.ACTION_DOWN, a.x, a.y, d)
            for (k in 1..20) { val f = k / 20f; ev(android.view.MotionEvent.ACTION_MOVE, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, d); Thread.sleep(8) }
            Thread.sleep(50)
            ev(android.view.MotionEvent.ACTION_UP, b.x, b.y, d)
            Thread.sleep(30)
        }
        fun press() {
            val s = sizeOf()
            val st = if (select) { val v = vm.state.value.committedAnnotations[rnd.nextInt(10)].normalizedPoints[0]; Offset((v.x * s.width).toFloat(), (v.y * s.height).toFloat()) }
                else Offset(s.width * (0.1f + 0.8f * rnd.nextFloat()), s.height * (0.1f + 0.8f * rnd.nextFloat()))
            gesture(st, Offset(s.width * (0.1f + 0.8f * rnd.nextFloat()), s.height * (0.1f + 0.8f * rnd.nextFloat())))
        }
        repeat(3) { press() }
        val a = snap(); fr.n = 0; fr.jank = 0; fr.maxMs = 0; fr.last = 0
        for (i in 1..100) { press(); if (i % 50 == 0) sb.append(diff("presses@$i", a, snap(), i, fr)) }
        sb.append("committed=${vm.state.value.committedAnnotations.size}\nexitReasons=${exits()}\n")
        return sb.toString()
    }
    @Test fun directDistance() { throw AssertionError("REPORT\n" + direct(0, false)) }
    @Test fun directDistanceNoLoupe() { throw AssertionError("REPORT\n" + direct(2, false)) }
    @Test fun directSelect() { throw AssertionError("REPORT\n" + direct(0, true)) }
    @Test fun select() { throw AssertionError("REPORT\n" + scenario("SELECT", true, 100, 20)) }
}
```
