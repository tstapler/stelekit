# ADR-002: Agenda Query Strategy — In-Memory Filter over FTS

## Status
Accepted

## Context
The agenda view needs to surface all blocks for a given date that have `SCHEDULED` or `DEADLINE` metadata matching today. Options:

1. **In-memory filter**: load today's journal page blocks + query all pages via `BlockRepository.getBlocksForPage`, filter by `scheduled`/`deadline` properties.
2. **FTS/SQL text search**: use SQLDelight FTS5 to search for `SCHEDULED: <2026-04-16` patterns across all blocks.
3. **Dedicated `scheduled_date` and `deadline_date` columns** on the `blocks` table, indexed.

## Decision
Use **in-memory filter** for MVP (Story 3). A follow-up migration can add indexed columns if performance degrades at scale.

## Rationale
- **Zero schema change for MVP**: `ParsedBlock.scheduled` and `ParsedBlock.deadline` are already parsed by `TimestampParser` and stored in `Block.properties` (keys `scheduled`, `deadline`).
- **Agenda scope is bounded**: a daily agenda rarely exceeds a few hundred tasks even in large graphs. In-memory filtering over all blocks with non-null `scheduled`/`deadline` is fast (<50 ms for 10k blocks).
- **Avoids premature optimization**: the dedicated-column approach requires a DDL migration and `MigrationRunner` wiring, which is higher risk for a feature in initial delivery.
- **Queryable today**: `SqlDelightBlockRepository` already has `getBlocksForPage`; we add a new `getBlocksWithScheduledOrDeadline(date)` method that queries properties table or scans in memory.

## Consequences
- `AgendaRepository` (new interface) wraps the query logic. Swapping to an indexed strategy later requires only changing the implementation.
- Memory usage is proportional to number of scheduled blocks. Acceptable until graphs exceed ~50k blocks.
- The `scheduled`/`deadline` property keys must be populated during `GraphLoader` parsing — verified this is already done via `TimestampParser` in `MarkdownParser.kt`.

## Patterns Applied
- **Repository pattern**: `AgendaRepository` isolates query strategy behind an interface.
- **Strategy pattern**: the query implementation can be swapped without changing callers.
