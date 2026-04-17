# Logseq KMP Rich Editor Persistence System

This directory contains a comprehensive auto-save and persistence system for the Logseq KMP rich editor. The system provides reliable data persistence, conflict detection/resolution, performance optimization, and error recovery mechanisms.

## Overview

The persistence system is built around several key components:

- **PersistenceManager**: Core auto-save functionality with configurable intervals
- **DataSynchronizer**: Real-time synchronization and conflict handling
- **ConflictDetection & Resolution**: Automatic and manual conflict resolution
- **Performance Optimizations**: Debouncing, batching, and intelligent processing
- **Error Handling**: Robust error recovery with retry mechanisms
- **Integration Layer**: Seamless integration with existing KMP infrastructure

## Key Features

### ✅ Auto-Save System
- Configurable auto-save intervals (default: 3 seconds)
- Intelligent change detection to avoid unnecessary writes
- Debounced batching for optimal performance
- Force save capabilities on focus loss or navigation

### ✅ Conflict Detection & Resolution
- Automatic conflict detection using content and metadata comparison
- Multiple resolution strategies (keep local, keep remote, merge, manual)
- Heuristic-based auto-resolution for common scenarios
- Manual intervention workflow for complex conflicts

### ✅ Performance Optimization
- Debounced change batching with configurable intervals
- Intelligent change merging to reduce operations
- Performance monitoring and metrics collection
- Memory and disk usage tracking

### ✅ Error Handling & Recovery
- Exponential backoff retry mechanism
- Categorized error handling (retry, ignore, escalate, manual)
- Recovery backup and restore functionality
- Comprehensive error reporting and user notifications

### ✅ Real-time Synchronization
- Incremental and full sync capabilities
- Remote status monitoring and connectivity checks
- Batch processing for network efficiency
- Sync history and progress tracking

## Architecture

### Core Components

```
PersistenceManager (IPersistenceManager)
├── Auto-save queue with debouncing
├── Change detection and validation
├── State management and statistics
└── Integration with BlockRepository/GraphWriter

DataSynchronizer (IDataSynchronizer)
├── Remote connectivity management
├── Conflict detection and resolution
├── Sync session management
└── Progress tracking and history

ConflictDetector & ConflictResolver
├── Content conflict detection
├── Property conflict handling
├── Structure conflict resolution
└── Resolution strategy execution

PersistenceErrorHandler
├── Error categorization and handling
├── Retry mechanism with exponential backoff
├── Recovery backup system
└── User notification integration

Performance Optimizations
├── PersistenceDebouncer (batching)
├── PersistenceOptimizer (change merging)
├── PersistencePerformanceMonitor (metrics)
└── Processing time estimation
```

### Data Flow

```
Editor Changes → Change Queue → Debouncer → Optimizer → Conflict Detection → Persistence Manager → File System
                                            ↓
                                      Error Handler ← Retry Logic ← Failure Detection
                                            ↓
                                      Conflict Resolver ← User Interaction ← Conflicts
```

## Usage

### Basic Setup

```kotlin
// Create integrated persistence system
val persistenceSystem = PersistenceFactory.createPersistenceSystem(
    blockRepository = blockRepository,
    graphWriter = graphWriter,
    fileSystem = platformFileSystem,
    notificationManager = notificationManager,
    scope = coroutineScope,
    config = PersistenceConfig.DEFAULT.copy(
        autoSaveInterval = 3000L,
        enableConflictResolution = true,
        enablePerformanceMonitoring = true
    )
)

// Start the system
persistenceSystem.start()
```

### Saving Blocks

```kotlin
// Direct block save
val block = Block(/* block data */)
val result = persistenceSystem.saveBlockIntegrated(block)

// Queue changes for debounced processing
val change = BlockChange(
    blockUuid = block.uuid,
    type = ChangeType.CONTENT,
    timestamp = Clock.System.now(),
    oldContent = "old content",
    newContent = "new content"
)
persistenceSystem.queueChangeIntegrated(change)
```

### Monitoring and Health

```kotlin
// Get system statistics
val stats = persistenceSystem.getSystemStats()
println("System health: ${stats.systemHealth}")

// Perform health check
val healthCheck = persistenceSystem.performHealthCheck()
if (!healthCheck.isHealthy) {
    println("Issues: ${healthCheck.issues}")
    println("Warnings: ${healthCheck.warnings}")
}
```

## Configuration

### PersistenceConfig

```kotlin
data class PersistenceConfig(
    val autoSaveEnabled: Boolean = true,           // Enable auto-save
    val autoSaveInterval: Long = 3000L,           // Auto-save interval (ms)
    val maxQueueSize: Int = 100,                  // Maximum pending changes
    val maxRetries: Int = 3,                       // Maximum retry attempts
    val retryDelay: Long = 1000L,                 // Base retry delay (ms)
    val enableChangeDetection: Boolean = true,      // Enable smart change detection
    val enableConflictResolution: Boolean = true,    // Enable conflict resolution
    val backupEnabled: Boolean = true,              // Enable backup system
    val backupInterval: Long = 300000L,            // Backup interval (ms)
    val maxBackupFiles: Int = 10,                 // Maximum backup files
    val enablePerformanceMonitoring: Boolean = true,  // Enable performance monitoring
    val enableDetailedLogging: Boolean = false       // Enable detailed logging
)
```

