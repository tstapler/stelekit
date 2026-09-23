# ADR-001: JNI over JNA/Panama for native bridge

**Status**: Accepted
**Date**: 2026-06-17

## Context

The libsql-mvcc driver requires calling into a Rust `cdylib` from two JVM-based platforms: desktop JVM
(Linux/macOS/Windows) and Android. Three native-bridge options exist for Kotlin/JVM code:

- **JNI** — Java Native Interface, part of the JVM spec since 1.1; supported on all JVM implementations
  and Android.
- **JNA** — Java Native Access; a library that generates native stubs at runtime via `libffi`, with no
  hand-written C glue. Requires an additional native stub-loader (`jna-platform`) and JNA JAR on the
  classpath.
- **Project Panama / Foreign Function & Memory API** — JDK 22+ preview (stabilized as `java.lang.foreign`
  in JDK 22). Not available on Android; requires `--enable-preview` or a recent JDK; the API is still
  evolving.

Android is a hard constraint: SteleKit ships on Android API 21+. Project Panama is absent from the
Android runtime entirely. JNA can be compiled for Android but requires additional native stubs per
architecture (arm64-v8a, armeabi-v7a, x86_64) on top of the `cdylib` itself.

## Decision

Use **JNI** via `extern "C" #[no_mangle]` exports in Rust and corresponding `external fun` declarations
in a `LibsqlJni` Kotlin object. The same `cdylib` target serves both JVM and Android; the JNI symbol
naming convention (`Java_dev_stapler_stelekit_db_libsql_LibsqlJni_<method>`) is identical on both
runtimes as long as the Kotlin object lives in the same package on both platforms.

## Rationale

- **Zero extra dependencies**: JNI is part of every JVM and the Android NDK. JNA requires a JAR + native
  stubs; Panama requires JDK 22+.
- **Single binary per architecture**: The Rust `cdylib` compiled with `rules_rust` / `cargo` is the only
  artifact needed; no secondary stub loader.
- **Proven on Android**: All Android NDK libraries use JNI. JNA's Android story is community-maintained
  and has historically lagged behind NDK releases.
- **Bazel `rules_rust` support**: `cdylib` crate type produces `.so` / `.dylib` / `.dll` directly; Bazel
  can cross-compile for Android ABI targets without Panama/JNA layering.
- **Package-path consistency**: By placing `LibsqlJni.kt` in `dev.stapler.stelekit.db.libsql` on both
  `jvmMain` and `androidMain`, the generated JNI symbol prefix is identical, eliminating the need for
  two separate native entry-point sets.

## Consequences

- All JNI functions in `lib.rs` must carry `#[no_mangle]` and `extern "C"` and must follow the
  `Java_<package>_<class>_<method>` naming scheme exactly. A typo produces `UnsatisfiedLinkError` at
  runtime with no compile-time warning.
- JNI signatures are stringly-typed on the Rust side (`JNIEnv`, `JClass`, `JObject`, `jlong`, etc.).
  Correctness must be verified via integration tests rather than the compiler.
- Exception propagation across the JNI boundary requires explicit `env.throw_new(...)` calls in Rust;
  unhandled Rust panics in JNI callbacks are undefined behavior and will likely crash the process.
  All JNI functions must catch panics with `std::panic::catch_unwind`.
- Library loading (`System.loadLibrary("stelekit_libsql")`) must occur before any `external fun` is
  invoked; `LibsqlJni` companion object's `init` block is the canonical location.
