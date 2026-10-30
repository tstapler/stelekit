# Coverage baseline, 2026-10 (Story 0.1)

First full (not change-scoped) JaCoCo run. Every figure below comes from the commands in this file; nothing is estimated. Raw artifacts (`jacoco.xml`, `jacoco-android.xml`, `runtime.json`, `totals.json`) are produced by `scripts/ci/coverage-run.sh` and uploaded by `.github/workflows/coverage-baseline.yml`.

Run date: 2026-10-27, commit `622867638f` (origin/main), local machine (Manjaro, 24 cores, other agents running concurrently).

## Command

```
ANDROID_HOME=$HOME/Android/Sdk scripts/ci/coverage-run.sh <outdir>
  jvm tier:     ./gradlew --no-daemon :kmp:jvmTest --continue ; ./gradlew --no-daemon :kmp:jacocoTestReport -x :kmp:jvmTest
  android tier: ./gradlew --no-daemon :kmp:testDebugUnitTest --continue ; ./gradlew --no-daemon :kmp:jacocoAndroidTestReport -x :kmp:testDebugUnitTest
```

`jvmTest` already contains the `businessTest` and `commonTest` source sets (kmp/build.gradle.kts), so one task covers all three.

## Totals (from the report-level counters of the two XMLs)

| Tier | Lines | Branches | Methods | Classes |
|---|---|---|---|---|
| JVM (`jacoco.xml`) | 46,237/70,152 = 65.9% | 26,353/64,202 = 41.0% | 11,385/19,855 = 57.3% | 3,096/4,658 = 66.5% |
| Robolectric (`jacoco-android.xml`) | 23,144/71,369 = 32.4% | 16,177/65,000 = 24.9% | 5,865/19,997 = 29.3% | 1,607/4,691 = 34.3% |

### Stale figures replaced

| | Stale (change-scoped runs, 2026-10-16, research) | This baseline (full run, 2026-10-27) |
|---|---|---|
| JVM lines | 23,489/69,331 = 33.9% | 46,237/70,152 = 65.9% |
| Android lines | 8,479/70,498 = 12.0% | 23,144/71,369 = 32.4% |

The stale figures were partial runs; the denominators moved by about 800 lines (JVM) and 900 lines (Android) since, so the comparison is of method, not a regression history.

## Runtime and test counts

| Tier | Wall-clock | Peak RSS (see caveat) | Tests (from `kmp/build/test-results/**/TEST-*.xml`) |
|---|---|---|---|
| `jvmTest` + `jacocoTestReport` | 864 s | 10871 MB | 9,011 tests in 1,294 result files, 0 failures, 0 errors, 62 skipped |
| `testDebugUnitTest` + `jacocoAndroidTestReport` | 80 s | 8122 MB | 2,717 tests in 352 result files, 0 failures, 0 errors, 3 skipped |

Caveats:
- **No display.** This machine has no X11 display and no `xvfb-run` (`scripts/jvm-display-check.sh` exit 1), so the JVM tier ran UI tests without a display. There were zero failures, but whether the 62 skipped tests include display-gated UI tests was not inspected, so UI-package coverage in the JVM figure (`ui`, `desktop`) may be understated. The nightly CI job installs Xvfb; its figures supersede these for `ui`. INFERRED until then.
- **Peak RSS is an upper bound.** The sampler sums every Gradle/Kotlin java process on the host, and other agents' Gradle builds were running concurrently. Treat it as "not more than", not as this build's footprint. The CI run on an otherwise idle runner gives the usable number.
- The Android tier ran in 80 s because compilation was already warm from the JVM tier; a cold CI run will be longer.

## Per-package line and branch coverage

Package = first path segment under `dev.stapler.stelekit`. Sorted by JVM line count.

