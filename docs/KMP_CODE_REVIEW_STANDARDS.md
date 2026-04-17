# Logseq KMP Migration Code Review Standards

## Overview

This document establishes comprehensive code review standards for the Logseq Kotlin Multiplatform (KMP) migration project. These standards ensure high-quality, maintainable code while preventing technical debt accumulation during the migration from ClojureScript to Kotlin.

## 1. KMP-Specific Code Quality Standards

### 1.1 Platform-Specific Implementation Guidelines

#### Common/Shared Code (`commonMain`)
- **Business Logic**: All domain logic, data models, and business rules MUST be in `commonMain`
- **Platform Abstractions**: Use `expect/actual` declarations for platform-specific operations
- **Dependency Injection**: Prefer constructor injection over service locators
- **API Design**: Design APIs that work across all target platforms (JVM, JS, iOS, Android)

```kotlin
// ✅ Good: Platform abstraction with expect/actual
expect class PlatformFileSystem() {
    suspend fun readFile(path: String): ByteArray
    suspend fun writeFile(path: String, data: ByteArray): Boolean
}

// ❌ Bad: Platform-specific code in commonMain
actual class PlatformFileSystem() {
    // JVM-specific implementation should be in jvmMain
}
```

#### Platform-Specific Code (`jvmMain`, `jsMain`, `iosMain`, `androidMain`)
- **Single Responsibility**: Each platform implementation should only handle platform-specific concerns
- **Error Handling**: Convert platform exceptions to common Result types
- **Resource Management**: Use platform-appropriate resource management patterns

### 1.2 Kotlin Language Standards

#### Coroutines and Concurrency
```kotlin
// ✅ Good: Proper structured concurrency
suspend fun saveBlocks(blocks: List<Block>): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        blocks.chunked(100).forEach { chunk ->
            repository.saveBlocks(chunk).getOrThrow()
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// ❌ Bad: Blocking operations in suspend functions
suspend fun badExample(): Result<Unit> {
    return runBlocking { // Don't use runBlocking in suspend functions
        // Blocking I/O operations
    }
}
```

#### Data Classes and Immutability
- **Prefer data classes** for immutable value objects
- **Use `val`** instead of `var` whenever possible
- **Deep immutability**: Ensure nested collections are immutable
- **Validation**: Perform validation in `init` blocks

```kotlin
// ✅ Good: Immutable data class with validation
data class Block(
    val id: Long,
    val uuid: String,
    val content: String,
    val properties: Map<String, String> = emptyMap()
) {
    init {
        require(id > 0) { "ID must be positive" }
        require(uuid.matches(Regex("^[0-9a-f-]{36}$"))) { "Invalid UUID format" }
        require(content.length <= MAX_CONTENT_LENGTH) { "Content too long" }
    }
}
```

### 1.3 SQLDelight Database Standards

#### Schema Design
```sql
-- ✅ Good: Proper indexing and constraints
CREATE TABLE blocks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL UNIQUE,
    content TEXT NOT NULL,
    page_id INTEGER NOT NULL,
    parent_id INTEGER,
    position INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (page_id) REFERENCES pages(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES blocks(id) ON DELETE CASCADE
);

-- Indexes for common queries
CREATE INDEX idx_blocks_page_id ON blocks(page_id);
CREATE INDEX idx_blocks_parent_id ON blocks(parent_id);
CREATE INDEX idx_blocks_position ON blocks(page_id, parent_id, position);
```

#### Query Patterns
- **Prepared Statements**: Always use parameterized queries
- **Batch Operations**: Use transactions for multiple writes
- **Flow Integration**: Expose database operations as Kotlin Flows

```kotlin
// ✅ Good: Flow-based query with proper error handling
fun getBlocksForPage(pageId: Long): Flow<Result<List<Block>>> = flow {
    try {
        val blocks = database.blockQueries
            .selectByPageId(pageId)
            .executeAsList()
            .map { it.toBlock() }
        emit(Result.success(blocks))
    } catch (e: Exception) {
        emit(Result.failure(e))
    }
}.flowOn(Dispatchers.IO)
```

