# KMP Migration Bug Report

## 🔴 Critical / Blocking Issues

### 1. Android Target Compilation Failure
*   **Status**: ⛔ Blocking
*   **Symptom**: `Internal compiler error` during `ExternalPackageParentPatcherLowering`.
*   **Cause**: Incompatibility between Kotlin Compiler (tested 2.0.21 and 1.9.24) and SQLDelight plugin (2.0.2) generated code IR lowering on Android target.
*   **Mitigation**: Android target is temporarily disabled in `kmp/build.gradle.kts`.

### 2. JS Target Configuration Failure
*   **Status**: ⛔ Blocking
*   **Symptom**: `OutOfMemoryError: GC overhead limit exceeded` and `Node.js` binary resolution failure during Gradle configuration.
*   **Cause**: Kotlin JS plugin environment configuration issues.
*   **Mitigation**: JS target is temporarily disabled.

## 🟡 Resolved Issues

### 3. JVM Runtime Crash (Skiko Incompatibility)
*   **Status**: ✅ Fixed
*   **Symptom**: `java.lang.NoSuchMethodError: 'void org.jetbrains.skiko.SkiaLayer.<init>(...)'` at runtime.
*   **Resolution**: Resolved by removing manual Skiko version pinning and allowing Compose Multiplatform 1.7.1 to manage its own compatible transitive dependencies. This correctly resolves to Skiko `0.8.18`, which is compatible with Kotlin 2.0.21.

### 4. JVM Internal Compiler Error
*   **Status**: ✅ Fixed
*   **Resolution**: While a downgrade to `1.9.24` was previously used as a workaround, the project now successfully compiles and runs on Kotlin `2.0.21` by allowing transitive dependency resolution.

### 5. Missing Platform Implementations
*   **Status**: ✅ Fixed
*   **Details**: 
    *   `GitManager` was missing a `jvmMain` implementation for `GitManagerFactory`. Added a stub implementation.
    *   `PlatformFileSystem` on Android was rewritten to use `java.io.File` to avoid API level issues with `java.nio.file`.

### 6. Compose Resources & Icons
*   **Status**: ✅ Fixed
*   **Details**: Fixed `Sidebar.kt` using deprecated wildcard imports for Material Icons. Updated to specific imports (e.g., `Icons.AutoMirrored.Filled.List`).

## 🔍 Pre-existing Issues (Legacy)
*   **ClojureScript Lint Errors**: Multiple unresolved symbols in legacy `.clj` and `.cljs` files (`file_sync_actions.clj`, `security.cljs`). These do not affect the KMP build but may impact the legacy app.
