# ADR-002: Two coordinate regimes, one lossless bridge (Double scene units, 1 unit = 1/16 inch)

**Status**: Accepted (2026-11-14, plan sign-off; no owner decision outstanding)
**Date**: 2026-11-14

## Context
Open Question 4. The integer-sixteenths model (`SketchPrimitive`, `Inches`) is load-bearing for exact rational dimensions, fusion and the CSV/SVG writers (ADR-002/003 of home-project-sketching). Excalidraw and the solver use doubles.

## Decision
- v2 elements use `Double` coordinates in "scene units" where 1 unit equals 1/16 inch, so a lifted v1 value keeps the same numeric value (`Long` below 2^53 to `Double` is exact; `MAX_COORD_SIXTEENTHS = 2^28` is safe).
- `V1ToV2.lift` is total and exact. `V2ToV1.project` is partial and returns a typed `ProjectionResult` (`Projected(primitive)` or `Dropped(reason)`) for axis-aligned Rect/Line with integer endpoints after rounding once at projection time. Dropped elements never enter fusion; the UI reports "N elements not used for dimensions".
- The solver works in Doubles; solved geometry is rounded to sixteenths only at projection, never per iteration. Fusion's own verification reports a mismatch rather than snapping.
- Non-finite values and `abs(coord) > 2^28` are rejected at every boundary (import, solver output, command).

## Alternatives rejected
- Unify on Double: forces tolerance logic into Bareiss elimination and breaks golden fixtures.
- Keep integers only: cannot carry curves, rotation, Excalidraw round trip.

## Scale addendum (Story 1.2.4)
Excalidraw pixels map 1:1 to scene units on import and export (1 px = 1 unit = 1/16 inch); no conversion factor, so `export(import(x))` is exact. Excalidraw pixels carry no physical meaning, so a 100 px rect is 6.25 in in a technical sheet; this is intentional. Hit and snap tolerances are screen pixels divided by zoom, never raw scene units. Shared bounds live in `SceneLimits` (5,000 elements, 200 constraints, abs coord 2^28, 2,000 points per element, JSON depth 64, 16 MB sheet, 10,000 text chars, 20 MB import file, 2 MB largest JSON span decoded in one import call (`maxImportSpanBytes`, D-31), 60 MB / 16 MP image, 200 library items and 16 MB of stored library (`maxLibraryBytes`, each item one import span), 64 layers, soft banner at 4,000 elements / 160 constraints, 16,000 px PNG side, 100 ms slow-solve indicator); no other numeric cap exists in the sources.

## Consequences
Property tests: lift then project is identity for all int sixteenths; project of a rotated or non-integer element is `Dropped`.

## Spike 0.1.6 decision: scene structure and spatial index (2026-11-16)
**Evidence**: `JT/sketch/v2/SceneStructureBenchmark.kt` (harness, writes `kmp/build/reports/scene-structure-benchmark.txt`) and `SceneStructureBenchmarkGuard.kt` (budget guard). 5,000 `RectElement`-shaped records, one moved per step, 100 steps, 100 snapshots retained, JVM 21 (Gradle toolchain), one host with a load average above 50 (so times are pessimistic and noisy), one run. VERIFIED by running `./gradlew :kmp:jvmTest --tests '*SceneStructureBenchmark*'`. wasmJs and Android were NOT measured: the harness is JVM-only (`jvmTest-reason: desktop-only`), so every figure below is JVM; other targets are INFERRED to be 2-4x slower.

| Snapshot after moving one element | median per step | p95 | heap retained by 100 snapshots |
|---|---|---|---|
| `HashMap` copy (id to element) | 180 us | 353 us | 18,664 KB |
| `LinkedHashMap` copy (keeps order) | 90 us | 142 us | 22,530 KB |
| `ArrayList` copy (index addressed) | 1.6 us | 3.2 us | 1,959 KB |
| Persistent 32-way hash trie (path copying, 70 lines, no dependency) | 1.3 us | 5.0 us | 59 KB |

| Query (2,000 random points, 5,000 rects) | candidates (AABB tests) per query | time per query |
|---|---|---|
| Flat scan, sparse and dense, hit 8 u or snap 24 u | 5,000 | 34 to 58 us |
| Grid cell 256 u, sparse | 2.9 to 3.5 | 0.35 to 1.1 us |
| Grid cell 256 u, dense (2,500 u square, about 12 to 18 hits per query) | 109 to 134 | 1.8 to 3.3 us |
| Grid cell 128 u, dense | 52 to 77 | 1.2 to 2.5 us |
| Grid cell 512 u, dense | 296 to 325 | 3.8 to 6.7 us |
| STR R-tree (capacity 16), dense | 113 to 129 | 1.6 to 3.7 us |

Index upkeep after one element moves: grid remove+insert 0.7 us median; the R-tree prototype has no incremental update, a full STR rebuild is 1,981 us median. Every grid and R-tree result was asserted equal to the flat scan's hits inside the harness (the oracle).

