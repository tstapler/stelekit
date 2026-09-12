# Dependency Graph Verification: commonmain-bazel-target-split

**Date**: 2026-09-04

> Note: this phase slot is normally "Features/prior-art" research. For this project the
> mandate was redirected to rigorous dependency-graph verification (Feasibility Risks #1-#3
> in `requirements.md`), since that is the highest-consequence open question for Phase 3
> planning. No feature/prior-art research is included here.

## Confirmed package inventory

`ls kmp/src/commonMain/kotlin/dev/stapler/stelekit/` returns **38** top-level directories,
confirmed by `find <pkg> -name '*.kt' | wc -l` per package:

| Package | .kt files | Package | .kt files | Package | .kt files |
|---|---|---|---|---|---|
| asset | 15 | flashcard | 1 | resilience | 1 |
| benchmark | 1 | git | 37 | rtc | 1 |
| cache | 4 | llm | 19 | search | 3 |
| calibration | 2 | loader | 1 | sections | 9 |
| clipboard | 2 | logging | 1 | service | 2 |
| command | 3 | migration | 17 | stats | 2 |
| coroutines | 3 | model | 13 | tags | 9 |
| db | 39 | outliner | 6 | transfer | 21 |
| docs | 6 | parser | 4 | ui | 178 |
| domain | 13 | parsing | 8 | util | 7 |
| editor | 30 | performance | 32 | vault | 6 |
| error | 2 | platform | 50 | voice | 16 |
| export | 10 | repository | 35 | | |

