# ADR-004: Versioned Checksum Normalization Strategy

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The migration framework must compute a checksum for each migration's DSL body to detect tampering after a migration has been applied (the Liquibase `MD5SUM` pattern). If a previously-applied migration's DSL body is changed, the stored checksum diverges from the recomputed value, and the framework refuses to run.

The pitfalls research (`research/pitfalls.md` §2) documents that checksum drift from invisible byte-level differences is a high-severity, high-likelihood failure mode in migration tools:

- **Line endings**: `\r\n` vs. `\n` produces a different SHA-256 byte sequence.
- **BOM**: A UTF-8 BOM header changes every byte offset.
- **Trailing whitespace**: Many editors strip trailing whitespace on save.
- **Algorithm version change**: Liquibase 4.22.0 changed its whitespace-normalization logic and invalidated all existing checksums overnight (GitHub issue #3549).

For SteleKit, the checksum must cover the migration's DSL body (the lambda text as expressed at definition time), not the entire `.kt` file. This is a design distinction: comment changes and Kotlin formatting changes to the surrounding file must not invalidate the checksum.

Two strategies were evaluated:

**Option A — Raw byte checksum of the full migration source file**

SHA-256 of the raw bytes of the `.kt` file that contains the migration. Simple to compute; hard to keep stable. Any reformatting of the file (Kotlin formatter, IDE cleanup, added comments) changes the checksum.

**Option B — Normalized checksum of the migration's declared `id` + `description` + `apply` body string**

The migration author provides a canonical string representation of the migration body as a field on the `Migration` object. Normalization before hashing:
1. Strip UTF-8 BOM if present.
2. Normalize all line endings to LF (`\n`).
3. Strip trailing whitespace from each line.
4. Trim leading/trailing blank lines from the entire string.
5. SHA-256 the normalized UTF-8 bytes.
6. Store as `sha256-v1:<hex>` to version the algorithm.

The `checksum` field is computed from a dedicated `checksumBody: String` property on the `Migration` data class. Migration authors set this to a stable string (typically the canonical DSL body text) when defining the migration.

---

## Decision

**Option B: normalized checksum of a declared `checksumBody` field, stored with algorithm version prefix `sha256-v1:`.**

---

## Rationale

1. **Stability across formatting**: By checksumming a declared `checksumBody` field rather than raw file bytes, the framework is immune to Kotlin formatter rewrites, comment additions, and import reordering. The author controls what is checksummed.

2. **Algorithm versioning prevents silent invalidation**: Storing the checksum as `sha256-v1:<hex>` means a future algorithm change produces `sha256-v2:...` entries. The framework can detect version mismatches and apply the correct verification algorithm per row, rather than forcing a global `clearChecksums` operation.

3. **Normalization eliminates CRLF/BOM drift**: The three most common sources of checksum instability (BOM, `\r\n`, trailing whitespace) are eliminated by the normalization step before hashing. This is directly analogous to the mitigation recommended in `pitfalls.md` §2.

4. **Repair path**: `MigrationRunner.recalculateChecksums(graphId)` recomputes all stored checksums using the current normalization algorithm and updates `migration_changelog`. This is the equivalent of `flyway repair` and `liquibase clearChecksums`. It requires no `--allow-destructive` flag because it is non-destructive to graph content.

5. **Cross-platform consistency**: The normalization algorithm is pure string manipulation — strip BOM, replace `\r\n` → `\n`, strip trailing whitespace per line. `ContentHasher.sha256()` is already available in `commonMain`. The same computation produces identical results on JVM, iOS (Native), and Web (JS) because it does not depend on platform-specific byte encodings.

---

## Consequences

- `Migration` data class gains a `checksumBody: String` field. The migration author sets this to a stable representation of the migration's intent (e.g. the DSL body as a raw string literal).
- `MigrationChecksumComputer` in `migration/` normalizes and hashes the `checksumBody`. This is the only place SHA-256 is computed for migration verification.
- `migration_changelog.checksum` stores `sha256-v1:<64-char-hex>`.
- On startup, `MigrationRunner` reads all applied migration IDs from `migration_changelog`, recomputes checksums for each corresponding registered `Migration`, and throws `MigrationTamperedError` if any mismatch is found. The error names the migration ID and the diverging checksums.
- `MigrationRunner.recalculateChecksums(graphId)` provides the repair path for legitimate `checksumBody` updates (e.g. a bug fix to the migration's comment text).
- A dedicated cross-platform test in `businessTest` verifies that `MigrationChecksumComputer.compute(body)` returns identical strings for identical inputs regardless of the KMP target — specifically testing BOM-stripped, CRLF-normalized, and trailing-whitespace-stripped inputs.
