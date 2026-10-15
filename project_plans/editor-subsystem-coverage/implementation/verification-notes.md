# Verification notes: editor-subsystem-coverage

Evidence log for the acceptance criteria. Each entry records the command and what it printed.

## T1.1a — Pre-change inventory and AC-1 baseline (VERIFIED, branch at 826fdf7a)

```
find kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor -type f -not -path '*/commands/*' | wc -l   -> 26 (24 .kt, 2 .md)
find .../editor/commands -type f | wc -l                                                             -> 6
find .../editor -type f | wc -l                                                                      -> 32
```

AC-1 grep, `stelekit\.editor\.<pkg>\b` over `kmp androidApp benchmarks` (`*.kt *.kts *.xml *.pro *.bzl BUILD.bazel`)
excluding `/stelekit/editor/` — match count per package:

```
persistence 0 | viewmodel 0 | text 0 | state 0 | blocks 0 | input 0 | format 0
```

Only importers of `stelekit.editor.*` outside `editor/`: `ui/StelekitViewModel.kt`,
`ui/CommandManagerCoordinator.kt` (both `editor.commands.*`), `businessTest/.../AllBusinessTests.kt`
(the two keeper tests), and `jvmTest/.../CommandPaletteFocusRetentionTest.kt` (fully qualified `editor.commands.*`).

Host pre-flight: 61 GB RAM, 13 GB available, 3 Gradle daemons, 1 Bazel server.
`scripts/jvm-display-check.sh`: `DISPLAY=:1`, Wayland session, "real X11 display reachable — running directly".

## T1.1b — Baseline `bazel test //kmp:business_tests` (pre-change tree, 826fdf7a)

Requires `export ANDROID_HOME=$HOME/Android/Sdk` in a worktree (no `local.properties`).

| Run | Command | Result |
|---|---|---|
| 1 | `bazel test //kmp:business_tests` (no `ANDROID_HOME`) | Analysis error `androidsdk//:fail`; no tests ran. Cause: `ANDROID_HOME` unset (`.bazelrc:40` passes it via `--repo_env`). Not a baseline. |
| 2 | same, `ANDROID_HOME` set, 750s cold | `FAILED` in 157.8s: 1376 run, 1 failure — `GraphRelocationCoordinatorFlagClearingTest."MoveInProgressFlag should BeCleared When QuiesceTimesOut"`: `UncaughtExceptionsBeforeTest` (an exception leaked from an earlier test in the aggregated suite; log shows `no such table: main.blocks` and `ClassNotFoundException: android.os.Build$VERSION` on `DefaultDispatcher` workers). |
| 3 | same + `--cache_test_results=no` | `PASSED` in 157.8s: 1376 run, 0 failures (`Executed 1 out of 1 test: 1 test passes`). |

