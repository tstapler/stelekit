# Research: Pitfalls

**Date**: 2026-04-14
**Dimension**: Known failure modes and risks

## Summary

The highest-risk areas for this migration framework are (1) partial-apply / non-idempotent state that leaves the graph in an unknown intermediate state with no automated recovery path, (2) checksum drift caused by invisible byte-level differences (line endings, encoding, trailing whitespace) that block future runs with no clear error, and (3) the desired-state diff engine producing destructive operations (deleting blocks/pages) when the declared desired-state is under-specified. File-content migrations compound all of these because—unlike SQL DDL—there is no transaction boundary around text writes, block identity must be actively preserved across renames, and wikilink references can silently break anywhere in the graph.

---

## 1. Idempotency Failures and Partial Apply

**Severity**: High
**Likelihood**: High

### The Problem

Flyway's canonical failure mode is a migration that dies mid-execution on a database without transactional DDL (MySQL being the classic case). Partial changes are written to disk; Flyway marks the migration as `failed` in `flyway_schema_history`. The next run refuses to proceed because it sees an unresolved failure. Manual intervention — running `flyway repair` to delete the failed row, then fixing and re-running the migration — is required.

For a **file-content** migration framework this is far worse: there are no database transactions around file writes. If a migration processes 500 Markdown pages and crashes on page 312, pages 1–311 are already modified on disk. The framework has no automatic way to know which files were touched, what the before-state was, or how to atomically roll back. The "partial apply" leaves the graph in a state that is neither the original nor the target, and the migration cannot safely be re-run without idempotency guards.

### Real Incidents

- Flyway GitHub issue #3951: "Missing successful server migration fails validation even with ignore missing" — a migration that partially applied during a deployment caused every subsequent startup to fail.
- Flyway GitHub issue #1598: Flyway 4.1.2 writes to `schema_version` without a lock, allowing two simultaneous instances to both believe they are the first writer; this produces duplicate or interleaved migration entries.

### Mitigations

- Every migration **must** record which files it modified before writing, so interrupted runs can be detected and reversed.
- The existing SteleKit op-log is the right place to record per-file before/after state atomically as a unit; treat the full set of op-log entries for a migration as the "transaction".
- Enforce idempotency explicitly: before applying any operation, check whether the target block/property already has the desired value; if so, skip it. This is analogous to Flyway's `outOfOrder` protection.
- Store a `status` field on the migration log entry (`RUNNING` → `COMPLETE` | `FAILED`) so a re-run can detect a half-applied migration and either resume or abort.

---

## 2. Checksum Drift

**Severity**: High
**Likelihood**: High

### The Problem

Liquibase and Flyway both checksum migration files and compare the stored value against the on-disk file on every startup. The stored checksum serves as a tamper-detection mechanism: if the file was edited after being applied, the tool refuses to run. In practice, this becomes a major operational friction point because **invisible byte-level changes** produce different checksums:

- **Line endings**: A developer opens a `.sql` file on Windows; their editor writes `\r\n`; the file was originally `\n`; checksum breaks.
- **Trailing whitespace / blank lines**: Some editors strip trailing whitespace on save; this silently changes the checksum of an already-applied migration.
- **Encoding**: A file saved as UTF-8 with BOM vs. without BOM produces a different byte sequence. Liquibase's checksum algorithm is byte-level, so a BOM changes every checksum.
- **Whitespace normalization version change**: Liquibase 4.22.0 changed its whitespace-normalization logic. Teams upgrading Liquibase found all their existing checksums invalidated overnight with no migration failures — just hundreds of "checksum mismatch" errors requiring `liquibase clearChecksums` on every environment.

**Source**: Liquibase forum thread "Checksum calculations and line ending styles"; Liquibase GitHub issue #3549 "SQL checksum not resilient to all formatting changes".

### For File-Content Migrations

The checksumming problem is actually **more severe** for Markdown than for SQL:

- Markdown files are frequently edited by end users in arbitrary editors (VS Code, Typora, iA Writer, Vim) — many of which auto-reformat on save.
- Logseq/SteleKit specifically writes indentation, blank lines, and block IDs as semantically meaningful content, so a normalization function must be extremely conservative to avoid false equivalences.
- A migration that renames a page also changes all `[[Page Name]]` wikilink references in other files — if a checksum covers the wikilink text, a rename migration will cause every referencing file's checksum to drift.

