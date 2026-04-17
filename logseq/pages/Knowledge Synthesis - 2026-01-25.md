# Knowledge Synthesis - 2026-01-25

Daily consolidation of synthesized knowledge from external sources.

---

## Editor Migration Patterns for Kotlin Multiplatform

**Context**: Research into best practices for migrating complex editor systems from JavaScript/ClojureScript to Kotlin Multiplatform (KMP), focusing on actionable patterns for Logseq migration.

**Key Findings**:

### 1. Block-Based Architecture Pattern
- **Current Constraint**: Compose `TextField` doesn't support inline content (images, etc.) in 2025, making single-field rich text editing impractical
- **Solution**: Model documents as ordered list of blocks (`TextBlock`, `ImageBlock`) using `SnapshotStateList<Block>`
- **Benefits**: Enables efficient rendering with `LazyColumn`, precise focus control, and straightforward undo/merge operations
- **Implementation**: Each block has immutable identity but mutable content via state, allowing for atomic operations

### 2. Single Source of Truth State Management
- **Pattern**: Use `StateFlow` as single source of truth backed by appropriate persistence layer
- **Avoid**: "Single Source of Truth Illusion" where shared state class doesn't account for platform lifecycle differences
- **Architecture**: Separate business state (shared KMP) from UI state (platform-specific)
- **Best Practice**: Command-style stack for undo/redo that records semantic document operations rather than raw keystrokes

### 3. Cross-Platform Selection and Editing
- **Local Selection**: Use `TextFieldValue.selection` within text blocks for native keyboard/IME interactions
- **Cross-Block Selection**: Implement document-level selection state with `Cursor(blockIndex, offset)` and `DocSelection(anchor, focus)`
- **Caret Travel**: Arrow keys navigate within blocks first, crossing boundaries moves to adjacent blocks
- **Platform Differences**: Normalize key handling with `onPreviewKeyEvent` for cross-platform consistency

### 4. Migration Strategy Patterns
- **Incremental Approach**: Layer-by-layer migration (data models → data sources → business logic → presentation logic → tests)
- **Repository Options**: 
  - Separate repository for KMP module (recommended for large projects)
  - Monorepo model (creates coupling but ensures consistent testing)
- **Core Library Approaches**:
  - Refactor native library to multiplatform (risks divergence)
  - Write common adapter over existing native libraries (more maintainable)

### 5. Performance Optimization
- **Virtual Scrolling**: Use `LazyColumn` with keyed items for efficient document rendering
- **State Management**: Leverage `SnapshotStateList` for efficient recomposition
- **Image Loading**: Platform-specific loaders (Coil 3 for KMP) with appropriate cache wiring
- **Memory Efficiency**: Use persistent data structures for immutable operations without copying overhead

### 6. Collaborative Editing Architecture
- **CRDT Pattern**: Implement Conflict-Free Replicated Data Types for real-time collaboration
- **State-Based vs Operation-Based**: State-based CRDTs (CvRDTs) easier to implement with dedicated merge functions
- **Implementation**: `Mergeable<T>` interface with `merge(incoming: T): T` method
- **Synchronization**: Use `StateFlow` extensions for real-time state propagation across clients

**Related Concepts**: [[Kotlin Multiplatform]], [[Editor Architecture]], [[State Management]], [[CRDT]], [[Virtual Scrolling]], [[Command Pattern]]

**Source**: [How I Built a Notion-Style Editor with Compose Multiplatform](https://medium.com/@eduardofelipi/how-i-built-a-notion-style-editor-with-compose-multiplatform-3d051c97dcab), [Creating multiplatform CRDTs](https://avwie.github.io/creating-multiplatform-crdts/), [KMP Migration Guide](https://kotlinlang.org/docs/multiplatform/migrate-from-android.html)

**Tags**: #[[Editor]] #[[Kotlin Multiplatform]] #[[Architecture]] #[[Migration]] #[[Performance]]

---

## Large-Scale KMP Migration Anti-Patterns

**Context**: Analysis of common pitfalls and anti-patterns when migrating large codebases to Kotlin Multiplatform, based on production experiences from companies like Google and Premise.

**Key Findings**:

### 1. Repository Structure Anti-Patterns
- **Single Module Monolith**: Leads to increasing compilation times and doesn't allow feature-specific dependencies
- **Library Model for Features**: Creating separate repository for KMP code creates versioning disconnect with day-to-day app development
- **Insufficient Module Boundaries**: Blurring module boundaries in large KMP apps creates dependency conflicts and maintenance issues

### 2. Migration Process Anti-Patterns
- **Big Bang Rewrite**: Attempting to migrate entire system at once without incremental validation
- **Ignoring Platform Differences**: Assuming shared state works identically across platforms without lifecycle considerations
- **Complex Bridge Maintenance**: Long-term maintenance of CLJS-KMP bridges creates technical debt and slows development

### 3. Performance Anti-Patterns
- **Eager Data Loading**: Loading entire graph into memory instead of progressive/just-in-time loading
- **Inefficient State Updates**: Not using Compose optimization patterns like `remember`, `derivedStateOf`, and proper state hoisting
- **Blocking Operations**: Performing file I/O or heavy computation on main thread instead of using coroutines properly

### 4. Testing Anti-Patterns
- **Insufficient Platform Testing**: Not testing KMP code on all target platforms regularly
- **Missing Integration Tests**: Only unit testing shared code without testing platform integration points
- **Ignoring Build Performance**: Not monitoring and optimizing Gradle build times for large KMP projects

### 5. Dependency Management Anti-Patterns
- **Non-KMP Dependencies**: Using Android/JVM-only libraries without multiplatform alternatives
- **Version Conflicts**: Inconsistent dependency versions across platform-specific modules
- **Over-Engineering**: Creating unnecessary abstraction layers that complicate the codebase

### 6. Successful Patterns to Follow
- **Clean Architecture**: Clear separation of UI (Compose), Domain (pure Kotlin), and Data layers
- **Feature Modules**: Using several shared modules for better scalability and separation of concerns
- **Incremental Validation**: Building vertical slices and verifying before deletion of legacy code
- **Automated Testing**: Comprehensive testing including "Golden Master" comparisons between old and new implementations

**Related Concepts**: [[Kotlin Multiplatform]], [[Architecture Anti-Patterns]], [[Clean Architecture]], [[Gradle Optimization]], [Test Strategy]]

