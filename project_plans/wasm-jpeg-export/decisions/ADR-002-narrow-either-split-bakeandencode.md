# ADR-002: Narrow `Either` split at `bakeAndEncode()`, not at `ImageEncoder.encodeToJpeg`

**Status**: Accepted
**Date**: 2026-09-22
**Context project**: wasm-jpeg-export

## Context

Acceptance criterion 3 requires that encode failure be distinguishable from
success — today `ImageEncoder.encodeToJpeg` returns a bare `ByteArray` with
"empty array on failure" as its documented contract on JVM
(`ImageEncoder.jvm.kt`), Android (`ImageEncoder.android.kt`), and iOS
(`ImageEncoder.ios.kt`, deliberately out of scope). Repo `CLAUDE.md`'s Arrow
rule requires `Either<DomainError, T>` at "repository and service method"
boundaries and forbids nullable/bare-return error signaling there.

`research/architecture.md` §3 and `research/features.md` §1 lay out the
tension directly: widening `encodeToJpeg` itself to `Either` would force
JVM/Android/iOS changes the requirements explicitly mark out of scope, for
zero current production callers (`bakeAndEncode` has no non-test call sites
— `grep -rn "bakeAndEncode(" kmp/src` returns only
`AnnotationExporterTest.kt`).

## Decision

Change only `AnnotationExporter.bakeAndEncode()`'s return type, to
`Either<DomainError.ExportError, ByteArray>`. Add
`DomainError.ExportError.EncodingFailed(message: String)` alongside the
existing `SerializationFailed`/`ClipboardFailed`/`ShareFailed` variants
(`DomainError.kt:104-108`). `bakeAndEncode()` maps an empty `ByteArray` from
`ImageEncoder.encodeToJpeg(...)` to `Left(EncodingFailed(...))`, otherwise
`Right(bytes)`.

`ImageEncoder.encodeToJpeg`'s `expect`/`actual` signature stays bare
`ByteArray` (empty-on-failure) on **all four** platforms — no code changes to
JVM, Android, or iOS actuals.

## Consequences

- iOS's stub (`ByteArray(0)`) automatically maps to `Left(EncodingFailed)` at
  the `bakeAndEncode` boundary with zero iOS code changes — satisfies the
  "iOS explicitly left alone" non-goal in both behavior and code.
- JVM/Android's existing catch-to-empty-array behavior is upgraded to a
  distinguishable `Left` for free, at one `commonMain` call site, instead of
  three additional platform-file edits.
- Test blast radius is exactly the 4 existing `bakeAndEncode` call sites in
  `AnnotationExporterTest.kt` (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/annotate/AnnotationExporterTest.kt`),
  plus one new regression test for the `Left` path.
- `ImageEncoder` remains, by design, a thin platform codec primitive (like
  `ImageIO`/`Bitmap.compress` themselves) rather than a repository/service
  boundary — the Arrow convention's "repository and service methods" scope is
  satisfied at `AnnotationExporter`, the actual `commonMain` orchestration
  point, without over-applying the rule to every function that can fail.
- If `ImageEncoder.encodeToJpeg` later gains a second caller with different
  error-handling needs, this decision should be revisited — it is scoped to
  today's zero-caller reality, not a permanent architectural stance.