### Mitigations

- Normalize to LF before checksumming; strip trailing whitespace from lines; strip the UTF-8 BOM on read.
- Checksum only the **semantic content** of a migration script (the DSL query text), not the file's raw bytes. This isolates checksums from editor artifacts.
- Implement a `--recalculate-checksums` repair command analogous to `flyway repair` / `liquibase clearChecksums` for the rare legitimate case of a migration script comment being updated.
- Store checksums as a versioned algorithm (e.g., `sha256-v1:...`) so that algorithm changes do not silently invalidate all history.

---

## 3. File-Content Migration Specific Risks

**Severity**: High
**Likelihood**: High

### 3a. Regex / Pattern Transformation on Markdown

Regex is the standard first tool people reach for when transforming Markdown programmatically. It is also the most reliable way to corrupt it.

**Known failure modes**:

- **Greedy matching across block boundaries**: A non-greedy `.*?` still crosses newlines in multiline mode, causing a pattern intended for one block to consume multiple blocks. (Real postmortem from Mike Levin's automation blog: "the non-greedy quantifier was still too permissive and could match content across multiple distinct quotes, code blocks, and even newlines.")
- **Code block contamination**: A regex that transforms `[[Page Name]]` links will also transform the same pattern inside fenced code blocks (`` ` `` or `~~~`), corrupting example content.
- **Frontmatter / YAML header collision**: Patterns targeting block-level content can accidentally match YAML frontmatter keys if the regex does not explicitly skip the frontmatter region.
- **Unicode normalization**: Markdown allows semantically equivalent but byte-distinct representations of the same character (e.g., composed vs. decomposed NFC/NFD). A regex expecting `é` (NFC) silently misses `é` (NFD).

**Recommendation**: Parse Markdown into a structured AST first; operate on the tree; re-serialize. SteleKit already has an `OutlinerPipeline`; transformations should hook into that pipeline's block model, not raw text.

### 3b. Block Identity Corruption

SteleKit has stable block IDs stored in sidecar files (from the `feat(storage): stable block identity` commit). Any migration that moves, splits, or merges blocks must preserve or explicitly re-map these IDs. Silent ID loss causes:

- Broken `((block-id))` embed references throughout the graph (the Logseq equivalent of a dead `<a href>` in HTML).
- Broken op-log entries that reference a block ID that no longer exists.
- Undo operations targeting a phantom block.

**Real-world evidence**: The Logseq-to-Obsidian migration community reports this as the hardest problem — Logseq block references become raw UUIDs in Obsidian with no automatic resolution. (GitHub: laughedelic/outbreak tool was built specifically because the naive migration breaks all block embeds.)

### 3c. Wikilink / Page Reference Invalidation

Renaming a page (common migration: "normalize all tag pages to lowercase") invalidates every `[[Original Name]]` reference in the graph. This is a graph-wide referential integrity problem. The migration must:
1. Find all pages being renamed.
2. Find all files that contain `[[OriginalName]]` references.
3. Rewrite those references atomically with the page rename.

If step 3 is omitted or partially applied, the graph has broken links. Unlike a database foreign key constraint, Markdown offers no enforcement mechanism — broken links are silent.

### 3d. Encoding Edge Cases

- Files written by some editors (particularly on Windows) may contain BOM, `\r\n` line endings, or Windows-1252 characters mistakenly saved as UTF-8.
- Logseq itself sometimes produces `&amp;` HTML entities in Markdown when content is pasted from a browser.
- A migration that uses `String.replace()` on a file with mixed encodings can silently corrupt multi-byte sequences if the string is not decoded properly before processing.

---

## 4. KMP Serialization Gotchas

**Severity**: Medium
**Likelihood**: Medium

### 4a. No Runtime Reflection on Kotlin/Native (iOS)

The most fundamental KMP serialization constraint: `kotlinx.serialization` on Kotlin/Native cannot use `KClass`-based serializer lookup at runtime because reflection is not available. The error is explicit:

> "Obtaining serializer from KClass is not available on native due to the lack of reflection."

This means any code that calls `serializer<T>()` or `serializerOrNull(kClass)` will compile cleanly for JVM but crash on iOS at runtime. Migration log entries, DSL query objects, and diff result types must all use compile-time `@Serializable` annotations, not dynamic serializer lookup.

**Source**: kotlinx.serialization GitHub issue #479.

### 4b. Custom Serializers Must Be `object`, Not `class` (JS + Native)

When writing a custom `KSerializer<T>`, the implementation must be a Kotlin `object` (singleton), not a `class`. On Kotlin/JS and Kotlin/Native, the compiler plugin cannot find the serializer if it is declared as a class.

**Source**: kotlinx.serialization GitHub issue #2382.

### 4c. Long Precision on Kotlin/JS (IR Compiler)

Kotlin/JS's IR compiler silently rounds `Long` values during JSON deserialization. If migration log entries use `Long` for timestamps or sequence numbers (the natural choice), values larger than `Number.MAX_SAFE_INTEGER` (2^53 − 1) will deserialize to wrong values on Web targets with no exception thrown.

**Mitigation**: Serialize timestamps as `String` (ISO-8601) rather than epoch milliseconds. Use `kotlinx-datetime`'s `Instant` with its string serializer rather than raw `Long`.

**Source**: kotlinx.serialization GitHub issue #1369.

### 4d. UUID and Instant Versioning Churn

- `kotlin.uuid.Uuid` was introduced in Kotlin 2.0.20; `kotlinx.serialization` 1.7.2 added its serializer. However, the serialization plugin auto-inserts the `Uuid` serializer only from Kotlin 2.1.0 onwards. In earlier Kotlin versions, `@Contextual` must be added manually or the build will fail with a confusing error.
- `Instant` moved from `kotlinx-datetime` to the Kotlin standard library in Kotlin 2.2 / `kotlinx-serialization` 1.9.0. If the project is between versions, `Instant` serialization breaks silently.

**Source**: kotlinx.serialization GitHub issues #2803, #3067.

### 4e. Polymorphic Serialization Failures on JS

Polymorphic type hierarchies (`sealed class` + subclasses) used for DSL query AST nodes or migration step types can fail on Kotlin/JS with `"Cannot read property 'isInstance' of undefined"`. The `SerializersModule` must explicitly register all sealed subclasses.

**Source**: kotlinx.serialization GitHub issue #452.

---

## 5. DAG Dependency Hell

**Severity**: Medium
**Likelihood**: Medium

### The Problem

Sqitch's explicit `requires`/`conflicts` DAG is one of its most powerful features and one of its most common sources of user pain. Known failure modes:

- **Circular dependencies not caught until deploy time**: If the DAG construction code does not run a cycle-detection algorithm (DFS-based topological sort) eagerly at load time, a circular dependency will either cause an infinite loop at deploy, a stack overflow, or — worst — silently proceed in a wrong order if the cycle is discovered late.
- **Diamond dependencies with incompatible versions**: Migration A requires migrations B and C; both B and C require migration D at different versions. The resolver must pick one version of D; whichever it picks will satisfy one branch and break the other.
- **Implicit ordering assumptions**: Teams that write migrations without explicit `requires` declarations rely on the lexicographic file-naming order (Flyway's `V1__`, `V2__` style). When two developers create `V3__` independently on different branches, a merge conflict is detected in the filename — but only if both files have the same name. If one developer uses `V3__add_tag.sql` and another uses `V3__rename_page.sql`, there is no merge conflict, both apply, one silently wins depending on alphabetical order, and the other becomes an ignored "out of order" migration.
- **Stale dependency references**: A migration is deleted or renamed, but other migrations still declare it in their `requires` list. The DAG is now broken at load time, not deploy time.

### File-Content Specific Risks

- A migration that declares `requires: normalize-tags` must verify that the `normalize-tags` migration actually ran (checking the log), not just that the file exists. File presence ≠ applied.
- If a migration's desired-state DSL query references a page that a prerequisite migration creates, out-of-order execution will produce an error inside the diff engine ("page not found") rather than a clear dependency error.

### Mitigations

- Eager cycle detection: run topological sort at framework startup, before any migration is applied.
- Namespace migration IDs with a timestamp prefix to avoid collision (hybrid Flyway + Sqitch style: `20260414-001-normalize-tags`).
- Validate that all `requires` references resolve to known migration IDs at load time.

---

## 6. Concurrent Access

**Severity**: High
**Likelihood**: Low (desktop-first) → Medium (multi-device scenarios)

### How SQL Tools Handle It

- **Flyway**: Uses a database advisory lock (PostgreSQL) or a row lock in `flyway_schema_history`. Flyway 9.1.2+ added a transactional lock by default; this caused a production regression where `CREATE INDEX CONCURRENTLY` — which must run outside a transaction — deadlocked with Flyway's lock acquisition. (GitHub issue #1654, #3508.)
- **Liquibase**: Uses a `DATABASECHANGELOGLOCK` table with `LOCKED = 1`. If the process is killed mid-migration (e.g., Kubernetes pod eviction), the lock row is left set and **every future run hangs indefinitely**. The only recovery is `liquibase releaseLocks`. This is a well-documented production incident pattern for teams running Liquibase in Kubernetes.
- **Sqitch**: Advisory lock with `--lock-timeout` (default 60s); configurable per-engine.

### For File-Based Migrations

File systems provide no advisory lock primitive equivalent to a database lock. Standard approaches:

1. **Lockfile**: Write a `.stelekit-migration.lock` file containing the PID and start time before beginning; delete it on clean completion. On startup, check for a stale lock file (PID no longer running) and warn the user.
2. **Atomic file rename**: Write the lock file atomically using `Files.createFile` (throws if it exists) rather than a check-then-create pattern to avoid TOCTOU races.
3. **No cross-device locking**: If the graph directory is on a network file system (Syncthing, iCloud Drive, Dropbox), file locking is unreliable or silently ignored. This must be documented and the framework should detect common sync-conflict file patterns (e.g., `page (conflicted copy).md`) before running.

---

## 7. Rollback Complexity

**Severity**: High
**Likelihood**: High

### Why Teams Disable Rollback

Flyway Community Edition has **no rollback support** (it is a paid feature in Flyway Enterprise). Most Flyway teams in practice use the "fix forward" strategy: write a new migration that undoes the damage. Liquibase has rollback, but it requires manually authoring a `<rollback>` block for every `<changeSet>`; many teams skip writing rollbacks until they are needed, at which point they discover the rollback SQL was never tested and fails in production.

The Thoughtbot blog documents the industry consensus well: "Rolling back a migration that involves data deletion or modification leads to data loss; you must either accept that or restore from backup."

### File-Content Rollback is Even Harder

1. **Deleting content is irreversible without a backup**: If a migration removes all blocks matching a pattern (e.g., "delete all empty pages"), the deleted content is gone from the file system. Rollback requires a pre-migration snapshot.
2. **User edits during migration**: A migration that takes 10 minutes to process 10,000 pages can conflict with user edits made to the same files during the run. Rolling back must handle the "user edited file after we started processing it" case, which requires comparing the user's new version against both the pre-migration and post-migration state.
3. **The op-log is the rollback mechanism**: SteleKit's existing op-log and undo infrastructure is the right primitive. Every migration step must emit op-log entries so that the undo stack can reverse them. The risk is op-log overflow or corruption — if the op-log is truncated before a rollback is requested, partial rollback is impossible.
4. **Renaming a page cannot be "undone" if the new name was subsequently edited**: Rollback of a rename requires that the new-name page is in exactly the post-migration state; any subsequent user edit makes the rollback a three-way merge, not a simple revert.

### Recommendation

Treat rollback as a "soft guarantee" for non-destructive migrations (rename, add property, restructure blocks) and a "manual backup required" for destructive ones (delete page, delete block, strip content). Make this distinction explicit in the DSL — tag destructive migration steps as `@Destructive` and require the user to explicitly confirm backup before proceeding.

---

## 8. Testing Gaps

**Severity**: High
**Likelihood**: High

These are the gaps that consistently cause production failures in migration frameworks:

### 8a. No Testing Against Real Data Volume

Integration tests use 5–10 synthetic files. Production graphs have 5,000–50,000 files. Performance issues (O(n²) block scans, missing indexes on the SQLDelight migration log) go undetected until the first real run.

### 8b. No Testing of the Interrupted / Partial-Apply Case

The most dangerous scenario — the framework crashes midway — is almost never tested. Most test suites test "happy path apply" and "happy path rollback" but not "apply crashes after 40% completion, then re-run."

### 8c. No Testing With Adversarial File Content

Real Markdown graphs contain:
- Files with BOM headers
- Files with mixed `\r\n` / `\n` line endings
- Files with embedded null bytes (from corrupted syncs)
- Files with YAML frontmatter containing special characters
- Files that are symlinks
- Files that are in the middle of a Syncthing sync (`.sync-conflict-*` duplicates)

None of these appear in synthetic test fixtures.

### 8d. No Cross-Platform Checksum Consistency Tests

A checksum computed on JVM and stored in SQLDelight must round-trip identically when read on iOS (Kotlin/Native) and Web (Kotlin/JS). This is **never tested** by default. The Long precision issue (#4c above) makes this particularly dangerous.

### 8e. Rollback Not Tested Against Subsequent Edits

The rollback test suite typically tests: apply migration → rollback migration → check files match original. It does not test: apply migration → user edits file → rollback migration → observe conflict. The latter is the common production case.

### 8f. Migration DSL Parsing Not Fuzz-Tested

If the DSL query parser is custom-built (not a battle-tested library), it will have edge cases in string escaping, Unicode identifiers, and nested expressions that only appear when a real user writes a novel migration. Fuzzing the parser — even with a simple property-based test — catches these before production.

---

## Risk Matrix

| Risk | Severity | Likelihood | Mitigation |
|---|---|---|---|
| Partial apply leaves graph in unknown state | High | High | Op-log as transaction boundary; resumable migration with `RUNNING`/`COMPLETE` status |
| Checksum drift from editor/encoding differences | High | High | Normalize bytes before checksumming; versioned checksum algorithm |
| Regex transforms corrupt Markdown content | High | High | Parse to AST; never transform raw text with regex |
| Block ID loss during move/merge/split | High | High | Explicitly preserve/remap block IDs; validate ID inventory post-migration |
| Wikilink references break after page rename | High | High | Graph-wide reference rewrite must be atomic with the rename |
| kotlinx.serialization `KClass` crash on iOS | Medium | High | Use compile-time `@Serializable` only; no runtime serializer lookup |
| Long precision loss on Kotlin/JS | Medium | Medium | Serialize timestamps as ISO-8601 strings |
| DAG circular dependency not caught at load time | Medium | Medium | Eager topological sort at startup |
| Concurrent access / stale lockfile | High | Low-Medium | Atomic lockfile; detect stale locks; warn on network FS |
| Rollback impossible after destructive migration | High | High | Mark destructive steps; require explicit backup confirmation |
| Stuck lock on process kill (Kubernetes / crash) | High | Low | Lockfile with PID + stale detection; `releaseLock` repair command |
| Diff engine generates unwanted drops | High | Medium | Require `--allow-destructive` flag; default is safe/additive-only |
| Polymorphic serialization failure on JS | Medium | Medium | Explicit `SerializersModule` registration for all sealed subclasses |
| No testing at production data volumes | High | High | Benchmark suite against graph of 10k+ files |
| Partial-apply scenario never tested | High | High | Explicit chaos test: inject failure at N% completion |

---

## Recommendations

**1. Make the op-log the transaction boundary.**
Before writing any file, record the pre-migration state to the op-log as a single migration transaction. On startup, detect `RUNNING` migrations (process died) and either offer to resume or roll back via the op-log. This is the only way to recover from partial apply without requiring a user backup.

**2. Parse, never regex.**
Commit to using SteleKit's `OutlinerPipeline` AST for all migration transforms. A migration that operates on the parsed block tree cannot accidentally corrupt fenced code blocks, YAML frontmatter, or block ID annotations. Regex transforms are strictly forbidden in the migration DSL.

**3. Normalize bytes before checksumming, version the algorithm.**
Checksum logic must: strip BOM, normalize to LF, strip trailing whitespace per line, then SHA-256. Store the checksum as `sha256-v1:<hex>` so future algorithm changes are detectable. Add a test that the same migration file produces identical checksums on all three KMP targets (JVM, iOS, JS).

**4. Run topological sort eagerly and validate all `requires` references at load time.**
Never allow a migration to run if its declared dependencies are missing or form a cycle. The error must name the exact migration ID that is missing or the exact cycle path, not a generic failure.

**5. Mark destructive operations explicitly and default to safe mode.**
Any migration step that can delete content (remove block, delete page, strip property) must be tagged `@Destructive` in the DSL. The apply engine must refuse to execute destructive steps unless `--allow-destructive` is passed or the user explicitly confirms. This follows Atlas's analyzer model: detect impact before applying.
