# Coverage wave D: `capture` and the remaining `git` gap (JVM row)

Source and method as in `wave-A.md`.

| File | Baseline | After (new tests alone added) |
|---|---|---|
| `capture/ActiveWriteHooks.kt` | 19/29 | 28/29 |
| `git/GitSyncService.kt` | see below | see below |

`GitSyncService.kt` changed after the baseline, so its line numbers moved. The fresh measurement is the whole `dev.stapler.stelekit.git.*` test set run under `./gradlew :kmp:jacocoTestReport :kmp:jvmTest --tests "dev.stapler.stelekit.git.*"` on the current tree. Result: `git` 3525/4021 lines = 87.7% from that test subset alone (the package grew from 3439 to 4021 JVM lines since the baseline), above the 85 target; `GitSyncService.kt` went from 266 covered / 135 missed to 301 covered / 100 missed between two runs of that same subset on the same tree, the second adding `GitSyncServiceStepRoutingTest` (+35 lines). Both are lower bounds for the whole suite. The existing `GitSyncServiceErrorRoutingTest` cases `assumeTrue` real network access (`NetworkMonitor().isOnline`) and are skipped on an offline runner; how much of the file's missed lines that explains was not measured. `GitSyncServiceStepRoutingTest` covers the same step-to-state routing through the `isOnline` seam, so it runs everywhere.

Tests: `ActiveWriteHooksTest`, `GitSyncServiceStepRoutingTest`.

## Story 11.5 (reachable-versus-unreachable summary)

Task 11.5a's summary line already exists: `scripts/ci/reachability-scan.py --check-baseline scripts/ci/reachability-baseline.txt --catalog kmp/src/businessTest/resources/wiring-catalog.txt` prints `unreferenced types: 39 (baseline 39); wired features with entry-point tests: 3 of 3` (run on this branch) and the `Reachability (baseline ratchet)` CI job runs it. The final zero-check (N=0 apart from the allowlisted set) is not met: it depends on the dispositions of Epics 3-8, which are outside this PR, so N is still the 39-type baseline.
