---
name: stelekit-bazel-perf
description: SteleKit-specific Bazel/CI performance history and constraints (16GB GitHub runner, Android persistent-worker attempt, monolithic android_main target). Use with the generic bazel-perf skill when speeding up SteleKit Bazel builds or its GitHub Actions jobs.
---

# SteleKit Bazel performance notes

Method (profile, elapsed vs. critical path, strategy tally) is in the generic `bazel-perf` skill. This file holds only what is true of this repo.

## Setup facts

- Bazel 9.2.0 (no `.bazelversion` pin; Bazelisk resolves latest). `analyze-profile` is gone; use the JSON trace profile.
- Targets worth profiling: `bazel build //kmp:android_app --config=android --profile=/tmp/profile.json.gz`, `bazel test //kmp:jvm_tests --profile=...`.
- CI: `.github/workflows/bazel-ci.yml` on standard GitHub runners (4 vCPU, 16GB RAM). Cache is `setup-bazel`'s, ~10GB/repo, so near-zero cache hits on a branch that should be warm point at eviction or key churn. Check that config before touching build flags.
- Cache-hit counts print with `--config=remote-cache` (`INFO: N processes: X action cache hit, ...`).
- Strategy tally for a job: `gh run view <run-id> --log | grep "<job name>" | grep -oP '; \d+s \K[a-z0-9, -]+(?=\.\.\.)' | sort | uniq -c | sort -rn`.

## Tried and reverted: Android persistent workers

`build:android --persistent_android_dex_desugar` and `--persistent_android_resource_processor` moved Desugar, DexBuilder and PackageAndroidResources from `processwrapper-sandbox` to `worker` mode. On `//kmp:android_app` this was most of a 6.4x elapsed/critical-path gap, but it does not fit the 16GB runner and was reverted after 3 failed CI runs (33929109869, 33931054162, 33931907031, 12 to 25 minutes each).

- The repo's `.bazelrc` documents these as `-Xmx3G` processes (the comment behind `--local_resources=memory=9000`). `--worker_max_instances=<Mnemonic>=2` on all three mnemonics is 6 x 3GB = 18GB, before the 6GB Bazel-server cap, `KotlinCompile`'s existing persistent worker and OS overhead.
- Local verification on a 61GB/24-core box showed zero swap and looked safe; it could never reach the ceiling.
- Attempt #2 failed because a mnemonic was missed. List what the flag really touches with `grep -oP 'bazel-workers/worker-\d+-\K\w+(?=\.log)'` on a failure log.
- Revisit only with a larger-memory runner or per-worker RSS measured on matching hardware.

## One giant target

`//kmp/src/androidMain/kotlin:android_main` is one `KotlinCompile` of 711 files: 71s, about half of a 138s critical path. Fix is splitting into smaller `kt_jvm_library`/`kt_android_library` targets (`:model`, `:repository`, `:db`, `:ui`). Check cycles first in both directions: `grep -rl "import dev.stapler.stelekit.<pkgA>" <pkgB>/`.

## Don't

- Don't raise `--local_resources=memory=` without reading the OOM comment above it in `.bazelrc` (run 33803270917: Bazel server OOM-killed at higher concurrency).
- Don't add `--jobs` beyond 4 on standard runners.