## 2. Interoperability Between Kotlin and ClojureScript

### 2.1 Data Contract Standards

#### Shared Data Models
```kotlin
// ✅ Good: Serialization-compatible models
@Serializable
data class Block(
    @SerialName("id") val id: Long,
    @SerialName("uuid") val uuid: String,
    @SerialName("content") val content: String,
    @SerialName("properties") val properties: Map<String, String> = emptyMap()
)

// ClojureScript equivalent (for reference)
;; {:id 123
;;  :uuid "00000000-0000-0000-0000-000000000001"  
;;  :content "Hello World"
;;  :properties {:tags "work"}}
```

#### API Compatibility
- **JSON Format**: Use consistent JSON serialization format
- **Error Handling**: Standardize error response structures
- **Null Safety**: Explicitly handle nullable fields across languages

### 2.2 Migration Strategy Standards

#### Incremental Migration Pattern
```kotlin
// ✅ Good: Adapter pattern for gradual migration
class BlockRepositoryAdapter(
    private val clojureRepo: ClojureBlockRepository, // Legacy
    private val kotlinRepo: BlockRepository // New
) : BlockRepository {
    
    override suspend fun getBlockByUuid(uuid: String): Flow<Result<Block?>> {
        // Use Kotlin repo if available, fallback to ClojureScript
        return if (featureFlags.useKotlinBackend) {
            kotlinRepo.getBlockByUuid(uuid)
        } else {
            clojureRepo.getBlockByUuid(uuid).map { result ->
                result.map { it?.toKotlinBlock() }
            }
        }
    }
}
```

#### Data Synchronization
- **Bidirectional Sync**: Implement two-way data synchronization during migration
- **Conflict Resolution**: Define clear conflict resolution strategies
- **Rollback Capability**: Maintain ability to rollback to ClojureScript implementation

## 3. Cross-Platform Code Consistency

### 3.1 API Contract Standards

#### Consistent Interfaces
```kotlin
// ✅ Good: Platform-agnostic interface
interface FileOperations {
    suspend fun readText(path: String): Result<String>
    suspend fun writeText(path: String, content: String): Result<Boolean>
    suspend fun exists(path: String): Result<Boolean>
    suspend fun delete(path: String): Result<Boolean>
}

// Platform implementations maintain same contract
classJvmFileOperations : FileOperations { /* JVM-specific */ }
classJsFileOperations : FileOperations { /* JS-specific */ }
```

#### Error Handling Consistency
- **Result Types**: Use `Result<T>` consistently across platforms
- **Error Categories**: Define common error types and handling patterns
- **Logging**: Standardize logging formats and levels

### 3.2 Performance Consistency

#### Memory Management
```kotlin
// ✅ Good: Memory-efficient streaming
fun processLargeFile(path: String): Flow<ProcessingResult> = flow {
    fileSystem.openReadStream(path).use { stream ->
        stream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                emit(processLine(line))
            }
        }
    }
}

// ❌ Bad: Loading entire file into memory
fun processLargeFileBad(path: String): List<ProcessingResult> {
    val content = File(path).readText() // Memory intensive
    return content.lines().map { processLine(it) }
}
```

#### Async Operations
- **Structured Concurrency**: Use proper coroutine scopes
- **Cancellation Support**: Implement cooperative cancellation
- **Backpressure**: Handle flow backpressure appropriately

## 4. Performance and Memory Management Standards

### 4.1 Memory Management Guidelines

#### Resource Cleanup
```kotlin
// ✅ Good: Proper resource management
class DatabaseConnection {
    private val connection = createConnection()
    
    suspend fun <T> useConnection(block: suspend (Connection) -> T): T {
        return try {
            block(connection)
        } finally {
            connection.cleanup() // Ensure cleanup
        }
    }
}

// ❌ Bad: Resource leak
class BadDatabaseConnection {
    private val connection = createConnection() // Never closed
}
```

