# ADR-004: FTS5 Query Sanitizer Redesign

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler

---

## Context

The current `sanitizeFtsQuery` function in `SqlDelightSearchRepository` uses a blanket regex that strips all FTS5 operator characters:

```kotlin
query.trim()
    .replace(Regex("""["()*:^~{}\[\]!]"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()
```

This approach:
1. Strips `"` which makes phrase search (`"meeting notes"`) impossible.
2. Strips `*` which is redundant (the SQL appends `*` anyway) but also prevents user-typed prefix queries from working as expected in edge cases.
3. Strips `()` which prevents grouped queries like `(tax OR taxes)`.
4. Does not handle SQL injection via FTS5 operators (`;`, `--`) — though SQLDelight's parameter binding mitigates the primary SQL injection risk, malformed FTS5 syntax can still cause `SQLiteException`.

The feature request explicitly requires phrase search via `"exact phrase"` syntax. The sanitizer must be redesigned to allow safe passthrough of phrase operators while still protecting against malformed input.

## Decision

Replace `sanitizeFtsQuery` with a dedicated `FtsQueryBuilder` class that uses a finite-state parser rather than regex character stripping.

### Parsing Rules

The builder processes the raw query string left-to-right, tracking a state machine with two states: `OUTSIDE_PHRASE` and `INSIDE_PHRASE`.

**Token classification**:

| Input pattern | Action |
|--------------|--------|
| `"` (opening, outside phrase) | Begin phrase segment |
| `"` (closing, inside phrase) | End phrase segment, emit `"phrase tokens"` |
| `"` (end of string, inside phrase — unbalanced) | Discard phrase segment, treat as plain tokens |
| Alphanumeric token | Emit as-is |
| Leading `OR`, `AND`, `NOT` (outside phrase, at start or after stripping) | Discard |
| `:` (colon — FTS5 column filter syntax) | Strip from the token |
| `(` `)` | Strip (grouped queries deferred to a future enhancement) |
| `^` `~` `{` `}` `[` `]` `!` `;` `--` | Strip |

**Prefix wildcard rule**: Append `*` to the last emitted unquoted token only (supports in-progress word completion). Phrase segments are never suffixed with `*`.

**Multi-token OR semantics**: For unquoted multi-token queries, emit tokens joined with ` OR ` to allow partial matches at lower rank (per ADR-002). Single-token queries emit the token directly (no `OR` needed).

**Output examples**:

| Raw input | `FtsQueryBuilder.build()` output |
|-----------|----------------------------------|
| `taxes` | `taxes*` |
| `2025 taxes` | `2025 OR taxes*` |
| `"meeting notes"` | `"meeting notes"` |
| `"meeting notes" taxes` | `"meeting notes" OR taxes*` |
| `"unclosed taxes` | `unclosed OR taxes*` (unbalanced quote stripped, tokens rescued) |
| `OR AND taxes` | `taxes*` (leading operators stripped) |
| `taxes::property` | `taxes*` (colon stripped) |
| `` (empty) | `` (empty — caller skips query) |
| `   ` (whitespace) | `` (empty) |
| `taxes; DROP TABLE` | `taxes DROP TABLE*` (semicolon stripped, no SQL harm due to parameter binding) |

### Class Design

```kotlin
class FtsQueryBuilder {
    fun build(rawQuery: String): String
    
    companion object {
        val DEFAULT = FtsQueryBuilder()
    }
}
```

A stateless class with a single public method makes unit testing trivial. The repository holds a `val ftsQueryBuilder = FtsQueryBuilder.DEFAULT` and calls `ftsQueryBuilder.build(rawQuery)`.

## Alternatives Considered

### Option A: Keep regex stripping, add special-case for balanced quotes

Extend the current regex to detect balanced quote pairs before stripping, preserve them, then apply the existing stripping rules to everything else.

**Rejected because**:
- Regex-based phrase detection is fragile with nested edge cases (odd number of quotes, quotes inside tokens, adjacent phrases).
- The resulting code would be harder to reason about than a simple state machine.
- Does not address the multi-token OR semantics needed for partial matching.

### Option B: Use a third-party FTS query parser library

Find a KMP-compatible library that parses and normalises FTS5 query syntax.

**Rejected because**:
- No suitable KMP library exists at this time. The FTS5 query syntax is simple enough to implement in ~80 lines of Kotlin.
- Avoiding a dependency for this is preferable in a local-first mobile app.

### Option C: Wrap SQLite query in a try/catch, retry with fully stripped query on exception

Attempt the raw query; on `SQLiteException`, strip all operators and retry.

**Rejected because**:
- Two round trips to the database on malformed input is wasteful.
- Silent degradation (phrase search silently becomes plain token search) is worse UX than a consistent parser.
- Does not address the phrase search feature requirement.

## Consequences

**Positive**:
- Phrase search (`"exact phrase"`) works correctly.
- Multi-token implicit OR enables partial matching (ADR-002 requirement).
- The parser is a pure Kotlin class — fully unit-testable without any mocking.
- Malformed FTS5 syntax no longer causes `SQLiteException`.
- SQLDelight parameter binding still prevents SQL injection; the builder adds FTS5-level safety on top.

**Negative / Risks**:
- The builder deliberately discards `()` grouping syntax. Users familiar with Logseq's advanced query syntax may try `(taxes OR receipts)` and be surprised when it's treated as plain tokens. Document this limitation and add support for `()` grouping as a future enhancement.
- `*` is only appended to the last unquoted token. A user typing `tax* rec*` (explicit multi-prefix) will get `tax OR rec*` — the explicit `*` on `tax` is stripped and replaced. This matches the design intent (prefix matching is for the last in-progress token) but should be documented.
- The builder's `OR`-joining of multi-token queries changes search semantics from the current implicit AND (FTS5 default). Test with an in-memory database to confirm that BM25 correctly ranks AND-matches above OR-matches — this is a core assumption of ADR-002.
