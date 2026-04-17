# Reference Repository Implementation

## Objective  
Implement the ReferenceRepository interface to track block-to-block references, enabling graph traversal and reference counting for the knowledge graph.

## Status
**NOT STARTED** - ReferenceRepository interface exists but no implementation

## Priority
**HIGH** - Blocks graph features (reference counting, most-connected blocks)

## Files Required (Context Boundary: 5 files)
1. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt` - EXISTS
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/BlockRepository.kt` - EXISTS  
3. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` - EXISTS
4. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt` - EXISTS (ReferenceRepository interface)
5. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/InMemoryReferenceRepository.kt` - EXISTS (stub)

## Prerequisites
- [x] Repository interfaces defined
- [x] BlockRepository working
- [x] Block references table defined in schema

## Atomic Steps

### Step 1: Design Reference Data Structure (0.5h)
- [ ] Analyze `block_references` table schema
- [ ] Define ReferenceRepository interface requirements
- [ ] Plan Datalog queries for reference counting

### Step 2: Implement InMemoryReferenceRepository (1.5h)
- [ ] Create reference tracking map
- [ ] Implement `getOutgoingReferences()` - blocks this block references
- [ ] Implement `getIncomingReferences()` - blocks referencing this block
- [ ] Implement `addReference()` and `removeReference()`
- [ ] Implement `getMostConnectedBlocks()` for reference counting

### Step 3: Implement DatascriptReferenceRepository (1.5h)
- [ ] Create Datalog rules for reference queries
- [ ] Implement bidirectional reference tracking
- [ ] Optimize for most-connected blocks queries

### Step 4: Add Reference Integration (0.5h)
- [ ] Integrate with BlockRepository save operations
- [ ] Auto-create references when links detected
- [ ] Update factory to create reference repositories

## Validation Approach
- Unit tests for reference operations
- Integration test with sample blocks and references
- Performance test for most-connected-blocks query

## Completion Criteria
- [ ] ReferenceRepository interface fully implemented (IN_MEMORY and DATASCRIPT)
- [ ] Blocks can track outgoing/incoming references
- [ ] Most-connected-blocks query returns ranked results
- [ ] All tests pass

## Success Metrics
- Time to implement: 4 hours maximum
- Context files: 5 (within 3-5 limit)
- Test coverage: New reference tests pass
- Feature: Reference counting operational
