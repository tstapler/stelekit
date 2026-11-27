# Spike results: wasm compile outside Gradle — 2026-11-27

Verdict: **the compiler and optimizer runs reproduce outside Gradle's task execution.** Replayed with
the Kotlin compiler Bazel downloads, but with **Gradle's argument lists, libraries, generated sources
and `wasm-opt` binary**, the `klib/` directory, `.wasm`, the three `.mjs` files and the optimized
`.wasm` are identical to Gradle's. The source map is not: its paths are relative to the output
directory, so at least that output depends on where it is built.

Reproduce: `replay_gradle_wasm.py` (usage in its docstring). Linux x86-64, JDK 21, Kotlin 2.4.10.

| Step | Time (two runs plus one by hand) | Output |
|---|---|---|
| klib (`K2JSCompiler`, 1898 sources, 83 libraries) | 61-81 s | `klib/` |
| link (`-Xir-produce-js -Xir-dce`, 92 libraries) | 66-102 s | `.wasm` 64,835,025 B + 3 `.mjs` |
| `wasm-opt` (Binaryen 125) | 211-264 s | 20,469,808 B |

## Findings
- **Fragments:** `-Xfragments=wasmJsMain,webMain,commonMain` with `-Xfragment-refines` and
  `-Xfragment-sources` works as Gradle passes it. `-Xcommon-sources` was not tried.
- **Compiler access (plan S2):** a temporary `use_repo` of `com_github_jetbrains_kotlin_git` plus
  `bazel query` listed `//:lib/kotlin-compiler_jar`, `//:lib/compose-compiler-plugin_jar`,
  `//:lib/kotlinx-serialization-compiler-plugin_jar` and `//:lib/kotlin-stdlib-wasm-js_klib`. So no
  rules_kotlin patch is expected for the compiler; no rule has been built against them yet. The
  bundled plugins give the same bytes as Gradle's `-embeddable` ones. That stdlib klib has the same
  sha256 as Gradle's.
- **Language level:** Gradle passes no `-language-version`.
- **Sizes:** plan.md section 0.2 (36 MB, 10.9 MB) is stale.

## Still borrowed from Gradle
- **Argument lists:** source list, fragment assignment, flags and library order. Bazel must produce these.
- **Libraries:** 98 files, 83 for compile and 92 for link (`wasmjs-klib-closure.tsv`: GAV, file, scope, sha256).
- **Generated sources:** 66 of the 1898 — 61 SQLDelight, 4 Compose resources, 1 version file. The 61
  match the committed `kmp/src/generated/sqldelight*`, but `sqldelight/` also holds files Gradle no
  longer generates (`Debug_flags.kt`, `Spans.kt`, `Query_stats.kt` and others).
- **Binaryen:** the binary and flags.

## Not done from plan.md
- **W0:** webpack config, golden listing of the whole distribution, section 0.3 updates.
- **S2:** whether the Build Tools API has a JS/Wasm operation.
- **S4:** compiling at language/API 2.1.
- **S7:** the 5% size criterion refers to a stale figure; byte equality of `wasm-opt` replaces it.
- **Untried:** Bazel sandbox, skiko processing (S3), bundling without webpack (S5), tests without
  Karma (S6), Compose resources.