#### Caching Strategies
- **Appropriate Caching**: Cache frequently accessed data with proper eviction
- **Memory Limits**: Implement cache size limits and monitoring
- **Cache Invalidation**: Define clear cache invalidation strategies

### 4.2 Performance Standards

#### Database Operations
```kotlin
// ✅ Good: Batch operations with transactions
suspend fun saveBlocks(blocks: List<Block>): Result<Unit> = withContext(Dispatchers.IO) {
    database.transaction {
        blocks.chunked(100).forEach { chunk ->
            blockQueries.insertAll(chunk.map { it.toInsert() })
        }
    }
    Result.success(Unit)
}

// ❌ Bad: Individual database calls in loop
suspend fun saveBlocksBad(blocks: List<Block>): Result<Unit> {
    blocks.forEach { block ->
        blockQueries.insert(block.toInsert()) // N+1 query problem
    }
    return Result.success(Unit)
}
```

#### Query Optimization
- **Index Usage**: Ensure queries use appropriate indexes
- **Query Plans**: Review and optimize query execution plans
- **Pagination**: Implement proper pagination for large result sets

## 5. Security Considerations for Note-Taking Applications

### 5.1 Input Validation and Sanitization

#### Security Validation
```kotlin
// ✅ Good: Comprehensive input validation
object SecurityValidation {
    private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private const val MAX_CONTENT_LENGTH = 1_000_000
    private val DANGEROUS_PATTERNS = listOf(
        "<script", "javascript:", "data:", "vbscript:"
    )
    
    fun validateBlockContent(content: String): String {
        require(content.length <= MAX_CONTENT_LENGTH) { "Content exceeds maximum length" }
        require(!content.contains('\u0000')) { "Null bytes not allowed" }
        
        DANGEROUS_PATTERNS.forEach { pattern ->
            require(!content.contains(pattern, ignoreCase = true)) { 
                "Potentially dangerous content detected: $pattern" 
            }
        }
        
        return content.trim()
    }
    
    fun validateUuid(uuid: String): String {
        require(UUID_REGEX.matches(uuid)) { "Invalid UUID format" }
        return uuid.lowercase()
    }
}
```

#### XSS Prevention
- **Content Sanitization**: Sanitize user content before storage/display
- **Output Encoding**: Encode content appropriately for different contexts
- **CSP Headers**: Implement Content Security Policy headers

### 5.2 Data Protection Standards

#### Encryption Requirements
```kotlin
// ✅ Good: Secure encryption for sensitive data
class SecureBlockRepository(
    private val encryptionManager: EncryptionManager,
    private val delegate: BlockRepository
) : BlockRepository {
    
    override suspend fun saveBlock(block: Block): Result<Unit> {
        val encryptedBlock = if (block.isPrivate) {
            block.copy(
                content = encryptionManager.encrypt(block.content),
                properties = block.properties.mapValues { (_, value) ->
                    encryptionManager.encrypt(value)
                }
            )
        } else {
            block
        }
        
        return delegate.saveBlock(encryptedBlock)
    }
}
```

#### Access Control
- **Permission Model**: Implement proper permission checking
- **Data Isolation**: Ensure user data isolation
- **Audit Logging**: Log access to sensitive operations

## 6. Comprehensive Code Review Checklist

### 6.1 Pre-Submission Checklist

#### Code Quality
- [ ] Code follows Kotlin coding conventions
- [ ] Functions and classes have clear documentation
- [ ] Error handling is comprehensive and consistent
- [ ] Resource management is proper (no memory leaks)
- [ ] Tests cover edge cases and error conditions
- [ ] Performance implications have been considered

