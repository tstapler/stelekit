# BUG-002: Benchmark Workflow Jobs Silently Skip on Push to Main [SEVERITY: High]

**Status**: 🐛 Open
**Discovered**: 2026-04-26
**Impact**: Benchmark history is never committed on push to `main`. The `android-benchmark` and `benchmark` workflows trigger on push but the job-level `if` condition silently evaluates to `false`, causing both jobs to be skipped every time. As a result, `benchmarks/history/` and `benchmarks/android-history/` are never updated on main, and the PR comparison baseline is always stale or missing.

## Problem Description

Both `.github/workflows/benchmark.yml` and `.github/workflows/android-benchmark.yml` use the following job-level condition to exclude draft PRs:

```yaml
if: github.event.pull_request.draft == false
```

On **push** events (not pull_request events), `github.event.pull_request` is an empty object and `.draft` resolves to `null`. In GitHub Actions expression evaluation, `null == false` is `false`, so the job is skipped entirely. The workflows are configured to trigger on both `push` (branches: [main]) and `pull_request`, but the jobs only ever run on non-draft pull_request events.

## Reproduction Steps

1. Merge a PR into `main` (triggers a push event on main).
2. Observe the "Android Benchmark" and "Load Benchmark" workflow runs in the Actions tab.
3. Expected: Both jobs run, collect results, and commit benchmark JSON files to `benchmarks/*-history/`.
4. Actual: Both jobs show as "skipped" — the `if` condition evaluates to `false` because `github.event.pull_request.draft` is `null` on a push event.

## Root Cause

The `if` guard intended to skip draft PRs (`github.event.pull_request.draft == false`) has a type-coercion side effect: when there is no PR context (push events), `.draft` is `null`, and GitHub's expression engine evaluates `null == false` as `false`.

## Files Likely Affected

- `.github/workflows/benchmark.yml` — line with `if: github.event.pull_request.draft == false`
- `.github/workflows/android-benchmark.yml` — same condition

## Fix Approach

Add an explicit push-event guard so the condition only applies to PR events:

```yaml
if: github.event_name == 'push' || github.event.pull_request.draft == false
```

This allows push events through unconditionally and preserves the draft-skip behavior for PR events.

## Verification

1. Apply the fix to both workflow files.
2. Merge a commit to `main`.
3. Confirm both benchmark jobs run (not skipped) in the Actions tab.
4. Confirm new JSON files appear in `benchmarks/history/` and `benchmarks/android-history/` in the repository after the run.

## Related Tasks

- Discovered during PR #35 (`stelekit-action-failing` branch) CI review
- `.github/workflows/benchmark.yml` and `android-benchmark.yml` both need the same one-line fix
