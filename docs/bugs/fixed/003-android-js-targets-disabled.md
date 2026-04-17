## 🐛 BUG-003: Android and JS Targets Disabled [SEVERITY: Medium]

**Status**: ✅ Fixed
**Discovered**: 2026-01-11 during Project Analysis
**Impact**: Multiplatform capabilities are currently limited to JVM/Desktop and iOS. Android and JS targets are disabled in build configuration.

**Reproduction**:
1. Check `kmp/build.gradle.kts`
2. Observe `if (project.findProperty("enableJs") == "true")` and `if (project.findProperty("enableAndroid") == "true")` blocks
3. Check `gradle.properties` and see these properties are not set to true

**Root Cause**:
- **Android**: Incompatibility between Kotlin Compiler (2.0.21) and SQLDelight plugin (2.0.2) generated code IR lowering.
- **JS**: `OutOfMemoryError` and Node.js binary resolution failures during configuration.

**Files Affected** (2 files):
- `kmp/build.gradle.kts` - Targets conditionally enabled
- `gradle.properties` - Flags missing

**Fix Approach**:
1. Investigate SQLDelight compatibility with Kotlin 2.0.21 for Android.
2. Fix memory settings and Node.js configuration for JS target.
3. Re-enable targets in `gradle.properties`.

**Progress**:
- **Android**: ✅ FIXED - Added `DriverFactory.android.kt` with proper AndroidSqliteDriver initialization using reflection
- **JS**: ✅ FIXED - Added JS expect/actual declarations with SQLDelight web-worker-driver and SQL.js

**Files Modified**:
- `kmp/src/androidMain/kotlin/com/logseq/kmp/db/DriverFactory.android.kt` - NEW
- `kmp/src/commonMain/kotlin/com/logseq/kmp/db/PlatformUtils.kt` - NEW
- `kmp/src/jvmMain/kotlin/com/logseq/kmp/db/PlatformUtils.jvm.kt` - NEW
- `kmp/src/androidMain/kotlin/com/logseq/kmp/db/PlatformUtils.android.kt` - NEW
- `kmp/src/jsMain/kotlin/com/logseq/kmp/db/PlatformUtils.js.kt` - NEW
- `kmp/src/jsMain/kotlin/com/logseq/kmp/db/DriverFactory.js.kt` - NEW
- `kmp/webpack.config.d/sqljs-config.js` - NEW
- Updated `SqlDelightBlockRepository.kt` and `SqlDelightReferenceRepository.kt` to use `Clock.System.now()` instead of `System.currentTimeMillis()`
- Updated `GraphManager.kt` to use platform-independent APIs
- Updated SQLDelight to version 2.1.0 for JS driver compatibility

**Verification**:
- [x] Run `./gradlew :kmp:compileDebugKotlinAndroid` successfully.
- [x] Run `./gradlew :kmp:compileKotlinJs` successfully.
- [x] Run `./gradlew :kmp:compileKotlinJvm` successfully.

**Notes**:
- JS target now uses SQLDelight web-worker-driver with SQL.js worker
- Browser will use WebWorker for async database operations
- Webpack config updated to copy sql-wasm.wasm to output directory

**Related Tasks**: 
- KMP Migration
