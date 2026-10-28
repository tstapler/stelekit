# coverage-map.json schema

Produced by `scripts/ci/coverage-map.py` from Gradle JaCoCo XML reports (plan Story 11.1). `coverage-map.md` is the same rows sorted by `rank`.

```
python3 scripts/ci/coverage-map.py \
  --jacoco kmp/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml \
  --jacoco kmp/build/reports/jacoco/jacocoAndroidTestReport/jacocoAndroidTestReport.xml:android \
  --tiers docs/coverage/coverage-tiers.yml --reachability-baseline scripts/ci/reachability-baseline.txt \
  --out docs/coverage --command "<the exact gradle command that produced the XMLs>"
```

## Top level

| Field | Meaning |
|---|---|
| `generatedAt` | UTC timestamp |
| `commit` | `git rev-parse HEAD` unless `--commit` is given |
| `runner` | The exact command that produced the XMLs (`--command`); Gradle only |
| `tiersFile` | sha256 of the tiers file used |
| `packages` | One row per (package, source set), sorted by `rank` |

## Package row

| Field | Meaning |
|---|---|
| `pkg` | First path segment under `dev/stapler/stelekit` (`ui`, `db`, ...; `(root)` for classes directly in it) |
| `sourceSet` | `jvm` (default) or the suffix given to `--jacoco PATH:suffix` (`android`) |
| `tier` | `T1`..`T4` from the tiers file (flat `package: T1` lines); a package with no tier fails the run listing every such package |
| `lines`, `branches` | `{covered, total}` over **reachable** source files only |
| `mutation` | `{killed, total, measured}` from `--mutations` (PIT `mutations.xml`); `measured` is false without it |
| `commits90d` | Commits in the last 90 days touching `kmp/src/**/dev/stapler/stelekit/<pkg>/` (`git log`, or `--churn-json`) |
| `reachablePct` | Share of the package's source files not named for a type in the reachability baseline. The match is by file basename (a file named `Foo.kt` is unreachable when `Foo` is in the baseline), so a file holding a live type beside a baselined one is counted reachable. INFERRED approximation |
| `score` | `uncoveredLines x tierWeight x log2(2 + commits90d)`; weights T1=4, T2=3, T3=1, T4=0 (the plan fixes T1=4 only; T2/T3 are this script's assumption) |
| `rank` | 1 = largest `score` |
| `ownerPlan` | Optional owning plan unit from `--owner-plan-json` |
| `target` | `{line, branch}` percentage target for the tier (T1 90/80, T2 80/70, T3 60/40; assumption until Story 11.2 replaces it with measured targets) |

Unreachable files are removed from the `lines`/`branches` denominators, so deleting dead code never scores as a coverage regression (pitfalls E1).

## Refusals

- `--runner bazel`, or an XML path under `bazel-out/`: Bazel and Gradle coverage numbers are not comparable (pitfalls E6).
- A package with no tier.