#### Platform Considerations
- [ ] Code is properly separated between common and platform-specific
- [ ] expect/actual declarations are used appropriately
- [ ] Platform dependencies are minimized in common code
- [ ] Cross-platform compatibility has been verified

#### Migration Requirements
- [ ] Data models are compatible with ClojureScript equivalents
- [ ] API contracts maintain backward compatibility during transition
- [ ] Migration path and rollback strategy are documented
- [ ] Feature flags are used for gradual rollout when appropriate

### 6.2 Review Process Checklist

#### Architecture and Design
- [ ] Separation of concerns is maintained
- [ ] Dependencies are properly injected
- [ ] Abstractions are appropriate and not over-engineered
- [ ] Domain logic is isolated from infrastructure concerns

#### Security and Safety
- [ ] Input validation is comprehensive
- [ ] Sensitive data is properly protected
- [ ] SQL injection and XSS vulnerabilities are prevented
- [ ] Error messages don't leak sensitive information

#### Performance and Scalability
- [ ] Database queries are optimized
- [ ] Memory usage is efficient
- [ ] Async operations are properly structured
- [ ] Caching is appropriate and not excessive

### 6.3 Post-Review Checklist

#### Testing and Validation
- [ ] Unit tests pass with good coverage
- [ ] Integration tests verify cross-platform behavior
- [ ] Performance benchmarks meet requirements
- [ ] Security scanning passes

#### Documentation and Communication
- [ ] API documentation is updated
- [ ] Migration notes are provided if needed
- [ ] Performance implications are documented
- [ ] Security considerations are noted

## 7. Quality Gates and Acceptance Criteria

### 7.1 Mandatory Quality Gates

#### Code Quality Metrics
- **Test Coverage**: Minimum 80% line coverage for new code
- **Static Analysis**: No critical issues from Detekt/Ktlint
- **Performance**: No regression in performance benchmarks
- **Memory**: No memory leaks detected in stress tests

#### Security Requirements
- **Vulnerability Scanning**: Zero high/critical vulnerabilities
- **Dependency Updates**: All dependencies up-to-date or justified
- **Security Review**: Passes security checklist for sensitive features

#### Migration Compatibility
- **Data Consistency**: Data models maintain compatibility
- **API Compatibility**: Breaking changes require migration plan
- **Rollback Capability**: Ability to rollback to previous implementation

### 7.2 Anti-Patterns to Avoid

#### Common Anti-Patterns

1. **Platform Code in Common Module**
```kotlin
// ❌ Anti-pattern: Platform-specific code in commonMain
// This should be in jvmMain/jsMain with expect/actual
fun readFile(path: String): String {
    return File(path).readText() // JVM-specific
}
```

2. **Blocking Operations in Suspend Functions**
```kotlin
// ❌ Anti-pattern: Blocking I/O in suspend function
suspend fun badDatabaseOperation(): List<Block> {
    return runBlocking { // Wrong! Use withContext instead
        database.blockQueries.selectAll().executeAsList()
    }
}
```

3. **Excessive Platform Dependencies**
```kotlin
// ❌ Anti-pattern: Platform-specific dependencies in common code
// commonMain should not depend on JVM-specific libraries
import java.io.File // This shouldn't be in commonMain
```

4. **Ignoring Error Handling**
```kotlin
// ❌ Anti-pattern: Ignoring Result types
fun getBlock(uuid: String): Block { // Should return Result<Block>
    return repository.getBlock(uuid).getOrThrow() // Loses error information
}
```

5. **Memory-Intensive Operations**
```kotlin
// ❌ Anti-pattern: Loading everything into memory
fun getAllBlocks(): List<Block> {
    return database.blockQueries.selectAll().executeAsList() // Could be huge
}
```

#### Migration Anti-Patterns

1. **Direct Data Model Changes Without Migration**
```kotlin
// ❌ Anti-pattern: Changing data structure without migration
data class Block(
    val id: Long,
    val content: String,
    val newField: String // Breaking change without migration strategy
)
```