### Predefined Configurations

```kotlin
// Default configuration for general use
PersistenceConfig.DEFAULT

// Fast configuration for development/testing
PersistenceConfig.FAST (autoSaveInterval = 1s, maxRetries = 1)

// Slow configuration for resource-constrained environments
PersistenceConfig.SLOW (autoSaveInterval = 10s, maxRetries = 5)

// Testing configuration with no persistence
PersistenceConfig.TESTING (autoSaveEnabled = false)
```

## Performance Considerations

### Debouncing Strategy
- Changes are collected over a configurable interval (default: 3 seconds)
- Multiple changes to the same block are merged automatically
- Batch size limits prevent memory issues
- Immediate flush on critical operations

### Conflict Resolution Performance
- Content conflicts are detected using checksum comparison
- Property conflicts use key-value comparison
- Structure conflicts require hierarchical analysis
- Resolution strategies have different performance costs

### Memory Management
- Change queues are bounded to prevent memory leaks
- Failed operation history is limited to recent entries
- Performance metrics use rolling windows
- Automatic cleanup of old data

## Error Handling

### Error Categories

1. **Retry**: Temporary issues (network, file locks, timeouts)
2. **Ignore**: Non-critical issues (minor formatting, optional features)
3. **Escalate**: Critical issues (security, out of memory)
4. **Manual**: Complex issues requiring user intervention

### Recovery Mechanisms

1. **Automatic Retry**: Exponential backoff with configurable limits
2. **Backup/Restore**: System state snapshots for recovery
3. **Conflict Resolution**: Multiple strategies for different conflict types
4. **User Notification**: Clear error messages and suggested actions

## Integration with Existing Infrastructure

### Repository Integration
- Uses existing `BlockRepository` for data access
- Integrates with `GraphWriter` for file persistence
- Follows existing transaction patterns
- Maintains compatibility with data formats

### File System Integration
- Uses existing `PlatformFileSystem` for file operations
- Respects existing file permissions and security
- Handles platform-specific path handling
- Maintains backup compatibility

### Performance Integration
- Integrates with existing `PerformanceMonitor`
- Uses existing coroutines and dispatchers
- Follows existing logging patterns
- Provides metrics in existing format

## Testing

### Unit Testing
Each component can be tested independently:

```kotlin
@Test
fun testConflictDetection() {
    val detector = ConflictDetector()
    val conflicts = detector.detectBlockConflicts(/* test data */)
    assertEquals(1, conflicts.size)
}

@Test
fun testDebouncing() {
    val debouncer = PersistenceDebouncer(scope, 1000L)
    debouncer.queueChange(/* change */)
    // Test batching behavior
}
```

### Integration Testing
The integrated system can be tested end-to-end:

```kotlin
@Test
fun testIntegratedPersistence() {
    val system = PersistenceFactory.createPersistenceSystem(/* dependencies */)
    system.start()
    
    // Test complete workflow
    val result = system.saveBlockIntegrated(/* test block */)
    assertTrue(result.isSuccess)
    
    system.stop()
}
```

## Monitoring and Observability

### Metrics Available
- Auto-save success/failure rates
- Average save duration
- Queue size and processing time
- Conflict detection and resolution statistics
- Error recovery success rates
- Memory and disk usage

### Health Monitoring
- System health checks with scoring
- Automatic issue detection
- Performance threshold alerts
- Recovery status monitoring

## Security Considerations

### Data Validation
- All inputs validated using existing `Validation` utilities
- UUID and content length validation
- Path traversal protection
- Control character filtering

### Error Information
- Sensitive data not exposed in error messages
- Stack traces only in debug mode
- File paths sanitized in logs
- User notifications contain minimal technical details

## Future Enhancements

### Planned Features
1. **Multi-graph Support**: Persistence across multiple graphs
2. **Cloud Integration**: Direct cloud storage synchronization
3. **Advanced Merging**: 3-way merge algorithms
4. **Predictive Caching**: ML-based performance optimization
5. **Real-time Collaboration**: Multi-user editing support

### Performance Improvements
1. **Streaming Processing**: Large file handling
2. **Compression**: Reduced disk usage
3. **Delta Encoding**: Efficient change storage
4. **Parallel Processing**: Multi-core optimization

## Troubleshooting

### Common Issues

1. **High Memory Usage**
   - Reduce `maxQueueSize` in configuration
   - Enable cleanup of old data
   - Check for memory leaks in custom components

2. **Slow Performance**
   - Increase `autoSaveInterval` to reduce frequency
   - Enable performance monitoring to identify bottlenecks
   - Check disk I/O performance

3. **Frequent Conflicts**
   - Review conflict resolution strategy
   - Check for concurrent editing scenarios
   - Consider shorter auto-save intervals

### Debug Information

Enable detailed logging for troubleshooting:

```kotlin
PersistenceConfig.DEFAULT.copy(
    enableDetailedLogging = true,
    enablePerformanceMonitoring = true
)
```

This will provide comprehensive logs for:
- Change queuing and processing
- Conflict detection and resolution
- Error handling and recovery
- Performance metrics and bottlenecks