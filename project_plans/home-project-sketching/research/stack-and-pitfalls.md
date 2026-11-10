# Stack and Pitfalls: home-project-sketching

Confidence labels: VERIFIED = checked in this repo; CITED = from the named public source, recalled and not re-fetched in this session (re-check version numbers before pinning); DESIGN = our recommendation.

Repo facts (VERIFIED, `kmp/build.gradle.kts`): `kotlinx-serialization-json:1.10.0` (line 98), `com.fleeksoft.ksoup:ksoup:0.2.6` (line 127, KMP HTML/XML parser), `kotest-assertions-core`/`kotest-property:6.2.4` (lines 162-163), Coil 3 (line 118). Batik is JVM-only (line 218) and must not leak into commonMain.

## 1. SVG parse / SVG + DXF write

### Options
| Option | KMP (JVM/Android/iOS/wasm) | Fit | Verdict |
|---|---|---|---|
| Compose Multiplatform resources SVG rendering (`painterResource` of .svg; Coil `coil-svg`) | Render only; the Compose resource SVG path is Android-VectorDrawable-converted or platform-specific, no DOM access. https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-usage.html , https://coil-kt.github.io/coil/svgs/ (CITED) | Shows a drawing; cannot read `<text>` dimension labels | Use for display only, if at all |
| `svg-to-compose` (DevSrSouza) | JVM build-time code generator, emits ImageVector. https://github.com/DevSrSouza/svg-to-compose (CITED) | Generates static icons; loses text and semantics | Not suitable |
| Batik | JVM only (already in jvmMain) | Full SVG DOM | Cannot be used from commonMain; ok for a JVM-side golden-test oracle only |
| Ksoup (already a dependency) | Yes, KMP. https://github.com/fleeksoft/ksoup (CITED) | Parses XML/HTML into a DOM; use its XML parser mode for `<svg>` elements, attributes, `<text>` content | Best base for a minimal reader; confirm XML-mode (case-preserving `viewBox`) in a spike |
| `xmlutil` (pdvrieze) | Yes, KMP. https://github.com/pdvrieze/xmlutil (CITED) | Streaming XML reader and kotlinx-serialization XML format | Alternative if Ksoup lowercases attributes |
| Kotlin DXF libraries | No maintained pure-KMP DXF writer found (searched mentally; GitHub topic `dxf` + kotlin shows JVM-only, e.g. kabeja, dxf-writer wrappers) (UNVERIFIED, spike a real search) | DXF is a text group-code format; ASCII R12 `LINE`/`LWPOLYLINE`/`TEXT` is trivial to emit | Write our own emitter if DXF is wanted. Spec: https://help.autodesk.com/view/OARX/2024/ENU/?guid=GUID-235B22E0-A567-4CF6-92D3-38A2306D73F3 (CITED) |

### Recommendation (DESIGN)
Write our own minimal SVG reader over an XML DOM (Ksoup/xmlutil), scoped to what CadQuery and our own writer emit. Do not adopt a rendering library for parsing.

Reader scope, in priority order:
1. `viewBox`, `width`/`height` with units (`in`, `mm`, `px`, unitless). SVG spec: https://www.w3.org/TR/SVG11/coords.html#ViewBoxAttribute . Calibrate from `data-*` or named known dimensions, never from the `px` size.
2. `<line>`, `<rect>`, `<polyline>`, `<polygon>`, `<g transform>`. Support `translate`, `scale`, `matrix`; reject `skew`/non-uniform rotation with a typed error.
3. `<path d>` subset: `M L H V Z` and relative forms only; flag `C Q A` as "unsupported, bbox only". Grammar: https://www.w3.org/TR/SVG11/paths.html#PathDataBNF .
4. `<text>` with `x`,`y`,`text-anchor` and content, plus `<tspan>` concatenation; this is where CadQuery dimension labels live. Parse content with the rational parser from section 2.
5. Metadata: `data-*` attributes, `<title>`, `<desc>`.

Pitfalls: the SVG y-axis points down (flip for elevation sheets); nested transform composition order; CSS `style="..."` overriding attributes; `text` placed with `transform="rotate(-90)"` for vertical dimensions; entity decoding of `&quot;` for the inch mark and the unicode prime `″`.

