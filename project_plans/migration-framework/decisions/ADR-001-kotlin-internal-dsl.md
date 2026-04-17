# ADR-001: Kotlin Internal DSL over External SQL Parser

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The migration framework needs a way for contributors to express desired-state transformations: "all blocks that lack a `type::` property should have `type:: note` added". The requirements explicitly rule out a full SQL parser/runtime; a constrained DSL is sufficient.

Two options were evaluated:

**Option A — Hand-rolled Kotlin internal DSL (lambda-with-receiver)**

```kotlin
migration("V001__add-missing-type-property") {
    description = "Add type:: property to all blocks that lack one"
    requires("V000__baseline")

    apply {
        forBlocks(where = { block -> !block.properties.containsKey("type") }) {
            setProperty("type", "note")
        }
    }

    revert {
        forBlocks(where = { block -> block.properties["type"] == "note" }) {
            deleteProperty("type")
        }
    }
}
```

Pure Kotlin, no parser infrastructure. Migrations are `.kt` source files compiled into the app or a migrations module. `@DslMarker` prevents scope leakage. Full IDE support (completion, type checking, refactoring).

**Option B — ANTLR 4 via `antlr-kotlin` (external text-file grammar)**

A grammar file defines a SQL-like mini-language (e.g. `FOREACH block WHERE property('type') IS NULL SET property('type') = 'note'`). `antlr-kotlin-runtime` parses text at runtime. Migrations are external `.stele-migration` files distributed alongside graph data.

- Adds Gradle plugin, grammar file, generated-source build step.
- `antlr-kotlin` has ~269 GitHub stars as of 2026; active but narrow adoption.
- ANTLR runtime compiles to JVM, Android, JS, WASM, and Native via shims — but each new KMP target requires validation.
- End users could author migration text files without recompiling the app.

---

## Decision

**Option A: hand-rolled Kotlin internal DSL.**

---

## Rationale

1. **KMP commonMain constraint**: ANTLR adds grammar tooling overhead and requires shim validation on each new KMP target. The Kotlin DSL compiles to all four targets (JVM, Android, iOS, Web) with zero additional build infrastructure.

2. **Requirements scope**: The requirements explicitly call for a "constrained DSL" and rule out a "full SQL parser/runtime". A Kotlin builder covers all required query semantics (selector predicates, property upsert/delete, page rename) and is straightforward to extend by adding new builder methods rather than grammar productions.

3. **Compile-time safety**: Block property predicates like `block.properties.containsKey("type")` are type-checked at compile time. A text-file DSL would catch predicate errors at runtime, after the user has already written and saved the file.

4. **IDE support**: Kotlin lambda DSLs have full IDE completion, inline documentation, and refactoring support. A custom grammar has none of these without separate tooling.

5. **No external file distribution needed for v1**: Migrations in v1 are contributor-authored and compiled into the SteleKit binary (or a dedicated `migrations` module). The "end users author migration text files at runtime" use case is out of scope for v1.

6. **Precedent in codebase**: SteleKit's existing SQLDelight `.sq` files already demonstrate the project's comfort with query-as-code patterns. The migration DSL follows the same philosophy.

---

## Consequences

- New package: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/`
- `MigrationDsl.kt` — `@DslMarker` annotation + `MigrationBuilder` / `ApplyScope` / `BlockScope` classes
- Migrations live in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/migration/migrations/` as `V001_*.kt`, etc.
- Adding a new DSL primitive (e.g., `renameProperty`) is a Kotlin function addition, not a grammar change.
- If external text-file authoring is required in a future version, Option B remains available as an evolution path; the `Migration` data class interface is stable enough to accommodate both code-authored and parser-authored migration objects.