**Decision (the plan's defaults applied; these are measured recommendations for Stories 1.2.1 and 1.3.3, not owner decisions)**:
1. `SketchSceneV2` keeps elements in a persistent hash trie keyed by element id, plus an ordinary `List` of ids for z-order that is array-copied on reorder (1.6 us). Reason: a full `HashMap` copy per snapshot is cheap in time (under 0.4 ms) but retains about 190 KB per snapshot, 18.7 MB for a 100-deep history, and allocates that much garbage per drag frame; the trie retains about 0.6 KB per snapshot. The trie is about 70 lines, so no dependency is added (ADR-007). The simpler copy-on-write `HashMap` also passes the time bar; the trie is chosen for memory, and Story 1.2.1 may fall back to `HashMap` copy if the trie costs more than it saves in review.
2. The spatial index is a uniform grid with 256-unit cells (16 in), incremental from `changedIds` by remove+insert. Flat scan stays as the brute-force oracle for the property test. Reason: flat scan already passes a 1 ms hit bar by 17x on JVM (34 to 58 us), so it is the correct v1 fallback if the grid is cut, but snap runs per drag frame over many candidate points per element and was not measured with real snap geometry, so the grid's 20 to 50x lower candidate count is the safer margin. A 5,000-element dense scene still needs only about 130 candidates per query. R-tree is rejected: no cheaper than the grid on dense scenes, and its update path is a rebuild or a split/merge implementation for no measured gain.
3. Guard budgets (`SceneStructureBenchmarkGuard`): `HashMap` copy snapshot median under 2,000 us and trie snapshot median under 100 us (about 11x and 77x the measured medians, set wide because the measuring host was loaded).

**Not covered by this spike (named gaps)**: wasmJs and Android timing; real snap-candidate cost; worst-case clustering (all elements in one grid cell degrades the grid to a flat scan; a 100x100 u cluster was not run); memory was measured as heap growth after `System.gc()` and is approximate (about 10%).

## Spike 0.1.3a decision: canonical double formatting (2026-11-16)
Evidence and tables: `research/determinism-spike.md` (VERIFIED by `DeterminismSpikeTest` on JVM 21 and on wasmJs in ChromeHeadless 146). The ECMAScript `Number::toString` layout (`4` for `4.0`, `0.00001`, `1e+21`, `-0.0` as `0`) built on platform digits is byte-identical on JVM and wasmJs for the plan's fixture values and for every multiple of 1/16 tested (20,000 values, same corpus hash on both), which covers every lifted v1 coordinate. It is NOT identical for full-precision doubles: on wasmJs `Double.toString` is not shortest-round-trip (72 of 20,000 random 64-bit patterns and 6 of 20,000 uniform [0,1000) values do not parse back), and JVM keeps `4.9E-324` where ECMAScript prints `5e-324`.
Decision for Story 1.1.2 (plan default, not an owner decision): the codec carries its own shortest-digit generation (a Grisu/Ryu-class routine in `commonMain`, checked by a round-trip property test and by the JVM-recorded corpus hashes of `DeterminismSpikeTest`) instead of trusting any platform `toString`; until it exists, only multiples of 1/16 may be written as canonical doubles. The prototype `CanonicalDoubleSpike` in the test file is the layout reference, not production code.

## Spike 0.1.3d decision: Float drift (2026-11-16)
`FloatDriftSpikeTest`, identical figures on JVM and wasmJs: a Double to Float round trip drifts by up to 3.1e-5 at +/-1,000 units, 3.9e-3 at +/-100,000 and 8.0 at the 2^28 limit, against the 1e-6 solver residual bar. Decision: scene model, codec and solver stay `Double`; `Float` appears only when handing coordinates to Compose draw calls. Closed on the solver fixtures (Task 0.1.3d, 2026-11-16; table in `research/determinism-spike.md`): the 31 solved 100-constraint sketches keep a 1.8e-8 residual in Double, but after a Float round trip the worst residual is 1.48e-6 at the origin and 7.3e-5, 9.8e-4, 7.8e-3 and 0.125 at offsets of 1,000, 16,000, 100,000 and 1,048,576 units, so Float storage cannot meet the 1e-6 bar.

## Story 1.1.2 decision: canonical doubles (2026-11-16)
The codec owns both directions of the double text round trip (`V2/codec`: `ShortestDouble` printer, `DecimalParser` reader, `CanonicalJson` layout), because on wasmJs neither `Double.toString` nor `String.toDouble` round-trips shortest digits (`research/determinism-spike.md`, "1.1.2 follow-up"). Output is the ECMAScript `Number::toString` layout over the shortest digits, ties to the even digit, `-0` as `0`, NaN and infinities rejected with `NonFinite`; keys are sorted by UTF-16 code unit with `schema` and `sheetUuid` first at the top level. Doubles enter a JSON tree through `CanonicalJson.number` and leave it through `parseDouble`, never `JsonPrimitive(Double)` or `.double`. Evidence: `CanonicalJsonTest` (12 tests on JVM and wasmJs, identical corpus hashes and fixture-scene SHA-256 on both) and `CanonicalJsonOracleTest` (JDK 21 oracle).

## Story 1.2.4 implementation note (2026-11-16)
`SceneLimits` (`kmp/src/commonMain/.../sketch/v2/scene/SceneLimits.kt`) holds the 32 canonical fields; "MB" caps are binary megabytes (1,048,576 bytes). `SceneLimitsTest` pins the field list and defaults; `SceneLimitsGrepTest` (businessTest) fails on a cap-like literal in the engine, importer, solver, `ui/sketch/v2` and `projectbridge` sources that is not in `scene-limits-allowlist.txt` (shrink-only; today 8 entries: 2 hash-prefix lengths, 6 Spike 0.1.2 solver numerics).
