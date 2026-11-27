# Spike results: W0 (oracle), S1 (compile outside Gradle), S2, S4, S7 — 2026-11-27

Verdict: **the compile, link and optimize steps can leave Gradle.** Replayed outside Gradle with the
compiler Bazel already downloads, all outputs are byte-for-byte what Gradle produced.

Reproduce: `replay_gradle_wasm.py` (usage in its docstring). Linux x86-64, JDK 21, Kotlin 2.4.10.

| Step | Time | Output | Compared with Gradle |
|---|---|---|---|
| klib (`K2JSCompiler`, 1898 sources, 83 libraries) | 61-81 s | `klib/` | not compared directly |
| link (`-Xir-produce-js -Xir-dce`) | 66-67 s | `.wasm` 64,835,025 B + 3 `.mjs` | identical |
| `wasm-opt` (Binaryen 125, flags read from the Gradle log) | 211-232 s | 20,469,808 B | identical |

## What this settles
- **S1:** `-Xfragments=wasmJsMain,webMain,commonMain` with `-Xfragment-refines` and `-Xfragment-sources`
  works as passed by Gradle. `-Xcommon-sources` was not needed or tried.
- **S2:** no rules_kotlin patch needed for the compiler. With `use_repo` of
  `com_github_jetbrains_kotlin_git`, `//:lib/kotlin-compiler_jar`, `compose-compiler-plugin_jar`,
  `kotlinx-serialization-compiler-plugin_jar` and `kotlin-stdlib-wasm-js_klib` are public targets.
  The bundled plugins give the same bytes as Gradle's `-embeddable` ones.
- **S4:** Gradle passes no `-language-version`, so a direct launcher uses the compiler default and
  the 2.1 setting of `//:kotlin_toolchain` does not apply.
- **S7:** Binaryen 125. The sizes in plan.md section 0.2 (36 MB, 10.9 MB) are stale.

## Still borrowed from Gradle (the work that remains)
- **Libraries:** 83 files from Gradle's cache (`wasmjs-klib-closure.tsv`: GAV, file, sha256). W2.
- **Generated sources:** 66 of the 1898 — 61 SQLDelight, 4 Compose resources, 1 version file. The
  committed `kmp/src/generated/sqldelight*` match byte for byte, but `sqldelight/` also holds files
  Gradle no longer generates (`Debug_flags.kt`, `Spans.kt`, `Query_stats.kt` and others) that a
  Bazel wasm target must not compile. W1, W3.
- **Absolute paths:** the replay used them; path independence under Bazel's sandbox is untested.
- **Not attempted:** skiko runtime processing (S3), bundling without webpack (S5), tests without
  Karma (S6), Compose resources.
