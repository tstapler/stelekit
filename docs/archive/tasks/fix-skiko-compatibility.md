# Fix Skiko Compatibility Issue

## Objective
Resolve the JVM runtime crash caused by binary incompatibility between androidx.compose (1.6.11) and the resolved skiko-awt version.

## Prerequisites
- Understanding of the current Skiko version in use (0.8.18)
- Knowledge of Compose Desktop version (1.7.1)
- Familiarity with Gradle dependency management

## Atomic Steps
1. **Diagnose the exact version mismatch**
   - Check current androidx.compose version in build.gradle.kts
   - Verify which Skiko version is being resolved transitively
   - Identify the specific NoSuchMethodError signature

2. **Determine compatible Skiko version**
   - Research compatibility matrix between Compose Desktop 1.7.1 and Skiko versions
   - Test with recommended Skiko version (0.7.85 or 0.8.9 as mentioned in bug report)

3. **Apply version pinning**
   - Add explicit Skiko dependency to force compatible version
   - Update kmp/build.gradle.kts with proper Skiko version constraint
   - Ensure the version works for all targets (JVM, Android, iOS)

4. **Verify the fix**
   - Run ./gradlew :kmp:jvmTest to ensure tests pass
   - Run ./gradlew :kmp:runApp to verify application starts correctly
   - Check for any regressions in UI rendering

## Validation
- Application starts without NoSuchMethodError
- UI renders correctly in desktop application
- All existing tests continue to pass
- No new compilation errors introduced

## Links
- Related bug: kmp_migration_issues.md (JVM Runtime Crash - Skiko Incompatibility)
- TODO.md entry for JVM/Desktop build status