**Source**: [KMP Scalability Challenges](https://proandroiddev.com/kotlin-multiplatform-scalability-challenges-on-a-large-project-b3140e12da9d), [Production KMP Experiences](https://engineering.premise.com/kotlin-multiplatform-at-premise-b28d85825c9f), [KMP Configuration Guide](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html)

**Tags**: #[[Kotlin Multiplatform]] #[[Migration]] #[[Anti-Patterns]] #[[Architecture]] #[[Performance]]

---

## Modern Editor Architecture in Compose Multiplatform

**Context**: Deep dive into current capabilities and patterns for implementing rich text editors using Compose Multiplatform across platforms.

**Key Findings**:

### 1. Text Editing Capabilities (2025)
- **Current State**: `BasicTextField` and `TextField` don't support inline content (images within text)
- **Workaround**: Block-based editor approach is the recommended production pattern
- **Future**: `BasicTextField2` and text engine updates expected to address inline content limitations
- **Platform Differences**: Text editing behavior on iOS being improved to match native iOS expectations

### 2. Rich Text Editor Libraries
- **Compose Rich Editor**: Solid WYSIWYG option for text styling across Android, iOS, Desktop, and Web
- **Limitations**: Inline images still constrained by underlying `TextField` limitations
- **Features**: Bold, Italic, Underline, Links, Code blocks, Lists, HTML/Markdown import/export
- **Architecture**: Uses `RichTextState` for state management with methods like `toggleSpanStyle` and `toggleParagraphStyle`

### 3. Cross-Platform Considerations
- **Keyboard Navigation**: Arrow keys and modifier combos differ across platforms
- **Selection Affordances**: Native selection handles inside text blocks, custom chrome for cross-block ranges
- **Image Resources**: Multiplatform image resource APIs for consistent loading across platforms
- **Accessibility**: Proper `contentDescription` and dynamic type support essential

### 4. Performance Patterns
- **LazyColumn Rendering**: Keyed items for efficient large document rendering
- **Focus Management**: `FocusRequester` and `BringIntoViewRequester` for proper caret behavior
- **State Optimization**: Minimal recomposition through proper state hoisting and derived state
- **Memory Management**: Efficient object pooling for frequently created editor components

### 5. Plugin Integration Patterns
- **Bridge Architecture**: JSON-RPC communication between KMP core and JavaScript plugin host
- **API Compatibility**: Adapter layer to maintain compatibility with existing plugin expectations
- **Performance**: Batched events and optimized serialization to reduce communication overhead
- **Security**: Proper sandboxing and permission management for plugin execution

### 6. Data Synchronization Patterns
- **Real-time Sync**: CRDT-based conflict resolution for collaborative editing
- **Offline-First**: Local-first architecture with eventual consistency for sync
- **Version Control**: Git-based sync with proper conflict resolution strategies
- **Cache Strategy**: Intelligent caching and invalidation for optimal performance

**Related Concepts**: [[Compose Multiplatform]], [[Rich Text Editing]], [[CRDT]], [[Plugin Architecture]], [Real-time Sync]

**Source**: [Compose Multiplatform Roadmap](https://blog.jetbrains.com/kotlin/2025/05/kmp-roadmap-aug-2025/), [Rich Editor Libraries](https://mohamedrejeb.github.io/compose-rich-editor/), [Text Engine Development](https://proandroiddev.com/basictextfield2-a-textfield-of-dreams-2-2-fdc7fbbf9ffb)

**Tags**: #[[Compose]] #[[Editor]] #[[Cross-Platform]] #[[Performance]] #[[Plugin System]]

---