Writer: SVG with a root `<svg viewBox>` in model units (1 unit = 1/16"), `data-dim-id`, `data-value="33 7/16"`, `data-source` attributes on each dimension group, and a `<metadata>` JSON block holding the machine model. This answers the open question: SVG with `data-*` first (round-trips with the owner's CadQuery SVGs and renders anywhere); DXF is an optional write-only exporter added later via a small ASCII emitter (units header `$INSUNITS=1` inches). Do not read DXF in v1.

## 2. Exact rational inch arithmetic

### Type (DESIGN)
```kotlin
@JvmInline value class Inches(val sixteenths: Long)          // canonical for sheet geometry
data class Rational(val num: Long, val den: Long)              // normalized: den > 0, gcd == 1
```
Why two: the closet data are all multiples of 1/16", so `Long` sixteenths gives exact add/sub/compare with no gcd. Use `Rational` only for intermediate results (division by N for "equal zones", scale ratios) and convert at the boundary with an explicit rounding policy.

Rules:
- Normalize on construction; reject `den == 0`; use `Math.multiplyExact`-style overflow checks implemented in common code (`Long` overflow checks are not in `kotlin.math` on all targets; write `addExact`/`mulExact` helpers, since JVM `Math.*Exact` is unavailable in commonMain).
- Rounding to 1/16": round-half-up away from zero on the absolute value, expressed as an enum `RoundingPolicy { HALF_UP, FLOOR, CEIL }`. Dividing 136" into 5 equal zones gives 27 1/5", which is not representable; the solver must report "not representable at 1/16" with the exact remainder instead of silently rounding.
- Tolerance is a separate field (`±1/16"` default for field measurements) compared with `abs(a - b) <= tol`, never with `==` on parsed values from different sources.

Parse grammar (accept): `33 7/16"`, `33-7/16`, `33 7/16 in`, `2'-9 7/16"`, `2' 9 7/16"`, `7/16"`, `33.4375"`, `33.4375`, `0.5`, unicode vulgar fractions `½ ⅜`, `″`/`"`/`''` and `′`/`'`. Reject: `33 17/16` as ambiguous (accept but normalize and warn), `3/0`, empty, exponent notation.
Format: canonical `FEET_INCHES` and `INCHES_MIXED` (`33 7/16"`), lowest terms (`22 1/2"`, not `22 8/16"`); decimal output only when asked.

Boundary with existing `calibration/`: VERIFIED that `CalibrationService.kt` exists; check whether it stores doubles. Keep `Double` pixel math in annotation code and convert via `Inches` only at the displayed-value edge.

### kotest-property invariants (use `Arb`/`checkAll` inside `runTest`, per CLAUDE.md)
Source: https://kotest.io/docs/proptest/property-based-testing.html (CITED).
1. Round trip: `parse(format(x)) == x` for all `x` in `Arb.long(-100_000..100_000)` sixteenths, in both formats.
2. Normalization idempotent: `normalize(normalize(r)) == normalize(r)`.
3. Field axioms on `Rational`: add commutative/associative, `a + (-a) == 0`, `a * a.inv == 1` for `a != 0`.
4. Order consistency: `a < b` iff `(b - a).num > 0`; compare via cross-multiplication equals compare via `Double` when within 2^53.
5. Decimal bridge: `parse("%.4f")` of any sixteenth value equals the sixteenth exactly (n/16 has a finite 4-digit decimal, so 4 places are exact).
6. Rounding: `roundTo16(r)` is within 1/32" of `r`, is idempotent, and `roundTo16(-r) == -roundTo16(r)` for HALF_UP-away-from-zero.
7. Overflow: `addExact` throws (or returns Left) rather than wrapping; generate near `Long.MAX_VALUE`.
8. Parser never throws on `Arb.string()`; returns `Either`.
Named examples (kotlin.test): `22 1/2 + 24 3/16 + 22 1/2 == 69 3/16`; `33 7/16 + 33 3/8 + 69 3/16 == 136`.

## 3. Minimal constraint model (linear equalities)

### Model (DESIGN)
Variables: named dimensions `d_i` (in sixteenths, `Long`). Constraints, all linear with integer coefficients:
- `Fixed(d, v)` : source states a value (each source contributes a separate `Observation`, see conflict handling).
- `Sum(total, parts...)` : `total = sum(parts)`.
- `Equal(a, b)` : `a - b = 0`.
- `Offset(a, b, k)` : `a - b = k`.
No inequalities, no nonlinear terms, no geometry solving.

Solver: exact rational Gaussian elimination (fraction-free Bareiss, or `Rational` rows) over at most 500 variables; 500x500 is far under the 1 s budget (O(n^3) = 1.25e8 worst case, in practice constraints are sparse; use union-find first for `Equal`/`Offset` components, then eliminate only the `Sum` rows). Reference: https://en.wikipedia.org/wiki/Gaussian_elimination and fraction-free: https://en.wikipedia.org/wiki/Bareiss_algorithm (CITED).

Classification after row-reduction of `[A | b]`:
- Inconsistent row (`0 = c`, `c != 0`) : over-constrained with conflict. Report the minimal conflicting set by tracking which original constraint ids contributed to the row (carry a bitset of provenance per row; the bitset on the inconsistent row is the explanation). Name both sources and values (e.g. "table: 33 3/8" vs drawing: 33 7/16"").
- Free variables remain: under-constrained; list the free variables and which sums have exactly one unknown ("derivable: middle zone = 69 3/16").
- Unique solution: fully constrained; echo derived values with `provenance=derived`.
- Redundant but consistent rows: report as "confirmed by N sources" (this is success metric 1's "≥2 sources").

Observation vs constraint: a measured value from a table/drawing is an `Observation(dim, value, tol, source)`, not a hard equation. Run two phases: (a) structural constraints only to compute expected values, (b) compare each observation to its expected value within tolerance, and flag mismatches. This avoids a single bad measurement poisoning the whole system, and gives per-source blame without needing minimal-unsatisfiable-subset search. For tolerance-aware conflicts among observations of the same dimension, group by dimension and report the spread.

Not-representable results (division yielding non-1/16 values) are surfaced as warnings, see section 2.

## 4. Canvas drawing, snap, undo (Compose Multiplatform)

Docs (CITED): pointer input https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures ; Canvas/DrawScope https://developer.android.com/develop/ui/compose/graphics/draw/overview ; transformable https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch .

Patterns (DESIGN):
- Gestures: one `Modifier.pointerInput(Unit)` with `awaitEachGesture`, a tool state machine (`Idle -> Placing -> Dragging`). Use `detectTransformGestures` only for the pan/zoom layer (two pointers); single pointer goes to the active tool. Distinguish tap vs drag with `viewConfiguration.touchSlop`. Do not nest `detectDragGestures` and `detectTapGestures` in separate `pointerInput` blocks on the same node (they race); this repo already has a "narrow-canvas crash" and loupe/placement polish items (Epic C), so reuse the existing annotation placement conventions.
- Coordinates: keep the model in sheet units (sixteenths); a single `ViewTransform(scale, offset)` maps screen<->sheet. Hit-test in sheet space with a tolerance of `~12dp / scale`.
- Hit testing: linear scan is fine for 200 primitives (200 x segment distance per pointer move is microseconds); add a uniform grid index only if profiling says so. Prefer point-to-segment distance with early AABB reject.
- Snapping: priority order endpoint/vertex > midpoint > intersection > grid > axis lock (horizontal/vertical within 3 degrees). Compute candidates only for primitives whose AABB intersects the cursor radius. Show snap glyph. Snap result is a sheet-unit `Inches` value, so typed dimensions and snapped points stay exact. Shift-equivalent (long-press/toggle) for ortho lock on touch.
- Rendering performance: 200 primitives is small; hold the scene in an immutable list, draw in one `Canvas` with `drawLine`/`drawPath`, avoid allocating `Path`/`Offset` per frame (cache `Path` per primitive keyed on geometry version), draw the grid as one batched `drawPoints` and skip it below a zoom threshold, never recompose the whole screen on pointer move (hold the in-progress shape in a `mutableStateOf` read only inside the draw lambda so only the draw phase invalidates). Text via `rememberTextMeasurer` with cached layouts (https://developer.android.com/develop/ui/compose/text/draw-text; CITED); measuring text per frame is the usual 50 fps killer. Measure with the existing benchmark harness (budgets in `benchmarks/budgets/`).
- Undo/redo: command pattern with inverse operations or immutable snapshots. For a 200-primitive scene, persistent immutable snapshots (`List` copy-on-write) in an undo stack of N=100 are simpler and cheaper than inverse commands; coalesce a drag into one history entry on pointer-up. Constraints re-solve after each committed command, not per pointer move (use a lightweight preview during drag).
- Persistence: writes go through `DatabaseWriteActor`; new table must be added to `MigrationRunner.all` (CLAUDE.md mandatory rule, enforced by `MigrationRunnerSchemaSyncTest`); regenerate SQLDelight sources and commit `kmp/src/generated/sqldelight/`.
- Scope ownership: any sketch controller in `remember { }` owns its own `CoroutineScope` (CLAUDE.md `rememberCoroutineScope` rule).
- Testing: ViewModel/state-machine logic in `commonTest`; Compose gesture behaviour in `androidUnitTest` (Robolectric), per CLAUDE.md.

## 5. LLM-friendly serialization and testing without a live LLM

### Shape (DESIGN)
Two artifacts per project, regenerated together:
1. `project-model.json` (kotlinx-serialization, versioned `schema: 1`):
```json
{"schema":1,"project":"proj-closet-1","units":"in","precision":"1/16",
 "dimensions":[{"id":"zone.left","label":"Left zone width","value":"33 7/16","sixteenths":535,
   "tol":"1/16","status":"confirmed","sources":[
     {"kind":"table","ref":"table:t001#r3","value":"33 7/16"},
     {"kind":"svg","ref":"elevation.svg#t12","value":"33 7/16"}]}],
 "constraints":[{"id":"c1","type":"sum","total":"opening","parts":["zone.left","zone.mid","zone.right"],"holds":true}],
 "conflicts":[],"underConstrained":[]}
```
Provenance fields per value: `kind` (table|svg|yaml|prose|user|derived), `ref` (stable locator, no filesystem paths), `value` as written, `confidence`, `asOf`. Always include both the display string and integer sixteenths so the model never does fraction arithmetic by guessing.
2. A markdown digest, about 1 line per dimension plus a "Checks" section: `Zones sum to opening: PASS (33 7/16 + 22 1/2 + 24 3/16 + 22 1/2 + 33 3/8 = 136")`. Pre-computed verdicts let the LLM answer "do all zone widths sum to the opening?" by reading, not computing.

Token economy: omit null fields; short stable ids; emit conflicts/under-constrained first; cap digest to a budget (e.g. 2k tokens, truncate lowest-value rows with an explicit "N more omitted" line); no coordinates or geometry unless asked. Structured-output guidance: https://docs.claude.com/en/docs/build-with-claude/structured-outputs (CITED).

Privacy gate (requirements constraint): no raw imagery, no filenames or absolute paths, no PII in `ref`; run the artifact through the existing `LogRedactor`/privacy check before write; add a schema test that fails on any string matching path/email patterns.

### Tests without an LLM
- Golden-file tests: closet fixture -> `project-model.json` and digest byte-for-byte; update goldens via an explicit flag.
- Schema test: decode, re-encode, compare; `schema` bump required on any field change.
- Stubbed-model golden prompt: a `FakeLlmClient` that receives the exact prompt (digest + question) and is asserted on: (a) prompt contains the PASS line and all 5 zone values, (b) prompt size under budget, (c) no redaction-gate hits. The stub returns a canned answer; the test asserts the artifact contains the information needed (a deterministic evaluator that checks the digest verdict text), not LLM quality. Existing `llm/` package (VERIFIED dir exists) should provide the client interface to fake.
- Property test: for generated models, digest verdicts equal solver verdicts (the digest is a pure function of the solver output).

## 6. Wiki-write pitfalls

Sources: Logseq markdown/properties https://docs.logseq.com/ and https://github.com/logseq/logseq (CITED); GFM tables https://github.github.com/gfm/#tables-extension- (CITED); repo ADRs `project_plans/cross-graph-merge-share-target/decisions/ADR-001..004` and CLAUDE.md "Cross-graph writes" section (VERIFIED in CLAUDE.md).

- Table splice round-trip: Logseq tables are plain GFM pipes inside a block's text (continuation lines), not first-class structures. Splice only the changed rows/cells by byte offset; preserve column alignment row (`|---|:--:|`), original padding, trailing pipe style and line endings (CRLF). Never re-render the table; use `MarkdownSplicer` + `RoundTripGuard` rules (refuse when the splice would change an existing block).
- Escaped pipes (`\|`) and inline `|` inside code spans break naive `split("|")`; fractions with `/` are fine, but the inch mark `"` and unicode `″` vary between cells; normalize only for comparison, never when writing back.
- Cell content may carry links (`[[page]]`), bold, or footnotes: extract the number with a tolerant parser but retain the original cell text as `value as written` for provenance and for diff-free write-back.
- Table header variation: the owner's header names differ per page (e.g. "Zone", "Width (in)"); map columns by configurable header aliases, fall back to "ask the user" rather than guess; LLM-assisted mapping stays optional.
- Logseq property syntax: `key:: value` lines at the top of a block or page (first block of the page for page properties); keys lowercase with hyphens are normalised, values with commas may be split into page refs depending on config; do not put raw `::` in digest text. A property block must be the first lines of the block's content; inserting text above it demotes it to plain text. Existing properties not in `Block.properties` are dropped by `LogseqPageSerializer` (CLAUDE.md: it writes only keys already in `Block.properties`), so preserve unknown keys when touching a page.
- Block ids: any new block we write needs a deterministic uuid and explicit `id::` (CLAUDE.md `UuidRemap`); re-running the sync must be idempotent (replace our digest block by marker, not append a second copy). Wrap the digest in a stable marker property e.g. `stelekit-digest:: proj-closet-1` and find-and-replace by that key.
- Never write markdown of the active graph behind `GraphWriter`; use existing graph writers (requirements constraint; CLAUDE.md "Cross-graph writes").
- Feedback loop: the digest we write is itself on a wiki page that sync re-reads; mark digest blocks so the extractor ignores them, or fusion will count its own output as an independent source and inflate "≥2 sources".
- File watcher: our own write triggers `externalFileChanges`; rely on self-write suppression via `GraphWriter`.
- Encoding: UTF-8 no BOM, keep original newline convention, do not trim trailing spaces (Logseq uses two trailing spaces for hard breaks).

## Pitfalls ranked (likelihood x impact; H=3, M=2, L=1)

| # | Pitfall | L | I | Score | Mitigation |
|---|---|---|---|---|---|
| 1 | Fusion double-counts its own digest or two copies of one source as independent, inflating "≥2 sources" | H | H | 9 | Exclude marker blocks; dedupe by `ref`; test with a self-feeding fixture |
| 2 | Table splice alters unrelated bytes (padding, alignment, CRLF) and corrupts the wiki | H | H | 9 | Splice-only + RoundTripGuard; byte-for-byte golden tests |
| 3 | Float or lossy rounding sneaks into dimensions (`33.4375`, `Double` calibration) so equality checks drift | H | M-H | 8 | Single `Inches`/`Rational` type; ban `Double` in model; property tests |
| 4 | CadQuery SVG lacks semantic labels, so text-to-dimension binding is wrong | M | H | 6 | Bind by proximity + `data-*` when present; show unbound labels for manual tagging; report binding confidence |
| 5 | New table missing from `MigrationRunner.all` or generated SQLDelight not committed (Bazel CI failure / missing on existing DBs) | M | H | 6 | Follow CLAUDE.md checklist; `MigrationRunnerSchemaSyncTest` |
| 6 | Constraint conflict reported with the wrong blame (single bad observation poisons system) | M | H | 6 | Observation-vs-constraint phases; provenance bitsets |
| 7 | Canvas misses 50 fps because of per-frame text measurement/`Path` allocation/recomposition | M | M-H | 5 | Cached TextLayout/Path, draw-phase-only state reads; benchmark probe |
| 8 | Privacy leak: filenames/paths/imagery in LLM artifact | L-M | H | 4-6 | Redaction gate + schema test on `ref` patterns |
| 9 | Long overflow or non-representable division silently rounded | L | M | 2-3 | Checked ops; report remainder |
| 10 | Android SAF / wasmJs limits break SVG import (no file path, no Batik) | M | M | 4 | Stream bytes from picker into commonMain parser; no JVM-only deps in commonMain; compile wasmJs tests |
| 11 | Ksoup XML mode lowercases `viewBox` or mangles `<text>` whitespace | M | M | 4 | Spike test on real CadQuery SVG; fall back to xmlutil |
| 12 | Gesture handler races (tap vs drag vs pan/zoom), existing narrow-canvas crash | M | M | 4 | Single pointer-input state machine; Robolectric tests |
| 13 | Logseq property block demoted/dropped on rewrite | M | M | 4 | Preserve unknown keys; property must stay first lines |
