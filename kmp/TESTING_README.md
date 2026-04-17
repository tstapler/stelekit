# Testing Infrastructure for Logseq KMP

This document outlines the comprehensive testing infrastructure implemented for the Logseq Kotlin Multiplatform project.

## Testing Structure

### Source Sets
- **commonTest**: Shared test code across all platforms
- **businessTest**: Business logic tests (models, repositories, ViewModels) - no UI dependencies
- **jvmTest**: JVM-specific tests
- **androidUnitTest**: Android unit tests
- **iosTest**: iOS tests

### Test Categories

#### 1. Unit Tests for Data Models
- **File**: `src/businessTest/kotlin/com/logseq/kmp/model/ModelTests.kt`
- **Coverage**: Page, Block, Property data classes
- **Tests**: Creation, equality, copying, serialization compatibility

#### 2. ViewModel Tests
- **File**: `src/commonTest/kotlin/com/logseq/kmp/AppViewModelTest.kt`
- **Coverage**: AppViewModel business logic
- **Tests**: State management, page selection, error handling, loading states

#### 3. Repository Integration Tests
- **File**: `src/businessTest/kotlin/com/logseq/kmp/repository/RepositoryIntegrationTests.kt`
- **Coverage**: In-memory repository implementations
- **Tests**: CRUD operations, relationships, error handling

## Key Improvements Made

### 1. Fixed Build Configuration
- ✅ Added `kotlin.mpp.applyDefaultHierarchyTemplate=false` to gradle.properties
- ✅ Suppressed Android source set layout warnings
- ✅ Fixed Kotlin Native target handling
- ✅ Separated UI dependencies from business logic

### 2. Enhanced Testing Dependencies
- ✅ Added coroutines-test for async testing
- ✅ Added serialization testing support
- ✅ Platform-specific test dependencies for Android and iOS

### 3. Repository Pattern Implementation
- ✅ Created `PageRepository`, `BlockRepository`, `PropertyRepository` interfaces
- ✅ Implemented in-memory repositories for testing
- ✅ Integrated repositories with ViewModel

### 4. Multiplatform Testing Setup
- ✅ Configured tests for JVM, Android, iOS, and JS targets
- ✅ Proper source set hierarchy for test isolation
- ✅ UI-independent business logic testing

## Running Tests

### JVM Tests (Business Logic)
```bash
./gradlew jvmTest
```

### Android Unit Tests
```bash
./gradlew testDebugUnitTest
```

### iOS Tests (when on macOS)
```bash
./gradlew iosX64Test
./gradlew iosSimulatorArm64Test
```

### All Tests
```bash
./gradlew allTests
```

## Known Issues & Workarounds

### AndroidX Dependency Conflicts
The JVM tests currently have AndroidX dependency warnings. This occurs because:
- Compose dependencies include AndroidX libraries
- JVM runtime classpath validation flags this as an issue

**Workaround**: Tests pass functionally despite warnings. For production, consider:
1. Further separation of UI and business logic
2. Using test-specific dependency configurations

### iOS Target Limitations
iOS tests are disabled on non-macOS systems. This is expected behavior.

### JS Test Dependencies
JS tests require Node.js setup which may need additional configuration.

## Test Coverage Areas

### ✅ Implemented
- Data model validation and serialization
- ViewModel state management
- Repository CRUD operations
- Error handling scenarios
- Loading state management
- Entity relationships

### 🔄 Partially Implemented
- Platform-specific file system testing
- UI component testing (Compose)

### 📋 Planned
- Database integration tests (SQLDelight)
- End-to-end UI tests
- Performance testing
- Multiplatform serialization testing

## Configuration Files Modified

1. **gradle.properties**: Added Kotlin MPP and Android configuration
2. **build.gradle.kts**: Enhanced source sets, dependencies, and test configurations
3. **Source structure**: Separated UI from business logic for better testability

This testing infrastructure provides a solid foundation for maintaining code quality across all Logseq KMP target platforms.