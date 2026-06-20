# ADR-014: BlockType Sealed Class with SQLDelight ColumnAdapter

**Status**: Accepted
**Date**: 2026-06-19
**Feature**: Block Operations Performance — Phase 2 (R3)

## Context

`Block.blockType` in `Models.kt` is currently typed as `String` with a private `validBlockTypes` allowlist and a runtime `require` check in `init`:

```kotlin
private val validBlockTypes = setOf(
    "bullet", "paragraph", "heading", "code_fence", "blockquote",
    "ordered_list_item", "thematic_break", "table", "raw_html",
    "image_annotation"
)
require(blockType in validBlockTypes) { "Invalid blockType: $blockType" }
```

This creates two problems:

**Compile-time unsafety**: Any string is valid at the type level. Comparisons against block type — branching on `"bullet"`, `"heading"`, etc. — are stringly-typed and not exhaustive. Adding a new block type requires manually hunting every `when` or `if` branch in the codebase; the compiler offers no help.

**Crash on unknown values**: The `require` check throws `IllegalArgumentException` for any `block_type` value not in `validBlockTypes`. Logseq plugin data can introduce custom block types (e.g., `"logseq.pom/later"`, `"excalidraw/drawing"`). When SteleKit reads such a page, `toModel()` in `SqlDelightBlockRepository` calls `knownBlockTypeOrDefault()` as a fallback before constructing `Block` — but this silently coerces the unknown type to `"bullet"`, losing type fidelity on round-trip write back to disk.

The existing `ParsedModels.BlockType` sealed class in the parser layer has the right shape, but it is not used in the domain model, and it does not include `ImageAnnotation` (which exists only as the string constant `BlockTypes.IMAGE_ANNOTATION = "image_annotation"`).

### Why This Is Classified as a Correctness Fix

The `require` crash path is guarded by `knownBlockTypeOrDefault()` at the repository boundary, so in practice pages with unknown block types degrade to `"bullet"` rather than crashing. However, this means round-tripping an unknown-type block through SteleKit silently rewrites it as a bullet on next save — a data-loss bug for Logseq plugin users. The sealed class with `Unknown(raw)` fallback preserves the original string through the domain layer and writes it back unchanged.

## Decision

Replace `Block.blockType: String` with `Block.blockType: BlockType` where `BlockType` is a sealed class defined in the domain model, with a SQLDelight `ColumnAdapter<BlockType, String>` handling DB↔model conversion.

### 1. Sealed class definition

```kotlin
sealed class BlockType {
    data object Bullet : BlockType()
    data object Paragraph : BlockType()
    data object Heading : BlockType()
    data object CodeFence : BlockType()
    data object Blockquote : BlockType()
    data object OrderedListItem : BlockType()
    data object ThematicBreak : BlockType()
    data object Table : BlockType()
    data object RawHtml : BlockType()
    data object ImageAnnotation : BlockType()
    data class Unknown(val raw: String) : BlockType()

    fun toDbString(): String = when (this) {
        is Bullet -> "bullet"
        is Paragraph -> "paragraph"
        is Heading -> "heading"
        is CodeFence -> "code_fence"
        is Blockquote -> "blockquote"
        is OrderedListItem -> "ordered_list_item"
        is ThematicBreak -> "thematic_break"
        is Table -> "table"
        is RawHtml -> "raw_html"
        is ImageAnnotation -> "image_annotation"
        is Unknown -> raw
    }

    companion object {
        fun fromString(s: String): BlockType = when (s) {
            "bullet" -> Bullet
            "paragraph" -> Paragraph
            "heading" -> Heading
            "code_fence" -> CodeFence
            "blockquote" -> Blockquote
            "ordered_list_item" -> OrderedListItem
            "thematic_break" -> ThematicBreak
            "table" -> Table
            "raw_html" -> RawHtml
            "image_annotation" -> ImageAnnotation
            else -> Unknown(s)
        }
    }
}
```

`data object` subtypes (Kotlin 1.9+) provide correct `equals`/`hashCode`/`toString` for singletons. `Unknown(val raw: String)` preserves the original DB string, enabling lossless round-trips for plugin-defined block types.

### 2. SQLDelight ColumnAdapter

No schema change is needed — `block_type TEXT NOT NULL DEFAULT 'bullet'` stays TEXT. The adapter wires the conversion at the generated-code boundary:

```kotlin
object BlockTypeAdapter : ColumnAdapter<BlockType, String> {
    override fun decode(databaseValue: String): BlockType = BlockType.fromString(databaseValue)
    override fun encode(value: BlockType): String = value.toDbString()
}
```

The adapter is registered in `DriverFactory` / database construction:

```kotlin
SteleDatabase(
    driver = driver,
    blocksAdapter = Blocks.Adapter(blockTypeAdapter = BlockTypeAdapter)
)
```

With this in place, SQLDelight's generated `Blocks` data class changes `block_type: String` to `block_type: BlockType`. All call sites that read `block.blockType` automatically receive a `BlockType` value with no manual conversion.

### 3. `Block.init` validation removal

The `validBlockTypes` set and `require(blockType in validBlockTypes)` check are removed. The sealed class makes the distinction between known and unknown types structural — unknown values become `Unknown(raw)` rather than crashing. The `knownBlockTypeOrDefault()` fallback helper in `SqlDelightBlockRepository` is also removed, since `fromString()` now handles graceful degradation at the adapter boundary.

### 4. Write path

All methods that currently pass `block.blockType` (a `String`) to generated queries (`insertBlock`, `updateBlockForSave`, `updateBlockFull`, etc.) automatically pick up the `BlockType` type once `Block.blockType` changes. `RestrictedDatabaseQueries` forwarding stubs pass `blockType: BlockType` to the generated queries; the ColumnAdapter encodes it to `String` before binding.

### 5. Caller impact

All `when (block.blockType)` expressions in the UI and repository layers become exhaustive `when (block.blockType) { is BlockType.Bullet -> … }` expressions with compiler-enforced coverage. Any new `BlockType` variant added in the future requires a branch in every exhaustive `when` — which is the intended behavior. The `Unknown` variant serves as the fallback arm for all existing `else ->` branches.

## Rationale

- **Compile-time exhaustiveness**: the compiler now enforces that all block type variants are handled. Callers cannot accidentally miss a case.
- **Unknown types survive round-trips**: `Unknown(raw)` stores the original DB string and writes it back unchanged. Plugin-defined block types are no longer silently rewritten to `"bullet"` on save.
- **No schema change**: `block_type TEXT` stays TEXT. The ColumnAdapter is the only new infrastructure, and it is a standard SQLDelight 2.x pattern.
- **`Block.init` throw removed**: the crash path on unknown block types is eliminated. Page loads with plugin data no longer fail at `toModel()`.
- **Aligns with ADR-010**: ADR-010 (make illegal states unrepresentable) established the principle of using Kotlin's type system to eliminate invalid states. `BlockType` as a sealed class is a direct application of that principle.

## Consequences

**Positive**

- All `blockType` comparisons in non-migration code are exhaustive `when` expressions; stringly-typed comparisons are eliminated.
- Unknown Logseq plugin block types (`Unknown(raw)`) survive read-modify-write without data loss.
- `Block.init` `require` crash is removed; page loads with plugin data no longer throw at the model boundary.
- `knownBlockTypeOrDefault()` and `validBlockTypes` are deleted; the graceful fallback logic is consolidated in `BlockType.fromString()`.

**Negative / Trade-offs**

- Breaking change in the domain model API: all callers of `block.blockType` must be updated to use `BlockType` rather than `String`. This is a broad but mechanical change — the compiler surfaces every site.
- `ParsedModels.BlockType` (the parser's existing sealed class) and the new domain `BlockType` sealed class must be kept in sync or unified. If they remain separate, the parser→domain mapping at the repository boundary must translate between them. Unification (making the parser use the same `BlockType`) is preferred but requires the parser to depend on the domain model.
- `ImageAnnotation` must be added to the sealed class as a first-class variant — it is currently only a string constant. Any code that currently handles `"image_annotation"` via the string must be updated to `is BlockType.ImageAnnotation`.
- New `BlockType` variants added in the future become source-breaking changes for all exhaustive `when` expressions. The `Unknown` catch-all arm mitigates this for runtime data but not for compile-time switch completeness.

## Related

- Requirements: `project_plans/block-ops-perf/requirements.md` § R3
- Research: `project_plans/block-ops-perf/research/architecture.md` § 2, `research/pitfalls.md` § 5
- Complements ADR-010 (make illegal states unrepresentable): `BlockType` sealed class is a direct application of that principle to the block type domain
- No migration entry in `MigrationRunner.all` required (column stays TEXT)