| Package | JVM lines | JVM branch | Android lines | Android branch |
|---|---|---|---|---|
| ui | 17107/32581 (52.5%) | 31.1% | 11369/32737 (34.7%) | 22.1% |
| db | 8353/11318 (73.8%) | 64.1% | 1225/11366 (10.8%) | 13.1% |
| repository | 2998/4223 (71.0%) | 46.3% | 568/4160 (13.7%) | 8.3% |
| git | 2839/3443 (82.5%) | 70.3% | 2457/4323 (56.8%) | 48.3% |
| merge | 2396/2574 (93.1%) | 77.4% | 796/2580 (30.9%) | 29.8% |
| platform | 1289/1894 (68.1%) | 55.0% | 1286/3632 (35.4%) | 31.1% |
| capture | 1072/1333 (80.4%) | 54.9% | 34/687 (4.9%) | 4.0% |
| performance | 884/1259 (70.2%) | 49.7% | 181/1338 (13.5%) | 4.6% |
| parsing | 888/918 (96.7%) | 91.2% | 888/918 (96.7%) | 90.9% |
| editor | 700/777 (90.1%) | 75.0% | 381/777 (49.0%) | 7.6% |
| mcp | 519/703 (73.8%) | 50.2% | - | - |
| transfer | 659/697 (94.5%) | 78.7% | 537/696 (77.2%) | 59.0% |
| vault | 589/649 (90.8%) | 63.5% | 47/606 (7.8%) | 10.5% |
| export | 519/641 (81.0%) | 63.5% | 498/662 (75.2%) | 56.8% |
| domain | 544/621 (87.6%) | 73.9% | 465/613 (75.9%) | 65.3% |
| migration | 483/604 (80.0%) | 65.7% | 130/604 (21.5%) | 23.4% |
| llm | 539/572 (94.2%) | 77.1% | 64/580 (11.0%) | 2.4% |
| voice | 474/537 (88.3%) | 81.9% | 20/842 (2.4%) | 0.7% |
| model | 429/529 (81.1%) | 71.5% | 401/529 (75.8%) | 68.3% |
| asset | 279/499 (55.9%) | 48.2% | 20/499 (4.0%) | 0.0% |
| tags | 298/326 (91.4%) | 71.2% | 0/326 (0.0%) | 0.0% |
| error | 250/309 (80.9%) | 74.8% | 250/259 (96.5%) | 80.6% |
| parser | 235/266 (88.3%) | 81.2% | 204/266 (76.7%) | 73.4% |
| diagnostics | 170/226 (75.2%) | 51.1% | 57/226 (25.2%) | 25.0% |
| outliner | 201/220 (91.4%) | 79.6% | 201/220 (91.4%) | 79.6% |
| desktop | 19/217 (8.8%) | 4.1% | - | - |
| query | 201/215 (93.5%) | 62.8% | 111/214 (51.9%) | 51.4% |
| util | 205/211 (97.2%) | 85.7% | 205/211 (97.2%) | 83.9% |
| cli | 152/204 (74.5%) | 60.6% | - | - |
| stats | 195/196 (99.5%) | 87.6% | 181/181 (100.0%) | 93.1% |
| command | 0/154 (0.0%) | 0.0% | 0/154 (0.0%) | 0.0% |
| benchmarks | 0/147 (0.0%) | 0.0% | - | - |
| calibration | 119/138 (86.2%) | 54.4% | 119/138 (86.2%) | 54.4% |
| docs | 108/137 (78.8%) | 100.0% | 0/137 (0.0%) | 0.0% |
| service | 69/126 (54.8%) | 42.7% | 96/187 (51.3%) | 30.9% |
| search | 45/122 (36.9%) | 43.8% | 45/122 (36.9%) | 43.8% |
| logging | 44/112 (39.3%) | 27.8% | 87/125 (69.6%) | 54.3% |
| sections | 89/102 (87.3%) | 71.0% | 66/102 (64.7%) | 41.9% |
| cache | 80/92 (87.0%) | 78.6% | 0/92 (0.0%) | 0.0% |
| clipboard | 74/86 (86.0%) | 67.2% | 72/86 (83.7%) | 63.8% |
| coroutines | 50/55 (90.9%) | 68.8% | 49/55 (89.1%) | 56.2% |
| benchmark | 38/38 (100.0%) | 0.0% | 0/38 (0.0%) | 0.0% |
| rtc | 0/26 (0.0%) | 0.0% | 0/26 (0.0%) | 0.0% |
| flashcard | 25/25 (100.0%) | 70.0% | 25/25 (100.0%) | 55.0% |
| stelekit | 0/17 (0.0%) | 0.0% | 0/17 (0.0%) | 0.0% |
| resilience | 10/13 (76.9%) | 37.5% | 9/13 (69.2%) | 25.0% |

## Task 0.1c: timed Tier-1 PIT run

Command (`scripts/ci/pit-tier1.sh`): `./gradlew --no-daemon :kmp:mutationTest -Pmutate.classes=dev.stapler.stelekit.db.*,dev.stapler.stelekit.repository.* -Pmutate.tests=dev.stapler.stelekit.db.*,dev.stapler.stelekit.repository.* -Pmutate.threshold=0 -Pmutate.timeoutMinutes=80`

Result: PIT created 1,230 mutation test units for `db/` + `repository/` and was **stopped by the Gradle task timeout at 1 h 20 m** (4808 s wall-clock, exit code 1). It wrote an empty `mutations.xml`, so there is **no survivor count** for Tier 1; recording one would be an estimate. PIT also logged repeated `Minion exited abnormally due to TIMED_OUT` warnings (and a BlockHound agent warning) during the run.

Consequence for ADR-003: the nightly Tier-1 cadence is **not confirmed**. With the defaults (`mutate.maxMutationsPerClass=150`, 4 threads, `timeoutConst=2000`), `db/` + `repository/` as a single nightly job exceeds 80 minutes on this machine. ADR-003 needs an addendum before tiers are fixed: shard Tier 1 by package or class group, lower `maxMutationsPerClass`, or raise the job budget and re-measure. That addendum lives in the plan directory, which is not part of this branch.
