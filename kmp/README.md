# SteleKit KMP Module

The core Kotlin Multiplatform library for SteleKit — shared business logic, data layer, and Compose UI across Desktop, Android, Web, and iOS.

## Architecture

The module targets four platforms from a single shared codebase:

| Target | Description |
|--------|-------------|
| **JVM** | Desktop application (primary) |
| **Android** | Native Android app |
| **Web (wasmJs)** | Web application via Compose CanvasBasedWindow + Skia |
| **iOS** | Planned — currently disabled |

## Key Technologies

- **Kotlin 2.1.20** with Kotlin Multiplatform
- **Compose Multiplatform 1.7.3** — shared declarative UI across all targets
- **SQLDelight 2.3.2** — type-safe SQL, reactive flows, persistent SQLite storage
- **Orbit MVI** — Model-View-Intent architecture for ViewModels
- **kotlinx.coroutines** — structured concurrency throughout

## Project Structure

```
kmp/
├── build.gradle.kts
└── src/
    ├── commonMain/
    │   └── kotlin/dev/stapler/stelekit/
    │       ├── command/       # Undo/redo command pattern
    │       ├── db/            # GraphLoader, GraphWriter, GraphManager, DriverFactory
    │       ├── editor/        # Editor state, input handling, persistence
    │       ├── model/         # Block, Page, Graph, EditorCommand
    │       ├── outliner/      # Block tree operations, sorting, hierarchy
    │       ├── parser/        # Markdown parser, properties, timestamps
    │       ├── parsing/       # AST, lexer, LogseqParser
    │       ├── performance/   # PerformanceMonitor, DebounceManager
    │       ├── platform/      # FileSystem, GitManager, PlatformSettings (expect)
    │       ├── repository/    # SQLDelight repositories (blocks, pages, search)
    │       ├── rtc/           # Real-time collaboration (early-stage)
    │       ├── search/        # Datalog query, vector search stubs
    │       └── ui/            # Compose components, screens, theme, i18n
    ├── jvmMain/               # Desktop implementations (DriverFactory, FileSystem)
    ├── androidMain/           # Android implementations
    ├── wasmJsMain/            # wasmJs/browser implementations
    └── iosMain/               # iOS implementations (disabled)
```

## Building

**Prerequisites:** JDK 17+. Android SDK for Android target. Xcode on macOS for iOS.

```bash
# Run the desktop application
./gradlew :kmp:runApp

# Compile (JVM only, fast iteration)
./gradlew :kmp:compileKotlinJvm

# Run all tests
./gradlew :kmp:allTests

# Android debug build
./gradlew :kmp:assembleDebug

# wasmJs browser bundle
./gradlew :kmp:wasmJsBrowserProductionWebpack
```

## Testing

Tests are structured in three layers:

| Layer | Location | What it tests |
|-------|----------|---------------|
| Unit (common) | `commonTest/` | Parser, outliner, repository logic |
| Integration (JVM) | `jvmTest/` | GraphLoader, GraphWriter, full-stack DB |
| Screenshot (JVM) | `jvmTest/screenshots/` | Compose UI visual regression |

```bash
# All tests
./gradlew :kmp:allTests

# JVM only (faster)
./gradlew :kmp:jvmTest
```
