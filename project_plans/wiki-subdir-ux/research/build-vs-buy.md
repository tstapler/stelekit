# Build vs. Buy Research: Wiki Subdir UX

## Recommendation: BUILD from scratch — no external dependencies or SaaS

All primitives needed for this feature already exist in the codebase. The work is
composition and extension of existing components, not invention of new capabilities.

---

## 1. Existing OSS Library or Framework

### Option: Adopt a file-sync/merge library (e.g. `rsync`-wrapper, JGit's TreeWalker)
- **Pros:**
  - Would offload complex directory-diff/move logic
  - Established handling of edge cases (permissions, partial failures, cross-filesystem)
  - JGit is already used for git operations in this project (`platform/git/`)

- **Cons:**
  - The feature's needs are narrow: a bounded (1-2 level) candidate scan, a
    three-way file move/merge between two known directories, and a dry-run manifest.
    A general-purpose sync engine is massive over-engineering.
  - No existing Kotlin Multiplatform library handles the SAF ↔ JVM ↔ Wasm filesystem
    abstraction this project uses. JGit is JVM-only — it cannot cover Android SAF or
    Web/Wasm targets.
  - Adding a new dependency to a KMP project requires native bindings for every target,
    ballooning the dependency graph for a feature that the existing `FileSystem`
    abstraction already covers.
  - The SAF-specific move semantics (`DocumentsContract.moveDocument` vs stream-copy
    fallback, per-OEM `FLAG_SUPPORTS_MOVE` variability) are Android-platform concerns
    that no cross-platform library can handle correctly — they must be platform-specific
    by nature.

- **Verdict: NOT RECOMMENDED** — the primitives exist, the scope is narrow, and
  platform-specific behavior can't be outsourced.

---

## 2. SaaS / Managed API

### Option: Outsource file reconciliation to a hosted service
- **Pros:** None applicable — this is a local-on-device-only feature. All data stays on
  the user's device or in their own git remote.

- **Cons:**
  - **Data residency violation:** requirements.md §Non-functional Requirements mandates
    "all data stays on-device or in the user's own git remote; no new network surface."
    Sending user note files to an external API for diff/merge processing would violate
    this constraint.
  - **Vendor lock-in:** tying core file operations to a hosted service creates a
    dependency the user can't control or audit.
  - **Integration complexity:** wiring a SaaS API into the existing `DatabaseWriteActor`
    + `GitSyncService` sequencing would be more complex than using the primitives
    already in-tree.
  - **Cost:** recurring per-user API cost for infrastructure the solo-developer
    maintainer can't sustainably pay.

- **Verdict: NOT RECOMMENDED** — violates the security/data-residency constraint and
  adds unnecessary network surface to a personal note-taking app.

---

## 3. LLM-Generated Implementation vs. Battle-Tested Library

### The "merge" sub-problem
One might reach for an existing diff/merge library (e.g. `java-diff-utils`, `jgit-diff`)
to handle same-filename conflicts during a Merge operation.

- **Risk of bespoke LLM-generated diff/merge:** Correctness is critical — a flawed merge
  could silently corrupt user notes. Diff/merge algorithms have subtle edge cases
  (encoding, line endings, binary detection) that are easy to get wrong.

- **Why custom is acceptable here:** The existing `DiskConflictDialog` +
  `DiskConflictBlockMatcher` already provides battle-tested merge conflict resolution
  for external-file-change reconciliation. The requirements explicitly mandate reusing
  this machinery (requirements.md §C.18: "hand it to `DiskConflictDialog`/
  `DiskConflictBlockMatcher` rather than building new merge logic from scratch"). This
  isn't building a new diff engine — it's reusing the existing one in a new context.

- **Verdict:** No new merge library needed. The existing, tested conflict-resolution
  path should be reused. For the directory-level move/merge orchestration itself, the
  logic is straightforward (walk directory, copy/move per file, handle conflicts via
  existing dialog) — no complex algorithm to get wrong.

---

## 4. Fork or Adapt

### Option: Fork an existing directory-diff tool
- **Pros:** Would inherit edge-case handling for directory traversal, file comparison,
  and partial-failure recovery.

- **Cons:**
  - No existing KMP-compatible directory-diff tool in the ecosystem that handles the
    SAF/JVM/Wasm abstraction.
  - Forking a JVM-only tool (e.g. a Java file-sync library) wouldn't help with Android
    SAF or Web/Wasm targets.
  - The existing `FileSystem.renameFile()` + `listFilesRecursiveWithModTimes()`
    primitives already provide the necessary building blocks for a bounded, shallow
    directory walk — no fork needed.

- **Verdict: NOT RECOMMENDED** — no suitable KMP-compatible fork exists, and the needed
  primitives are already in-tree.

---

## Conclusion

**Build from scratch using existing codebase primitives.** No external library, SaaS,
or fork is warranted. The feature requires:

1. Extracting the existing candidate-scan logic from `GraphDiagnostics.kt` into a
   reusable function
2. Extending the banner system in `GraphContentMainArea.kt`
3. Building a three-way move/merge dialog (sibling to `StorageMoveChoiceDialog.kt`)
4. Assembling per-file moves from `FileSystem.renameFile()` +
   `listFilesRecursiveWithModTimes()` (and adding a copy primitive for SAF merge)
5. Wiring through `GitSyncService.commitLocalChanges()` and `DatabaseWriteActor`
   for consistency
6. Adding a marker-file system for crash recovery

All of these use primitives already present in the codebase. The architectural
disposition from Phase 2 research confirms this is a **Refactor-First** effort: compose
and extend existing components, don't invent new abstractions.