2. **Inconsistent Error Handling Across Platforms**
```kotlin
// ❌ Anti-pattern: Different error handling per platform
// JVM throws exception, JS returns null, iOS crashes
```

## 8. Metrics for Code Quality Improvement

### 8.1 Quantitative Metrics

#### Code Quality Metrics
- **Cyclomatic Complexity**: Maximum complexity of 10 per function
- **Technical Debt Ratio**: Maintain debt ratio below 15%
- **Code Duplication**: Less than 5% duplication
- **Test Coverage**: 80% line coverage, 70% branch coverage

#### Performance Metrics
- **Database Query Time**: P95 query time under 100ms
- **Memory Usage**: No memory leaks over 24h stress test
- **Startup Time**: Application startup under 2 seconds
- **File I/O Performance**: Large file operations under 500ms

#### Migration Metrics
- **Code Migration Percentage**: Track % of ClojureScript migrated
- **API Compatibility**: Maintain >95% API compatibility during transition
- **Bug Migration Rate**: Zero critical bugs during migration
- **Performance Regression**: Less than 5% performance degradation

### 8.2 Qualitative Metrics

#### Code Maintainability
- **Readability**: Code is self-documenting with minimal comments
- **Modularity**: High cohesion, low coupling
- **Testability**: Easy to unit test with minimal mocking
- **Documentation**: API documentation is complete and accurate

#### System Reliability
- **Error Recovery**: Graceful handling of all error conditions
- **Data Integrity**: Zero data corruption incidents
- **Availability**: System uptime >99.9%
- **Security**: Zero security incidents

## 9. Review Process Recommendations

### 9.1 Review Workflow

#### Pre-Review Phase
1. **Self-Review**: Developer reviews own code against checklist
2. **Automated Checks**: Run all automated tests and quality gates
3. **Documentation Update**: Update relevant documentation
4. **Migration Planning**: Document migration impact if any

#### Review Phase
1. **Architecture Review**: Senior engineer reviews architectural impact
2. **Security Review**: Security team reviews sensitive changes
3. **Platform Review**: Platform-specific implementation review
4. **Performance Review**: Performance team reviews performance impact

#### Post-Review Phase
1. **Integration Testing**: Full integration test with all platforms
2. **Performance Benchmarking**: Run performance benchmarks
3. **Security Validation**: Final security validation
4. **Deployment**: Merge and deploy with monitoring

### 9.2 Review Roles and Responsibilities

#### Code Reviewer Responsibilities
- **Quality Gatekeeper**: Ensure all quality standards are met
- **Migration Guardian**: Review migration impact and compatibility
- **Security Advocate**: Identify potential security issues
- **Performance Champion**: Review performance implications

#### Developer Responsibilities
- **Standards Compliance**: Ensure code meets all standards
- **Test Coverage**: Write comprehensive tests
- **Documentation**: Maintain accurate documentation
- **Migration Planning**: Plan migration impact carefully

### 9.3 Continuous Improvement

#### Review Process Metrics
- **Review Time**: Track average review time per PR
- **Review Quality**: Measure defect detection rate
- **Developer Feedback**: Collect and act on feedback
- **Standards Evolution**: Regularly update standards based on lessons learned

#### Knowledge Sharing
- **Code Review Guidelines**: Maintain and evolve guidelines
- **Best Practices**: Document and share best practices
- **Training**: Regular training on KMP and migration patterns
- **Retrospectives**: Regular retrospectives on migration process

---

## Conclusion

These code review standards provide a comprehensive framework for ensuring high-quality, maintainable code during the Logseq KMP migration. By following these guidelines, the team can prevent technical debt accumulation while successfully migrating from ClojureScript to Kotlin Multiplatform.

Regular review and updates of these standards will ensure they remain relevant as the migration progresses and new challenges emerge. The focus on quality, security, performance, and maintainability will help deliver a robust and scalable Logseq application.