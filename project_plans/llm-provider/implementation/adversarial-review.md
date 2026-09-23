# Adversarial Review: llm-provider (Round 3 — Final)

**Date**: 2026-06-13
**Verdict**: CONCERNS (0 blockers, 2 concerns, 2 minors)

## Round 1 Blockers — Resolution Status

- [x] TagSuggestionWorker opens DB without migration guard — RESOLVED
- [x] Worker had no graphId / initialization sequence — RESOLVED
- [x] Page-scope LLM fan-out: N calls per block — RESOLVED
- [x] insertTextAtCursor cursor-sensitivity — RESOLVED

## Round 2 Blockers — Resolution Status

- [x] `AhoCorasickMatcher.scan()` does not exist — RESOLVED: plan now calls `matcher.findAll(blockContent.lowercase())` (plan line 281; comment explicitly says "NOT .scan()")
- [x] `DomainError.NetworkError.Timeout` constructed without message — RESOLVED: plan line 173 shows `DomainError.NetworkError.Timeout("LLM tag suggestion timed out after ${timeoutSeconds}s").left()` with the required `message` argument
- [x] `appendToBlock` implementation references `_blockStates` (non-existent field) — RESOLVED: Task 4.0a now reads `_blocks.value.values.flatten().firstOrNull { it.uuid == blockUuid }` and delegates to `insertTextAtCursor(blockUuid, text, overrideCursorIndex = block.content.length)` — exactly matching `BlockStateManager`'s actual implementation pattern
- [x] `blockStateManager.blocks.value` iterated as List (it is Map<String,List<Block>>) — RESOLVED: Task 4.2.1a now does `blockStateManager.blocks.value[page.uuid.value] ?: emptyList()` then iterates `pageBlocks`, using `block.content` directly (not a non-existent `getContent()`)

## Round 3 Findings

### Blockers

None. All compile-breaking and data-loss issues from rounds 1 and 2 are confirmed resolved.

### Concerns

- [ ] **CONCERN — `appendTagToLastBlock` in `VoiceCaptureViewModel` calls a non-existent method name** — Story 4.3.3 / Task 4.3.3a directs the implementer to "add `fun appendTagToLastBlock(text: String)` to `VoiceCaptureViewModel` that calls `journalService.appendToTodayBlock(text)` or equivalent." `JournalService` has no `appendToTodayBlock(text)` method. Its actual API is `appendToToday(content: String)` (no "Block" suffix). An implementer following the plan verbatim will get a compile error. The task must specify the correct method name: `journalService.appendToToday(text)`. (Alternatively the implementer will notice the "or equivalent" hedge and find the correct name, but the explicit wrong name is still a trap.)

- [ ] **CONCERN — Story 4.2.1 acceptance criteria still says `insertTextAtCursor` where the task code says `appendToBlock`** — The acceptance criterion on plan line 695 reads "auto-applies `autoApplied = true` matches immediately via `insertTextAtCursor`." The actual task code directly below (Task 4.2.1a, line 726) correctly calls `blockStateManager.appendToBlock(block.uuid, ...)`. An implementer reading only the acceptance criteria (not the code block) will use the wrong method, reproducing the cursor-sensitivity bug. The acceptance criterion should be updated to say `appendToBlock` to match the code.

### Minors (retained from prior round, still unaddressed)

- The Key Design Decisions Summary (bottom of plan) still reads "Written via `blockStateManager.insertTextAtCursor(blockUuid, " [[${term}]]")`" (point 1). Contradicts the patched body. Fix to `appendToBlock`.

- `TagSuggestionEngine.AUTO_APPLY_THRESHOLD = 0.95f` is dead code for LLM suggestions (which cap at 0.85f). Add a code comment or lower to 0.86f to document the intentional ceiling.

- `TagSuggestion.confidence: Float` has no `coerceIn` guard in the model itself; clamping is only enforced by convention in `LlmTagProvider.parseResponse`. Document in a comment on the model class.

- `OPENAI_MODEL = "gpt-4o-mini"` hardcoded in `OpenAiLlmFormatterProvider` creates coupling between voice and tag providers. Acceptable for MVP; note for follow-up.

- `TagSuggestionWorker` returns `Result.success()` on LLM failure, suppressing WorkManager retries for transient network errors. Intentional for MVP; note that `Result.retry()` should be used for `DomainError.NetworkError.*` if retries are added later.
