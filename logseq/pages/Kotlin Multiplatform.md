# Kotlin Multiplatform

Cross-platform development framework that enables sharing code across Android, iOS, Desktop, and Web platforms while maintaining native performance and platform-specific capabilities.

## Key Characteristics

- **Code Sharing**: Single codebase written in Kotlin shared across platforms
- **Native Performance**: Compiles to platform-native code rather than interpreted/bridged
- **Platform Interop**: Ability to call platform-specific APIs through expect/actual pattern
- **Compose Integration**: Full support for Compose Multiplatform for UI sharing
- **Gradle-based**: Uses familiar Gradle build system with multiplatform extensions

## Architecture Patterns

### Module Structure
- **commonMain**: Shared business logic and data models
- **platformMain**: Platform-specific implementations (androidMain, iosMain, etc.)
- **expect/actual**: Mechanism for declaring platform-specific APIs

### Migration Approaches
1. **Incremental Layer-by-Layer**: Data models → data sources → business logic → presentation
2. **Vertical Slice Migration**: Complete feature implementation in KMP then replace legacy
3. **Adapter Pattern**: Wrapper around existing platform-specific libraries

## Benefits for Logseq Migration

- **Unified Logic**: Single implementation of core graph and editor functionality
- **Performance**: Native execution vs JavaScript interpretation
- **Ecosystem**: Access to Kotlin/Java ecosystem for libraries and tooling
- **Maintainability**: Single codebase reduces duplication and maintenance burden

## Common Challenges

- **Build Complexity**: Multiplatform builds take longer and require more configuration
- **Library Ecosystem**: Not all libraries support KMP, requiring alternatives or wrappers
- **Platform Differences**: Need to handle platform-specific behavior and lifecycle differences
- **Team Learning**: Requires learning KMP-specific patterns and best practices

## Related Concepts
[[Editor Architecture]], [[State Management]], [[CRDT]], [[Clean Architecture]], [[Migration Strategy]]

## References
- [[Knowledge Synthesis - 2026-01-25]] - Editor migration patterns and architectural considerations
- [[Knowledge Synthesis - 2026-01-21]] - Rename Page implementation in KMP context

## Tags
#[[Development Platform]] #[[Cross-Platform]] #[[Mobile Development]] #[[Desktop Development]]