# Spike 0.1.3 results: cross-target determinism (2026-11-16)

Run on: Linux host, Gradle toolchain JDK 21 (JVM), ChromeHeadless 146 (wasmJs Karma), Robolectric native graphics, SDK 34 (Android). Every figure below was printed by the test named beside it; nothing here is from memory. Sources: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/sketch/v2/{DeterminismSpikeTest,DeterminismSpikeSupport,DeterminismSpikeFont,FloatDriftSpikeTest}.kt`, `kmp/src/jvmTest/.../DeterminismSpikeJvmTest.kt`, `kmp/src/wasmJsTest/.../DeterminismSpikeWasmTest.kt`, `kmp/src/androidUnitTest/.../AndroidPixelSpikeTest.kt`.

Font used for "bundled" rows: DejaVu Sans (Bitstream Vera license) subset to U+0020-007E, hinting, kerning and GSUB/GPOS removed (10 KB, embedded as base64). It stands in for the OD-4 font so all targets measure identical font bytes. It is NOT the product font choice; because kerning and ligature tables were removed, shaping differences between engines are UNMEASURED.

## 0.1.3a Canonical double formatting
Prototype `CanonicalDoubleSpike.format` (ECMAScript `Number::toString` layout over the platform's `Double.toString` digits, with a shorten-while-it-round-trips pass).

| Input class (20,000 values each unless noted) | JVM 21 | wasmJs |
|---|---|---|
| Plan values `[0.1, 1e-5, 123456789.125, -0.0, 4.0]` | `0.1, 0.00001, 123456789.125, 0, 4` | same |
| ECMAScript edge cases (`1e21`, `1e-7`, `5e-324`, `MAX_VALUE`, `0.1+0.2`, ...) | pass | pass |
| multiples of 1/16 (scene units; the lifted v1 domain), round trip | 0 failures | 0 failures |
| multiples of 1/16, 5,000-value FNV-1a corpus hash | 343850831 | 343850831 (identical) |
| uniform [0,1000) full-precision, canonical string does not round-trip | 0 | 6 |
| random 64-bit patterns, platform `toString().toDouble() != d` | 0 | 72 |
| random 64-bit patterns, canonical string does not round-trip | 0 | 72 |
| uniform and random-bits corpus hashes | 1461392967 / 1799138217 | -1618638240 / -1299924261 (differ) |

Findings: (1) byte-identical for the plan's fixture and for every multiple of 1/16, which is the whole lifted v1 domain. (2) JVM `Double.toString` keeps `4.9E-324` for `MIN_VALUE` where ECMAScript says `5e-324` (the prototype's shortening pass fixes it). (3) On wasmJs, `Double.toString` is not shortest-round-trip for full-precision doubles: examples `bits=-7340603843733603805 raw=-7.97171071413719e-183` parse back one ulp away. Which half is wrong (the 15-digit `toString` or the parser) is INFERRED to be `toString` (the string has 15 digits where 16 are needed) and was not isolated. Consequence recorded in ADR-002: Story 1.1.2 cannot lean on the platform `toString` for solver or rotation output; it needs its own shortest-digit generator (or must restrict canonical output to 1/16 multiples).

## 0.1.3b Text metrics (20 px, `TextMeasurer`, density 1, maxWidth 120 for "wrap")
Bundled font bytes, width x height, line-end offsets:

| String | JVM | wasmJs | Android (Robolectric) |
|---|---|---|---|
| "Auth Service" free | 128x23 [12] | 128x23 [12] | 128x24 [12] |
| "line one\nline two" free | 80x46 [8,17] | 80x46 [8,17] | 80x48 [9,17] |
| "Auth Service" wrap 120 | 120x46 [5,12] | 120x46 [5,12] | 120x48 [5,12] |
| pangram wrap 120 | 120x115 [10,20,31,40,43] | 120x115 same | 120x120 same |

Widths: 0 px difference on all three targets (tolerance 1 px met). Line breaks: identical at the 120 px wrap on all three. Heights: Android is 1 px per line taller (24 vs 23), so persisted heights must come from the persisting target and not be recomputed per target. The "text-layout pivot" (width diff over 1 px or any differing break) is NOT triggered for Latin with a bundled font. Default (platform) fonts, for contrast: "Auth Service" is 117 (JVM), 112 (wasmJs), 114 (Android) wide, 5 px apart, so platform fonts must never feed layout.

CJK ("データ", "你好"): JVM and Android fall back to a system font (60 and 40 px, identical to each other); wasmJs has no CJK glyphs (27 and 18 px with default font, 37 and 25 with the bundled Latin subset, i.e. missing-glyph advance). No CJK font file was available on this machine and OD-4 is open, so a bundled CJK subset (policy option a) was NOT measured. The measured platform spread (60 vs 27/37 px) exceeds 1 px by an order of magnitude, so policy (b) of the plan applies: written into ADR-004.

## 0.1.3c Offscreen bitmap and PNG on wasmJs
`ImageBitmap(200, 100)` + `CanvasDrawScope` + `TextMeasurer` draws a rect and text on wasmJs (ChromeHeadless): non-blank fraction 0.0387, same as JVM. Pixel hash over the 200x100 ARGB buffer: JVM 109317198, wasmJs 109317198 (identical, bundled font). PNG via skiko (`Image.makeFromBitmap(bmp.asSkiaBitmap()).encodeToData(PNG)`): 1250 bytes on both JVM and wasmJs; the wasm test did not check the PNG signature or decode it (size equality with the JVM output is the only evidence, so byte equality is INFERRED). Result: offscreen draw and PNG encode are possible on wasmJs, so Story 5.2.2 does not need the Canvas-element seam; BUG-003's stated cause (no Skia on wasm) was not reproduced on this path (BUG-003's own `ImageEncoder.encodeToJpeg` path was not touched). iOS: `compileKotlinIosArm64` was NOT run (Linux host; this spike adds no `iosMain` code), UNVERIFIED.

## 0.1.3d Float drift (cross-link 0.1.2c)
Double to Float to Double, 100,000 uniform values per range (same numbers on JVM and wasmJs):

| Coordinate range (scene units) | max absolute drift |
|---|---|
| +/-1,000 | 3.05e-5 |
| +/-100,000 | 3.9e-3 |
| +/-16,777,216 | 0.50 |
| +/-2^28 (SceneLimits) | 8.0 |

The solver residual bar is 1e-6 (ADR-003), so Float storage drifts 30x to 4,000x past it at realistic sheet sizes: model, codec and solver stay `Double`; Float appears only at the Compose draw boundary (drift there is sub-pixel).

**Solver fixtures (Task 0.1.3d, closed 2026-11-16)**: `SolverSpikeTest.floatRoundTripOfSolvedFixturesMissesTheResidualBar` solves the 31 generated 100-constraint sketches of Spike 0.1.2 (seeds 1 and 100..129; 40 lines, Coincident/Horizontal/Vertical/Equal/Distance), translates each solution to a stated sheet offset (all those constraints are translation invariant), rounds every coordinate to Float and re-evaluates every constraint. Worst case over the 31 fixtures, from the JVM run (`./gradlew :kmp:jvmTest --tests '*SolverSpikeTest'`, printed `SPIKE_FLOAT_DRIFT_SOLVER` lines):

| Sheet offset (units) | max residual, Double solution | max residual, after Float round trip | max coordinate drift |
|---|---|---|---|
| 0 (coordinates within about +/-40) | 1.81e-8 | 1.48e-6 | 9.5e-7 |
| 1,000 | 1.81e-8 | 7.27e-5 | 5.8e-5 |
| 16,000 | 1.81e-8 | 9.77e-4 | 4.9e-4 |
| 100,000 | 1.81e-8 | 7.81e-3 | 3.9e-3 |
| 1,048,576 | 1.81e-8 | 0.125 | 0.0625 |

The Double solution meets the 1e-6 bar everywhere (its residual is the solver's stop tolerance, not the storage). The Float copy misses 1e-6 already at the origin (1.48e-6) and by 70x or more from 1,000 units on, so Float storage cannot hold a solved sketch. The test asserts the Double residual stays under 1e-6 and the Float residual exceeds 1e-5 at every offset of 1,000 or more; it also passed on wasmJs (`./gradlew :kmp:wasmJsBrowserTest -PenableJs=true --tests '*SolverSpikeTest.floatRoundTrip*'`, ChromeHeadless 146: 1 test, 0 failures; the wasm run does not capture stdout, so the wasm figures themselves are not recorded and only the assertions are evidenced there).

## 1.1.2 follow-up: the wasmJs parser (2026-11-16)
The first `CanonicalJsonTest` run on wasmJs (ChromeHeadless 146) failed `decodeOfEncode_should_EqualTheInput_*` while the corpus hashes and the fixture-scene SHA-256 passed: the codec's own shortest digits are byte-identical to JVM, but `String.toDouble()` on wasmJs does not read them back to the same double. So both halves of the platform round trip are unreliable there (the 0.1.3a note above left open which half was wrong; it is both, or at least the parser). Story 1.1.2 therefore ships its own correctly rounded parser (`DecimalParser`) beside the Burger-Dybvig printer (`ShortestDouble`); `CanonicalJsonOracleTest` matches the JDK on 200,000 random bit patterns for printing and on about 120,000 strings (shortest, exact binary expansions of up to 770 digits, random 1-40 digit decimals with exponents -340..330, subnormal boundaries) for parsing, and `CanonicalJsonTest` passes 12/12 on wasmJs. Consumers must read a decoded number with `CanonicalJson.parseDouble` / `doubleOf`, never `String.toDouble` or `JsonPrimitive.double`.

## 0.1.3e Android pixel render under Robolectric native graphics
Same fixture (white fill, 80x40 stroked rect, "Auth" text at 20 px, bundled font), 200x100, JVM vs Robolectric (SDK 34, `GraphicsMode.NATIVE`): 306 of 20,000 pixels differ (1.5%), all in the text half of the image; the shape half (x < 100) is pixel-exact (0 differing pixels); maximum channel delta 204. Cause consistent with the 1 px line-height difference of 0.1.3b. Verdict: Robolectric native graphics is reliable for shapes and strokes (exact) and gives a usable text render with a 1 px vertical shift; recorded tolerance for Task 2.1.3c: shapes exact, text region at most 2% of pixels differing, or compare text by draw list. One run only: run-to-run determinism on Android is INFERRED, not measured. On-device parity (Story 6.2.4c) is untouched and not run.
