## 🐛 BUG-001: Data Migration Complexity [SEVERITY: HIGH]

**Status**: 🔍 Investigating
**Discovered**: Migration Planning Phase (2026-01-01)
**Impact**: Plugin ecosystem compatibility risk - may block production migration

## Description

Converting from DataScript's flexible document model to relational/SQL structures may lose dynamic query capabilities used by plugins. The EAV (Entity-Attribute-Value) model used by DataScript allows arbitrary plugin-stored metadata, which doesn't map cleanly to SQL tables.

## Affected Files (3 files - within context boundary)

1. `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt` - Needs PluginMetadata model
2. `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/Migration.kt` - Create migration utilities
3. `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` - Add plugin_data table

## Root Cause Analysis

DataScript stores plugin data as arbitrary EAV tuples:
```
[:plugin-data :plugin-id "logseq-plugin-todo"]
[:plugin-data :entity-type "block"]
[:plugin-data :entity-uuid "block-uuid"]
[:plugin-data :key "todo-status"]
[:plugin-data :value "DONE"]
```

SQL schema requires structured table with foreign keys, which loses some flexibility.

## Fix Approach

### Phase 1: PluginMetadata Model (1h)
Create dedicated data model for plugin-specific metadata with JSON value storage.

### Phase 2: Migration Utilities (2h)
Build utilities to transform DataScript EAV tuples to structured plugin_data table.

### Phase 3: SQL Schema (1h)
Add plugin_data table with appropriate indexes and constraints.

### Phase 4: Integration Testing (1h)
Verify complete migration flow with sample plugin data.

## Mitigation Status

- [x] Flexible metadata storage designed (PluginMetadata model created)
- [x] Migration utilities implemented
- [x] SQL schema for plugin_data added
- [x] Integration tests passing

## Success Criteria

- [ ] 100% of test plugin data migrates successfully
- [ ] No data loss during migration
- [ ] Plugin API compatibility maintained
- [ ] Migration runs under 5 minutes for 10k blocks

## Related Tasks

- Task BUG-001-A: PluginMetadata Model Definition (docs/tasks/bug-fixes.md)
- Task BUG-001-B: Migration Utilities (docs/tasks/bug-fixes.md)
- Task BUG-001-C: SQL Schema (docs/tasks/bug-fixes.md)
- Task BUG-001-D: Integration Tests (docs/tasks/bug-fixes.md)

## Verification

```bash
# Run migration tests
./gradlew :kmp:test --tests "*Migration*"
```

## Next Action

Start Task BUG-001-A: PluginMetadata Model Definition (1h)
- Load Models.kt and understand existing patterns
- Design PluginMetadata data class
- Add kotlinx.serialization support
