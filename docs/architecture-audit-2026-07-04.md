# Architecture Hotspot Audit — 2026-07-04

Complexity × churn hotspot analysis (CodeScene technique, open-source tooling), adapted for this
Kotlin/KMP codebase. Method: temporal coupling from git history (language-agnostic) + a line-count
complexity proxy (Detekt's complexity rules are intentionally disabled in this repo — Compose
composables and parsers are legitimately long, per `kmp/config/detekt/detekt.yml`'s own comment) +
function-count as a God-Object signal, cross-referenced against existing ADRs and planning docs.

## Method

- **Window**: last 1000 commits, excluding `github-actions[bot]` (551/1000 commits were pure CI
  benchmark/demo-regeneration automation — including them would have swamped the signal with
  non-architectural noise).
- **Temporal coupling**: code-maat's core algorithm, reimplemented as a self-contained Python
  script (code-maat's Leiningen/Clojure toolchain wasn't worth standing up for a one-off run) —
  script preserved at the end of this doc for re-runs.
- **Complexity proxy**: line count per file. The real proxy (cyclomatic/cognitive complexity via
  a static analyzer) wasn't available here since this repo deliberately disables Detekt's
  `CyclomaticComplexMethod` rule; line count is the documented fallback for exactly this case.
- **Static structural signal**: function count per file (a Kotlin-native God-Object proxy, in
  place of Go's `gocyclo`/struct-field-count tooling, which doesn't apply to this stack).
- **Cross-reference**: grepped `docs/adr/` (16 ADRs) and every `project_plans/*/requirements.md`
  and `project_plans/*/implementation/plan.md` for mentions of each top hotspot file.

## Top hotspots (revisions × line count, generated code excluded from ranking)

| Rank | File | Revisions | Lines | Functions | Score | Prior docs mentioning it |
|---|---|---|---|---|---|---|
| 1 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | 56 | 1,865 | 7 | 104,440 | 18 |
| 2 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` | 37 | 2,574 | **139** | 95,238 | 15 |
| 3 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` | 34 | 1,844 | 54 | 62,696 | 11 |
| 4 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt` | 36 | 1,387 | 58 | 49,932 | 6 |
| 5 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt` | 23 | 1,577 | 80 | 36,271 | 9 |
| 6 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MigrationRunner.kt` | 29 | 1,041 | — | 30,189 | 7 |
| 7 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt` | 15 | 921 | — | 13,815 | 7 |
| 8 | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/RestrictedDatabaseQueries.kt` | 17 | 679 | — | 11,543 | — |

(Generated file `kmp/src/generated/sqldelight/.../SteleDatabaseQueries.kt` scored 30,572 by the
raw formula — excluded from ranking per the standard rule: mechanically-generated code is not a
refactor target, even though it's legitimately large.)

## Two distinct smells at the top, not one

- **`StelekitViewModel.kt`** — 139 functions in a single class. This is the textbook God Object:
  high complexity *and* high churn simultaneously, which is exactly the "where incidents come
  from" quadrant the complexity × churn framing exists to find. Every dispatcher-matrix rule,
  scope-ownership rule, and Flow-resilience rule in this repo's `CLAUDE.md` traces back to
  mistakes made in or around this file's history.
- **`App.kt`** — highest raw score (highest churn, second-highest line count) but only 7 top-level
  functions. The smell here is different: a handful of very large composable functions (root
  screen routing / global state wiring), not too many class responsibilities. This wants
  composable-extraction, not a SOLID/class-responsibility pass.

## Strongest temporal coupling pairs (top 10 of 40 found, min 3 shared commits)

| Shared commits | Ratio | Pair |
|---|---|---|
| 16 | 0.43 | `ui/App.kt` ↔ `ui/StelekitViewModel.kt` |
| 13 | 0.76 | `ui/AppState.kt` ↔ `ui/StelekitViewModel.kt` |
| 12 | 0.50 | `db/MigrationRunner.kt` ↔ `sqldelight/.../SteleDatabase.sq` |
| 12 | 0.71 | `db/RestrictedDatabaseQueries.kt` ↔ `sqldelight/.../SteleDatabase.sq` |
| 12 | 0.50 | `repository/SqlDelightBlockRepository.kt` ↔ `sqldelight/.../SteleDatabase.sq` |
| 10 | 0.83 | `sqldelight/.../SteleDatabase.sq` ↔ `jvmTest/.../QueryPlanAuditTest.kt` |
| 10 | 0.43 | `ui/App.kt` ↔ `ui/state/BlockStateManager.kt` |
| 10 | 0.29 | `db/GraphLoader.kt` ↔ `ui/StelekitViewModel.kt` |
| 9 | 0.53 | `ui/App.kt` ↔ `ui/AppState.kt` |
| 9 | 0.69 | `androidMain/.../DriverFactory.android.kt` ↔ `jvmMain/.../DriverFactory.jvm.kt` |

Most of these are expected/healthy (schema ↔ generated-query-consumer coupling, platform-pair
driver factories). One worth a second look: `error/DomainError.kt` co-changes with both `App.kt`
and `StelekitViewModel.kt` at ratio 0.75 (6 shared commits each) — a shared error-type file
changing in lockstep with both top God-Object candidates is a mild signal that error handling may
be more entangled with UI/ViewModel state than cleanly layered, though 6 commits is a thin sample
to be confident about.

## Recommendation

Point a targeted architecture review at `StelekitViewModel.kt` first — it is the only file in the
top ranks combining high churn, high complexity (by line count), *and* a clear structural smell
(139 functions in one class). `App.kt` is the close second but needs a different lens
(composable-extraction analysis, not class-responsibility/SOLID analysis) and should be a
follow-up pass, not bundled into the same review.

## Re-run script

```python
#!/usr/bin/env python3
"""Temporal coupling + hotspot analysis — code-maat's coupling algorithm, self-contained."""
import argparse
import itertools
import subprocess
from collections import Counter

def get_commits(n=None, since=None):
    cmd = ["git", "log", "--name-only", "--pretty=format:--%H--"]
    if n:
        cmd.insert(2, f"-n{n}")
    if since:
        cmd.insert(2, f"--since={since}")
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    commits, current_hash, current_files = [], None, []
    for line in out.splitlines():
        if line.startswith("--") and line.endswith("--") and len(line) == 44:
            if current_hash:
                commits.append((current_hash, current_files))
            current_hash, current_files = line[2:-2], []
        elif line.strip():
            current_files.append(line.strip())
    if current_hash:
        commits.append((current_hash, current_files))
    return commits

_author_cache = {}
def author_of(commit_hash):
    if commit_hash not in _author_cache:
        _author_cache[commit_hash] = subprocess.run(
            ["git", "log", "-1", "--pretty=format:%an", commit_hash],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    return _author_cache[commit_hash]

def compute_coupling(commits, max_files_per_commit=60, min_shared=3, exclude_authors=()):
    revisions, coupling = Counter(), Counter()
    for commit_hash, files in commits:
        if exclude_authors and author_of(commit_hash) in exclude_authors:
            continue
        for f in files:
            revisions[f] += 1
        if len(files) > max_files_per_commit:
            continue
        for a, b in itertools.combinations(sorted(set(files)), 2):
            coupling[(a, b)] += 1
    coupling = Counter({pair: n for pair, n in coupling.items() if n >= min_shared})
    return revisions, coupling

def hotspot_score(revisions, complexity_by_file=None, line_counts=None):
    proxy = complexity_by_file or line_counts or {}
    scores = {f: revisions[f] * proxy.get(f, 0) for f in revisions if f in proxy}
    return sorted(scores.items(), key=lambda kv: kv[1], reverse=True)

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--commits", type=int, default=1000)
    p.add_argument("--since", default=None)
    p.add_argument("--max-files-per-commit", type=int, default=60)
    p.add_argument("--min-shared", type=int, default=3)
    p.add_argument("--exclude-author", action="append", default=[])
    args = p.parse_args()

    commits = get_commits(n=args.commits, since=args.since)
    revisions, coupling = compute_coupling(
        commits, args.max_files_per_commit, args.min_shared, tuple(args.exclude_author)
    )

    print("# Top 30 files by revision count")
    for f, n in revisions.most_common(30):
        print(f"{n:5d}  {f}")

    print("\n# Top 40 co-change pairs (>= min-shared commits)")
    for (a, b), n in coupling.most_common(40):
        ratio = n / min(revisions[a], revisions[b])
        print(f"{n:3d}  ratio={ratio:.2f}  {a}  <->  {b}")
```

Usage: `python3 hotspot_analysis.py --commits 1000 --exclude-author "github-actions[bot]"`
