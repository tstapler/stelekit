// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

/**
 * Every `Scenario.metric` key a [ProbeRecord] may carry. `scripts/bench-budget-check.js --validate`
 * reads the string literals below and fails if any lacks an entry in
 * `benchmarks/budgets/annotation-resource-budgets.json`, so a new metric cannot ship without a
 * budget and a mode. RapidPlacement and BackgroundIdle are filled by the Epic A.3 scenarios; their
 * budgets are declared here so those PRs only flip modes.
 */
object ProbeCatalog {
    val METRICS: List<String> = listOf(
        "RapidPlacement.loaderConstructions",
        "RapidPlacement.decodeCountPerPress",
        "RapidPlacement.maxDecodeBytes",
        "RapidPlacement.cacheOverflowBytes",
        "RapidPlacement.undoDepth",
        "RapidPlacement.cpuMsPerPlacement",
        "RapidPlacement.jankyPct",
        "RapidPlacement.javaRetainedMb",
        "RapidPlacement.nativeDeltaMb",
        "BackgroundIdle.idleCpuPct",
        "BackgroundIdle.sustainedCpuPct",
        "Process.survived",
        "Process.badExitReasons",
        "LibraryScroll.cacheOverflowBytes",
        "LibraryScroll.maxDecodeBytes",
        "LibraryScroll.cpuMsPerScreenful",
        "LibraryScroll.javaRetainedMb",
        "LibraryScroll.nativeDeltaMb",
        "LibraryScroll.jankyPct",
        "SwitchChurn.editorReferenceCleared",
        "SwitchChurn.heapSlopeMbPerCycle",
        "Export.exportedBitmapBytes",
        "Export.bakePeakMb",
        "SketchCanvasPan200.frameP50RatioToEmpty",
        "SketchCanvasPan200.frameP95Ms",
    )
}
