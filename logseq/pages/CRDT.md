# CRDT (Conflict-Free Replicated Data Types)

Data structures that enable concurrent updates across multiple distributed nodes without coordination, with mathematical guarantees of eventual consistency.

## Core Concept

CRDTs allow multiple users to edit the same data simultaneously without requiring centralized coordination. When changes are synchronized, all replicas converge to the same final state through well-defined merge operations.

## Types of CRDTs

### Operation-Based (CmRDT)
- **Mechanism**: Updates transmitted as commutative operations
- **Requirements**: All operations must be delivered in order without loss
- **Example**: Increment operations where order doesn't matter
- **Advantages**: Smaller payloads, only operations transmitted
- **Challenges**: Requires reliable delivery guarantee

### State-Based (CvRDT)
- **Mechanism**: Full state periodically merged between replicas
- **Requirements**: Merge function must be commutative, associative, and idempotent
- **Example**: Last-Write-Wins registers with timestamp resolution
- **Advantages**: More fault-tolerant, simpler implementation
- **Challenges**: Larger payloads for state synchronization

## Implementation Patterns

### Mergeable Interface
```kotlin
interface Mergeable<T> where T : Mergeable<T> {
    fun merge(incoming: T): T
}
```

### Last-Write-Wins Element
- **Resolution**: Uses timestamp plus discriminant for tie-breaking
- **Implementation**: `MergeableValue<T>` with timestamp and random discriminant
- **Use Case**: Simple value replacement where latest update wins

### Mergeable Map
- **Conflict Resolution**: Last-Write-Wins for conflicting keys
- **Deletion Handling**: Tombstone set to track removed keys
- **Persistence**: Uses persistent data structures for efficiency
- **Serialization**: JSON-based for network transmission

## Integration with State Management

### StateFlow Extension
- **MergeableStateFlow**: Combines CRDT merging with Compose StateFlow
- **Automatic Sync**: Updates trigger StateFlow emissions for UI updates
- **Bidirectional**: Can merge incoming changes and publish local changes

### Coroutines Integration
- **Real-time Updates**: Automatic propagation of merged state
- **Event Sourcing**: Command operations can be stored and replayed
- **Backpressure Handling**: Flow-based merging handles network interruptions

## Use Cases in Logseq

### Document Collaboration
- **Block Editing**: Multiple users editing different blocks simultaneously
- **Text Editing**: Character-level CRDTs within text blocks
- **Structure Changes**: Block reordering, insertion, deletion

### Graph Synchronization
- **Page Creation**: Concurrent page creation without conflicts
- **Link Updates**: Bidirectional link relationship updates
- **Property Changes**: Page metadata and property synchronization

### Real-time Features
- **Cursors**: Shared cursor positions for collaborative editing
- **Selections**: Multi-user text selection visualization
- **Presence**: User presence indicators in shared documents

## Implementation Considerations

### Performance
- **State Size**: Full state transmission can be expensive for large documents
- **Merge Efficiency**: Algorithm complexity affects real-time performance
- **Memory Usage**: Persistent data structures and tombstone management

### Network Efficiency
- **Delta Compression**: Only transmit changes rather than full state
- **Batching**: Group multiple operations before transmission
- **Prioritization**: Prioritize user-visible changes over metadata

### Conflict Resolution
- **User Intent**: Preserve user intent when resolving conflicts
- **Operational Transformation**: Sometimes combined with CRDTs for better UX
- **Manual Resolution**: Complex conflicts may require user intervention

## Related Concepts
[[Editor Architecture]], [[State Management]], [Real-time Sync]], [[Distributed Systems]], [[Collaborative Editing]]

## References
- [[Knowledge Synthesis - 2026-01-25]] - CRDT implementation patterns and collaborative editing architecture

## Tags
#[[Data Structure]] #[[Collaboration]] #[[Distributed Systems]] #[[Synchronization]] #[[Real-time]]