Reading: the run-2 failure is order/timing-dependent and unrelated to `editor/` (the test file has 0 references to
`stelekit.editor`; last touched by #327). VERIFIED that it did not reproduce on the identical tree; its root cause
(which earlier test leaks the exception) is NOT established and is out of scope for this item.
Baseline for AC-2/AC-4: **1376 tests, 0 failures (run 3)**, with one known flake on this host. Any post-change
failure must be compared against this test and re-run before being attributed to the deletion.

## T1.1c — JaCoCo feasibility spike (VERIFIED, pre-change tree)

```
ANDROID_HOME=$HOME/Android/Sdk timeout 40m scripts/jvm-display-check.sh -- \
  ./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.editor.LinkInsertionTest' :kmp:jacocoTestReport
-> BUILD SUCCESSFUL in 40s; report at kmp/build/reports/jacoco/jacoco.xml (12.5 MB)
```

Feasible with a display (`DISPLAY=:1`). The filter ran only `LinkInsertionTest`, which touches no production code, so every
covered counter is 0. This run measures the **denominators**, not a coverage baseline:

| package (`dev/stapler/stelekit/editor/...`) | BRANCH covered/total | LINE covered/total |
|---|---|---|
| `commands` (live) | 0/132 | 0/777 |
| `persistence` | 0/753 | 0/1918 |
| `text` | 0/396 | 0/928 |
| `blocks` | 0/274 | 0/553 |
| `input` | 0/232 | 0/318 |
| `(root)` | 0/140 | 0/262 |
| `viewmodel` | 0/131 | 0/452 |
| `format` | 0/104 | 0/165 |
| `state` | 0/70 | 0/302 |
| **whole `editor/`** | **0/2096** | **0/5675** |

- AC-3 target: `editor.commands` ≥ 30% of 132 branches = **≥ 40 covered**.
- The item's whole-`editor` figures (385/5672 lines, 10/2230 branches) came from a different commit; denominators here are
  5675 lines / 2096 branches. The item's pre-change `editor.commands` figure is therefore NOT re-measured under the full suite;
  only the filtered 0/132 above is.
- Plan questions resolved: JaCoCo runs from Gradle with a display (answered); whether data-class generated branches inflate the
  denominator is still open and is read from per-class counters after the new tests run.

## T1.2 — Deletion and compile proof (VERIFIED)

`git rm` of 26 explicit paths (24 `.kt` + `persistence/README.md` + `state/README.md`). `git ls-files .../editor | wc -l` = **6**
(`commands/` only); 26 staged deletions; `editor/format/` absent.

| Check | Command | Result |
|---|---|---|
| JVM + Android + wasmJs compile | `./gradlew :kmp:compileKotlinJvm :kmp:compileDebugKotlinAndroid :kmp:compileKotlinWasmJs --continue` | `BUILD SUCCESSFUL in 1m 34s`, no unresolved references |
| Bazel main lib | `bazel build //kmp/src/jvmMain/kotlin:jvm_main_lib` | `Build completed successfully`, exit 0 |
| Business tests (AC-2) | `bazel test //kmp:business_tests --cache_test_results=no` | `PASSED in 158.7s`, 1376 tests, 0 failures (baseline run 3: 1376, 0) |
| AC-1 grep, 7 packages | `grep -rnE "stelekit\.editor\.<pkg>\b" kmp androidApp benchmarks ...` | 0 for each of persistence, viewmodel, text, state, blocks, input, format |
| AC-5 | `test ! -e` both format files and the directory | OK |
| detekt | `./gradlew :kmp:detekt` after removing 10 baseline `<ID>` lines | `BUILD SUCCESSFUL in 41s` |

- Root-symbol census (same-package use needs no import): one name collision, `CursorState`, but it is a distinct live class
  at `model/CursorState.kt`. The compile above is the proof that no code resolved to the deleted `editor.CursorState`.
- iOS is not compiled locally (needs macOS); left to CI `ci-ios.yml`.
- Docs: `docs/reference/hotspot-ranking.md` row removed, `kmp/README.md:34` updated. `docs/tasks/*` and
  `docs/archive/tasks/*` cite deleted files as historic specs and are left as is.

## T2 — `editor.commands` tests and coverage (AC-3, VERIFIED)

New classes in `kmp/src/businessTest/kotlin/dev/stapler/stelekit/editor/commands/`, registered in `AllBusinessTests.kt`
(import + `@SuiteClasses`): `CommandManagerTest` (23), `CommandSystemRegistryTest` (14), `EssentialCommandsTest` (15).

```
ANDROID_HOME=$HOME/Android/Sdk scripts/jvm-display-check.sh -- \
  ./gradlew :kmp:jvmTest --tests 'dev.stapler.stelekit.editor.commands.*' :kmp:jacocoTestReport
-> BUILD SUCCESSFUL in 57s; junit XML: 52 tests, 0 failures, 0 errors, 0 skipped
```

| Package `dev/stapler/stelekit/editor/commands` | covered/total |
|---|---|
| BRANCH | **99/132 = 75.0%** (target ≥ 40 = 30%) |
| LINE | 700/777 = 90.1% |
| METHOD | 121/141 = 85.8% |

- Source: only the three new classes (filter excludes `CommandPaletteFocusRetentionTest`); no value-semantics fallback test
  was added, so the figure is behavioural. Report file mtime 19:11 matches the run (not the stale spike report).
- Pre-change: 0/132 under the same filter (T1.1c). The item's full-suite pre-change figure was not re-measured.
- Remaining partial branches are the `catch (e: CancellationException) { throw e }` arms in each command lambda and
  `CommandManager`'s outer catch arms, which no essential command can reach (plan: accepted unreachable).

### Failures found while writing the tests (both were test-harness errors, not production bugs)
1. `compileTestKotlinJvm` failed: `assertEquals(listOf(Triple(.., 3000L)), events)` could not infer `T` against
   `Triple<…, Long?>`. Fixed by building expected values with a `Long?`-typed helper.
2. 9 `CommandManagerTest` cases failed with `Command not found` / empty `getAvailableCommands()`. Cause:
   `CommandManager.init` seeds the registry with `scope.launch`; I passed `backgroundScope`, and `advanceUntilIdle()` does
   not run background-scope work. Fixed by passing the `TestScope` itself (seeding completes, so `runTest` is not blocked).
   No production file was changed.

## T3 — AC-4: no new failures (VERIFIED)

| Run | Tree | Command | Result |
|---|---|---|---|
| business_tests | current (d5f31954) | `bazel test //kmp:business_tests --cache_test_results=no` | `PASSED in 154.9s` (new classes registered; baseline was 1376 tests, 0 failures) |
| jvm_tests (display `:1`) | current | `scripts/jvm-display-check.sh -- bazel test //kmp/src/jvmTest/kotlin:jvm_tests ...` | `FAILED`: 769 testcases, 11 failing |
| jvm_tests | pre-change 826fdf7a, throwaway worktree | same command | `FAILED`: 769 testcases, 11 failing |

`diff` of the sorted failing `classname :: name` lines, baseline vs current: **identical** (11 each). Failing classes:
`BacklinkRenamerTest` (7), `GraphLoaderTest` (2), `GraphLoadTimingTest` (1), `DiskConflictResolutionTest` (1).

- Cause (VERIFIED from the failure messages): `Read-only file system` when tests create temp dirs under `/home/tstapler/...`
  inside Bazel's linux-sandbox; they pass 100/100 under Gradle on the current tree
  (`./gradlew :kmp:jvmTest --tests '*BacklinkRenamerTest' ... -> BUILD SUCCESSFUL`). Pre-existing and unrelated to `editor/`.
- jvm_tests has no new tests, so 769 = 769 is expected; the new classes run in `business_tests`.
- The `bazel-ci.yml` PR job runs `//kmp:jvm_tests` under `xvfb-run`; whether CI's sandbox has a writable `$HOME` is not checked here.
- Earlier flake (T1.1b run 2, `GraphRelocationCoordinatorFlagClearingTest`) did not recur in any later business_tests run.