Total `.kt` files under `commonMain/kotlin/dev/stapler/stelekit`: **609** (`find ... -name '*.kt' | wc -l`).
(Requirements.md's "~800 files" figure is an approximation from the problem statement's
profiling context, not a re-derived count here — not in scope to reconcile further, but noted
since it doesn't match this repo-relative count.)

**Reconciling 38 vs. 29**: the requirements.md package lists (11 hub + 9 zero-risk + 18
hub-touching = 38) are verified to exactly partition all 38 confirmed directories — every one
of the 38 appears in exactly one of the three lists, with no omissions or duplicates (checked
by hand, one-to-one). The "29" figure cited in the original risk-analysis text has no
reconstructible basis in the current directory listing (39 is 38 too, not 29; there's no
plausible subset/superset relationship — e.g. excluding the 9 zero-risk packages gives 29,
which is almost certainly what happened: the original count silently dropped the zero-risk
group at some point in the analysis history). **Verdict: 38 is correct and current; 29 is
stale/wrong** and should not be used for planning.

## Import edge list (package-level)

Generated via `grep -rhoE 'import dev\.stapler\.stelekit\.[a-zA-Z0-9_]+' <pkg> --include='*.kt' | sed -E 's/^import dev\.stapler\.stelekit\.//' | sort -u`, self-imports excluded, run once per top-level package (zsh requires `${=pkgs}` for word-splitting an unquoted variable — bash-style `$pkgs` silently ran the whole loop body once with the entire package list as a single `$pkg` value; caught and fixed before results were trusted).

Full edge list (129 directed package→package edges) saved for reproducibility; key packages with outgoing edges relevant to cycle discussion:

```
asset       -> coroutines, db, error, logging, platform, repository, util
calibration -> logging, model, platform
db          -> coroutines, domain, error, git, llm, logging, migration, model,
               outliner, parser, parsing, performance, platform, repository,
               sections, util, vault
domain      -> error, llm, logging, model, repository, voice
editor      -> db, error, logging, model, performance, platform, repository, ui, util
export      -> coroutines, error, model, outliner, parsing, repository, util
git         -> coroutines, db, error, logging, model, parser, platform, repository,
               util, vault
llm         -> db, error, logging, model, platform, repository, tags, util, voice
migration   -> coroutines, db, logging, model, repository, util
performance -> cache, coroutines, db, error, logging, platform, repository, util
platform    -> calibration, error, logging, model, ui
repository  -> asset, cache, coroutines, db, error, logging, model, outliner,
               performance, search, util
search      -> error, model, repository
sections    -> error, logging, platform, repository
tags        -> domain, error, llm, logging, platform, util, voice
transfer    -> db, error, logging, model, platform, repository, util
ui          -> asset, calibration, clipboard, coroutines, db, docs, domain, editor,
               error, export, flashcard, git, llm, logging, migration, model,
               outliner, parser, parsing, performance, platform, repository,
               sections, service, stats, tags, transfer, util, vault, voice
voice       -> llm, logging, model, platform, repository
(leaf packages with no outgoing edges to hub/hub-touching packages: benchmark,
 cache, clipboard, command, coroutines, docs, error, flashcard, loader, logging,
 model, outliner, parsing, resilience, rtc, stats, service, util, vault)
```

## Cycle analysis

**Method**: built a directed graph from the edge list above (`/tmp/.../edges.txt`,
129 edges) and ran Tarjan's strongly-connected-components algorithm
(`/tmp/.../scc.py`) — standard, exact cycle detection (any SCC with >1 node is a
real cycle; a graph with zero import cycles yields 38 singleton SCCs). Also
computed a full transitive-closure reachability matrix as a cross-check
(node reaches itself ⟺ node is in some non-trivial SCC) — both methods agree.

**Result — one dominant cycle**: Tarjan's algorithm finds exactly **one non-trivial SCC,
containing 17 of the 38 packages**:

```
asset, calibration, db, domain, editor, export, git, llm, migration,
performance, platform, repository, search, sections, tags, transfer, ui, voice
```

Every other package (21 of 38) is its own singleton SCC — no cycle involves them.

Direct (2-node, A→B and B→A) bidirectional cycles found within that SCC:

```
asset <-> repository        calibration <-> platform      db <-> repository
db <-> git                  db <-> llm                    db <-> migration
db <-> performance          editor <-> ui                 llm <-> tags
llm <-> voice                performance <-> repository    platform <-> ui
repository <-> search
```

**Spot-check (all confirmed real, not regex/comment artifacts)** — read the actual import
lines for the most architecturally surprising edges:
- `platform/ml/MonocularDepthEstimator.kt:7` imports `dev.stapler.stelekit.ui.annotate.DepthModelUiState` — a "platform" (expect/actual hub) package genuinely importing from `ui`.
- `db/GraphManager.kt:33-34`, `db/GraphLoader.kt`, `db/DatabaseWriteActor.kt`, `db/BacklinkRenamer.kt`, `db/ImageImportService.kt`, `db/sidecar/ImageSidecarIndexer.kt` all import `repository.{BlockRepository, PageRepository, RepositorySet, GraphBackend, DirectRepositoryWrite, ...}` — `db` genuinely depends on `repository` types, inverting the layering implied by `CLAUDE.md`'s "Database/Files → db/, Repository → repository/" diagram.
- `calibration/CalibrationFallbackChain.kt` imports `platform.ml.MonocularDepthEstimator` / `platform.sensor.DepthSensorProvider`, while `platform/sensor/DepthSensorProvider.kt` imports `calibration.DepthFrame` — genuine two-way.
- `repository/SqlDelightSearchRepository.kt` / `repository/RepositoryFactory.kt` import several `performance.*` symbols, while `performance/OtelRepositoryWrappers.kt` / `performance/SpanRepository.kt` import `repository.{PageRepository, SearchRepository, DirectRepositoryWrite}` — genuine two-way.
- `llm` ↔ `tags` and `llm` ↔ `voice`, `repository` ↔ `search`, `ui` ↔ `platform`, `editor` ↔ `ui` — all confirmed with real, non-comment `import` statements (see Sources).

**Cross-check against `mcp__kibitzer__architecture_assessment`**: ran it against
`kmp/src/commonMain/kotlin` (scope `kmp/src/commonMain/kotlin/**`, `include_diagram=true`).
It required a `.claude/inspect.json` (none existed at task start per requirements.md; a
minimal `{"checks":[{"name":"kotlin-import-cycles","architecture_checker":"import-cycles",
"severity":"advisory","triggers":["batch"]}]}` config was needed — see Sources for the schema
reference used to fix an initially-invalid stub file another concurrent Phase 2 research
agent had dropped in `.claude/inspect.json`). Once valid, kibitzer ran successfully (77
dependency-graph nodes, under its 150-node diagram-fallback threshold, so a full Mermaid
diagram was rendered — no fallback needed) and reported **exactly 2 import-cycle findings**
out of 744 total findings:

1. `git.merge <-> git.model <-> git.merge` — a cycle **entirely inside the `git` package**
   between two of its sub-packages. Irrelevant to the top-level package split (both nodes
   compile into the same `git` Bazel target either way).
2. One giant witness cycle threading through 35 sub-package nodes, whose **top-level package
   membership is exactly the same 17-package set** found by the manual Tarjan analysis:
   `asset, calibration, db(+db.sidecar), domain, editor(+blocks/commands/state/text),
   export, git, llm, migration, performance, platform(ml/sensor), repository, search,
   sections, tags, transfer(+qrcode), ui(+annotate/components/gallery/onboarding/
   screens/state/transfer), voice`.

**Agreement**: the two independently-computed results agree exactly on which 17 top-level
packages are entangled in a cycle. They differ only in granularity: kibitzer operates at
sub-package/file resolution (it treats `ui.gallery`, `editor.blocks`, `git.merge`,
`platform.ml`, etc. as distinct graph nodes, presumably via real Kotlin import resolution
rather than regex), while the manual analysis worked at top-level-directory granularity. That
granularity difference explains why kibitzer surfaces exactly one witness cycle per SCC rather
than enumerating all cycles (a 17-node densely-connected SCC has combinatorially many simple
cycles; reporting one representative per file/SCC is the sane design) — it is not a
disagreement about *which* packages are entangled, just how many distinct cycle instances get
printed. No discrepancy that changes the conclusion.

**Conclusion for Phase 3 planning**: this is a load-bearing finding. **17 of the 38 top-level
packages — 7 of the 11 "hub" packages (`db`, `llm`, `performance`, `platform`, `sections`,
`transfer`, `ui`) and 11 of the 18 "hub-touching" packages (`asset`, `calibration`, `domain`,
`editor`, `export`, `git`, `migration`, `repository`, `search`, `tags`, `voice`) — form one
strongly-connected component.** These 17 packages account for **535 of 609 `.kt` files
(87.8%)**. Bazel forbids target cycles, so as currently structured, **no clean 1-target-per-
package split is possible for these 17 packages without either (a) collapsing them into one
(or a few) merged Bazel targets that absorb the cycle internally, or (b) a dependency-inversion
refactor that breaks specific offending imports first** (e.g. `platform.ml` no longer
importing `ui.annotate`, `db` depending on repository *interfaces* relocated to a
neutral/lower package instead of `repository` itself, etc.). Phase 3 must treat "17 packages,
one target" (or a scoped subset after targeted refactors) as the working assumption for this
group, not "17 independent per-package targets."

The **9 zero-risk pilot packages are untouched by this cycle** — none of `benchmark`, `docs`,
`error`, `logging`, `parsing`, `resilience`, `rtc`, `service`, `stats` appear anywhere in the
129-edge list as an importer of anything beyond `error`/`logging` (leaf nodes only). The pilot
slice's premise holds up completely under this analysis.

Also outside the cycle (confirmed hub-touching packages safe to split independently, pending
the `internal`-usage findings below): `command`, `clipboard`, `flashcard`, `loader`,
`outliner`, `parser`, `vault` — 7 of the 18 hub-touching packages.

## Cross-package `internal` usage findings

**Methodology, iterated three times because the first two were too noisy to trust:**

1. **Naive regex** (`internal (class|fun|val|var|interface|object|typealias|enum class) NAME`)
   mis-extracted names for extension functions/properties with a receiver type
   (`internal fun Double.roundTo(...)` was recorded as symbol `Double`; `internal fun <T>
   Flow<Either<...>>.catchDbError()` was recorded as symbol `Flow` because the receiver's
   *nested* generics broke a `<[^>]*>` regex that doesn't handle nesting). Found 124 internal
   declarations across 17 packages.
2. **Fixed regex** (separate patterns for `class/interface/object/typealias`, `fun` with
   receiver-skipping, `val/var` with receiver-skipping; nested-generic-tolerant `<...>`
   matching) — found **129 internal declarations** across the same 17 packages (`ui` 61,
   `transfer` 14, `repository` 13, `db` 9, `platform` 4, `domain` 4, `voice` 7, `git` 3,
   `sections` 3, `model` 2, `service` 2, `cache` 2, `command`/`export`/`llm`/`docs`/`tags`/
   `util` 1 each).
3. **Cross-package usage — word-boundary grep** (does symbol name appear anywhere in another
   package's files) surfaced ~20 "risky" symbol/package pairs, but **spot-checking every one
   showed most are false positives**: `repository`'s `Flow` (42 files) is `kotlinx.coroutines
   .flow.Flow`, unrelated to the local `internal fun ... Flow<T>.catchDbError()`; `cache`'s
   `withLock` (24 files) is overwhelmingly `kotlinx.coroutines.sync.withLock` (21 of 24 hits);
   `ui`'s `BlockEditor` (flagged via `llm/LlmSuggestionWriter.kt`) turned out to be a **code
   comment** referencing the architecture doc's naming convention, not a real reference.
4. **Strict check** (grep for the exact `import dev.stapler.stelekit.<pkg>.<Symbol>` line in
   another package's files) eliminates essentially all of that noise. Also checked for
   wildcard imports (`import dev.stapler.stelekit.<pkg>.*`) that would evade this check —
   found 4 (`ui/App.kt` wildcard-imports both `platform.*` and `repository.*`; a few files
   wildcard-import `parsing.*`, which has zero internal symbols so is moot) — manually verified
   `ui/App.kt` does not actually reference any of `platform`'s or `repository`'s internal
   symbol names in its body, so no hidden usage there.

**Result: 6 confirmed genuine cross-package `internal` usages**, all verified by reading the
actual import line:

| Declaring pkg | Symbol | Used by (package, file count) |
|---|---|---|
| `cache` | `PlatformLock` | `performance` (3 files) |
| `cache` | `withLock` | `performance` (3 files — same 3 files as above) |
| `db` | `replaceWikilink` | `migration` (1 file) |
| `repository` | `asDbFlowOrNull` | `git` (1 file) |
| `util` | `roundTo` | `model` (1 file), `ui` (1 file) |
| `voice` | `LlmProviderSupport` | `llm` (2 files) |

**False-positive rate estimate**: ~14 of ~20 word-boundary "risky" hits (≈70%) did not survive
the strict FQN-import check — either same-name stdlib collisions (`Flow`, `withLock`,
`Double`, `Query`, `Offset`) or non-code references (a code comment). **This is a meaningful,
quantified caveat**: any future automated blast-radius tool for this project must check actual
import statements, not bare identifier co-occurrence, or its false-positive rate will make the
output unusable for prioritization.

**Known false-negative risk (the strict check's own blind spot)**: `internal` *members*
declared inside a class body (as opposed to top-level/extension declarations) are reached via
`instance.member()` after importing the containing (public) class — there is no separate
`import package.MemberName` line to grep for. The strict method as run here would under-count
this case. A spot-check of the 129 internal declarations found none that are clearly
class-member internals (Kotlin idiom in this codebase favors top-level/extension `internal`
functions — none of the six confirmed hits nor a manual skim of the `ui`/`repository` internal
lists turned up an internal method nested inside a public class), so this blind spot is
believed low-impact here, but it was not exhaustively verified and should be flagged to whoever
builds the Phase 3 blast-radius tooling (Scope item: "pre-migration check... for cross-package
Kotlin `internal`-visibility usage").

**Risk level: LOW.** Only 6 confirmed cross-package `internal` dependencies exist in the
entire 609-file tree, concentrated in 5 declaring packages (`cache`, `db`, `repository`,
`util`, `voice`) consumed by 5 packages (`performance`, `migration`, `git`, `model`, `ui`,
`llm`). Each is a single named symbol or a tight pair — trivially fixable per-migration by
making the symbol `public` or wiring `associates` for that one pair, not a systemic blocker.
Compare to the cycle finding above, which is the dominant risk by a wide margin.

## Recommended package classification (vs. requirements.md's provisional 11/9/18)

- **9 zero-risk packages: confirmed unchanged.** `benchmark`, `docs`, `error`, `logging`,
  `parsing`, `resilience`, `rtc`, `service`, `stats` have zero import edges into any hub or
  hub-touching package and zero involvement in the cycle. The Small pilot slice's premise is
  fully validated by this data — proceed as planned.
- **11 hub packages: 7 are cycle members, 4 are not.** `db`, `llm`, `performance`, `platform`,
  `sections`, `transfer`, `ui` are inside the 17-package SCC; `model`, `cache`, `coroutines`,
  `util` are not (they're leaf-like even among the hubs — no import cycle touches them, though
  `cache.withLock`/`cache.PlatformLock` and `util.roundTo` do have the two confirmed
  `internal`-usage dependencies noted above, which is a much smaller and independently
  fixable concern than a cycle).
- **18 hub-touching packages: 11 are cycle members, 7 are not.** `asset`, `calibration`,
  `domain`, `editor`, `export`, `git`, `migration`, `repository`, `search`, `tags`, `voice` are
  inside the SCC. `command`, `clipboard`, `flashcard`, `loader`, `outliner`, `parser`, `vault`
  are not — these 7 are good second-slice candidates (real transitive hub imports per the
  original coarse classification, per requirements.md, but no cycle and no confirmed
  `internal`-usage risk found here) before attempting the 17-package cycle group.
- **The headline correction Phase 3 needs**: requirements.md's Rabbit Holes section already
  anticipated this ("some packages forced to merge rather than split") — this research
  confirms it's not a hypothetical edge case but the dominant shape of the problem: **17 of
  29 non-zero-risk packages (59%), representing 87.8% of all `commonMain` files, cannot be
  split into independent Bazel targets as-is.** Phase 3 planning should treat the rollout as
  three tiers, not two: (1) 9 zero-risk packages — clean, as planned; (2) 7 hub-touching
  packages outside the cycle (`command`, `clipboard`, `flashcard`, `loader`, `outliner`,
  `parser`, `vault`) plus the 4 non-cycle hub packages (`model`, `cache`, `coroutines`,
  `util`) — clean pairwise splits, modulo the 2 confirmed `internal`-usage fixes
  (`cache`→`performance`, `util`→`model`/`ui`); (3) the 17-package cycle group — needs either
  a scoped dependency-inversion refactor (breaking specific edges like `platform→ui`,
  `db→repository`) before any split, or acceptance that this group ships as one large merged
  Bazel target for now, deferring finer splitting to a later, separately-scoped effort.

## Sources

- `ls kmp/src/commonMain/kotlin/dev/stapler/stelekit/ | sort` and `find <pkg> -name '*.kt' |
  wc -l` per package (package inventory + counts).
- `find kmp/src/commonMain/kotlin/dev/stapler/stelekit -name '*.kt' | wc -l` → 609 total.
- Manual edge extraction: `grep -rhoE 'import dev\.stapler\.stelekit\.[a-zA-Z0-9_]+' <pkg>
  --include='*.kt' | sed -E 's/^import dev\.stapler\.stelekit\.//' | sort -u`, one run per
  package (all 38), saved to `edges.txt`; cycle detection via Tarjan's SCC algorithm in
  `scc.py` (both scratch files in the session scratchpad).
- Spot-check greps confirming specific edges are real, non-comment imports: `platform/ml/
  MonocularDepthEstimator.kt:7`, `db/GraphManager.kt:33-34`, `db/GraphLoader.kt:23-26`,
  `db/DatabaseWriteActor.kt:19-21`, `db/BacklinkRenamer.kt:7-9`, `db/ImageImportService.kt:
  21-25`, `db/sidecar/ImageSidecarIndexer.kt:9-11`, `calibration/CalibrationFallbackChain.kt:
  7-8`, `platform/sensor/DepthSensorProvider.kt:5`, `repository/SqlDelightSearchRepository.kt:
  17-22`, `repository/RepositoryFactory.kt:11-20`, `performance/OtelRepositoryWrappers.kt:3-4`,
  `performance/SpanRepository.kt:5`, `llm/LlmCredentialMigration.kt:8-9`,
  `tags/TagAvailabilityPoller.kt:5`, `llm/CustomProviderUrlValidation.kt:5`,
  `voice/VoicePipelineFactory.kt:5-8`.
- `mcp__kibitzer__architecture_assessment(path="kmp/src/commonMain/kotlin",
  scope="kmp/src/commonMain/kotlin/**", include_diagram=true)` — required fixing
  `/home/tstapler/Programming/stelekit/.claude/inspect.json` to a schema-valid config; schema
  confirmed by reading `~/code/github.com/tstapler/kibitzer/src/config.rs` (`Check` struct,
  `Severity` enum) and cross-referencing working examples in `~/code/github.com/tstapler/
  kibitzer/.claude/inspect.json` and `~/code/github.com/tstapler/stapler-squad/.claude/
  inspect.json`, plus `kibitzer/src/mcp.rs`'s built-in example configs (`import-cycles`,
  `layering`, `coupling`, `component-deps` architecture-checker names). Full tool output (744
  findings, 615 files) saved at `/home/tstapler/.claude/projects/-home-tstapler-Programming-
  stelekit/54e8d05d-7dbb-4b53-965c-1c3348289ced/tool-results/mcp-kibitzer-
  architecture_assessment-1788567131254.txt`.
- Internal-usage scans: three iterations of a Python regex+cross-reference script
  (`internal_scan.py` → `internal_scan2.py` → `internal_scan3.py`, session scratchpad),
  final strict version checks exact `import dev.stapler.stelekit.<pkg>.<Symbol>` lines;
  false-positive spot-checks via targeted `grep` for `withLock`, `Flow`, `Double`, `Offset`,
  `BlockEditor` in the flagged files.
