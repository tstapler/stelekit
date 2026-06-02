# Visit-Recency Ranking Patterns

## Research scope
How to add a visit-recency signal to SteleKit's existing BM25 + edit-recency + graph-boost
ranking formula, with minimal write amplification and correct KMP-safe scoring math.

---

## 1. Prior Art: Frecency Hybrid Models

### Firefox Places Frecency
The most mature open-source frecency implementation. Key design decisions:
- Stores `visit_count`, `last_visit_date`, and a computed `frecency` score in `moz_places`.
- Score for a single visit: `score_visit = weight_visit × e^(−λ × days_since_visit)`
  where `weight_visit` depends on visit type (typed URL = 2.0, link click = 1.0, etc.)
- Total frecency is computed from a **sample** of the last N visits (default 10), not all
  visits, to bound query cost.
- Decay is applied on **daily idle**: `new_frecency = old_frecency × 0.975` (≈ 30-day half-life).
  `use_count` entries with value < 0.1 (unused for 90 days) are pruned.
- The daily-decay model avoids recalculating full history on every access — only the
  stored score is updated.

**Relevance to SteleKit**: The daily-idle decay pattern is a good fit for a desktop/mobile
note app. However, SteleKit has no background daemon — decay must be computed on-the-fly
at query time or periodically on app open.

### Logseq (ClojureScript source)
Logseq tracks recent pages via a simple recency list (most-recently-visited pages stored in
app state), not a frecency score. No visit frequency weighting. The omnibar boosts pages
seen recently in the current session. This is simpler than Firefox's approach and confirms
that even a bare recency signal (without frequency) adds value in note-taking apps.

### fzf Discussion (junegunn/fzf #4543)
fzf's community discussion on frecency concluded: for personal tools, a **short half-life
(3–7 days)** with **no frequency component** performs better than full frecency because
users of personal tools revisit the same pages in bursts, not uniformly. A pure recency
signal with a 3-day half-life was preferred over frecency in user testing.

### Rekal / ClawMem (recent LLM memory tools, 2025)
Both use a hybrid formula:
`final_score = w_bm25 × bm25 + w_semantic × cosine + w_recency × exp(−ln2 × days / halflife)`
- Rekal default: BM25=0.4, semantic=0.4, recency=0.2, half-life=30 days.
- ClawMem: content-type-specific half-lives (short-form notes shorter half-life than
  long-form documents).

**Relevance**: The 0.2 weight on recency relative to BM25 is a useful calibration starting
point. Recency should not dominate relevance for non-recently-visited queries.

---

## 2. SQLite Storage: Efficient Visit History

### Option A — Append-only visit log (write-friendly, read-expensive)
```sql
CREATE TABLE page_visits (
    page_uuid TEXT NOT NULL,
    visited_at INTEGER NOT NULL  -- epoch ms
);
CREATE INDEX idx_page_visits ON page_visits(page_uuid, visited_at DESC);
```
- Insert on every navigation: O(1) write, no lock contention.
- Query: `SELECT MAX(visited_at) FROM page_visits WHERE page_uuid = ?` per result — N+1 problem.
- Pruning: periodic `DELETE FROM page_visits WHERE visited_at < ?`.

### Option B — Upserted summary row (read-friendly, write-amortised)
```sql
CREATE TABLE page_visits (
    page_uuid TEXT PRIMARY KEY,
    visit_count INTEGER NOT NULL DEFAULT 0,
    last_visited_at INTEGER NOT NULL,
    frecency_score REAL NOT NULL DEFAULT 0.0
);
```
- Insert or update on every navigation: `INSERT OR REPLACE` or `ON CONFLICT DO UPDATE`.
- Query: single join to get `last_visited_at` and `frecency_score` for all results at once.
- Frecency score updated on write using current formula — avoids recomputing at read time.
- Write amplification: 1 upsert per navigation event. At typical note-taking pace
  (5–20 navigations/day), this is negligible.

**Recommendation**: Option B (upserted summary) for SteleKit. It avoids the N+1 read-time
lookup problem and keeps the schema simple. Frecency score can be pre-computed on write.

### Write amplification assessment
For Option B, each navigation triggers one `INSERT OR REPLACE` on a 1-row-per-page table.
With `DatabaseWriteActor` serialising writes, this adds at most ~1 ms per navigation event
on a warm SQLite connection. No debouncing needed at the DB level, but the ViewModel can
debounce rapid back-forward navigation (e.g. 500 ms) to avoid redundant writes.

---

## 3. Decay Functions: Which Half-Life?

For personal note-taking, the requirements are:
1. Pages visited today should rank clearly above pages not visited in a week.
2. The signal should not completely dominate for old but highly relevant content.
3. Half-life should be shorter than edit-recency (currently 14 days in SteleKit).

**Recommendation: 3-day half-life, exponential decay.**

Formula already used in `SqlDelightSearchRepository.recencyMultiplier`:
```kotlin
1.0 + exp(-daysSince / RECENCY_HALFLIFE_DAYS)
```
This produces a multiplier between ~2.0 (today) and ~1.0 (old). A visit-recency signal
should use the same shape with `VISIT_HALFLIFE_DAYS = 3.0`:
```kotlin
fun visitRecencyMultiplier(lastVisitedMs: Long, nowMs: Long): Double {
    if (lastVisitedMs <= 0L) return 1.0
    val daysSince = (nowMs - lastVisitedMs).coerceAtLeast(0L) / 86_400_000.0
    return 1.0 + exp(-daysSince / VISIT_HALFLIFE_DAYS)
}
```
Visited today: 1 + exp(0) = 2.0. Visited 3 days ago: 1 + exp(-1) ≈ 1.37. Visited 2 weeks
ago: 1 + exp(-4.67) ≈ 1.009 (nearly no boost). This gives a sensible gradient.

### Exponential vs linear vs step
- **Exponential**: smooth, mathematically clean, matches real memory decay curves. Best.
- **Linear**: simpler but creates a hard cutoff at max_days; awkward UX ("why did this
  page suddenly drop?").
- **Step function** (e.g. visited in last hour = 3×, last day = 2×, last week = 1.5×):
  Easy to understand but creates discontinuities. Acceptable for MVP.
- **Recommendation**: exponential (already consistent with edit-recency in codebase).

---

## 4. Blending Visit-Recency with BM25 Without Swamping Relevance

Current formula (simplified):
```
score = abs(bm25) × PAGE_BOOST? × editRecencyMultiplier × graphMultiplier
```

Proposed addition:
```
score = abs(bm25) × PAGE_BOOST? × editRecencyMultiplier × graphMultiplier × visitRecencyMultiplier
```

Risk: multiplying three multipliers together can compound exponentially. A page visited
today (×2.0), edited today (×2.0), and a graph neighbour (×3.0) gets a ×12.0 boost over
an unvisited, old, non-neighbour page — which may be appropriate but should be validated.

Alternative — additive blending:
```
score = w1 × abs(bm25) + w2 × visitRecencyMultiplier + w3 × graphBonus
```
Additive blending provides better score normalisation but requires calibrating weights.
The existing multiplicative approach is simpler and consistent with the codebase; adding
one more multiplicative factor is the lowest-risk change.

**Normalisation guard**: if `abs(bm25)` is very small (e.g. a weak match with score 0.1),
multiplying by ×12.0 still produces only 1.2 — far below a good BM25 match at 3.0 × 1.0 = 3.0.
The multiplicative approach thus naturally self-normalises: recency boosts are only significant
for reasonably relevant BM25 results.

---

## 5. Implementation Plan

1. Add `page_visits` table (Option B schema) to `SteleDatabase.sq`.
2. Add `insertOrReplacePageVisit` and `selectPageVisitByUuid` queries.
3. Add `RestrictedDatabaseQueries` stub for the upsert (gated by `@DirectSqlWrite`).
4. Add `recordPageVisit(pageUuid: String)` to `SearchRepository` interface or a new
   `VisitRepository` interface.
5. Call `recordPageVisit` from `StelekitViewModel.navigateTo()` — after navigation confirms
   success, fire-and-forget via `viewModelScope.launch`.
6. In `buildRankedList`, join visit data: query `selectPageVisitByUuids(uuids)` in bulk
   (one query for all result UUIDs) to avoid N+1. Cache in a `Map<String, Long>` for the
   ranking pass.
7. Apply `visitRecencyMultiplier` with `VISIT_HALFLIFE_DAYS = 3.0` to both page and block
   hits (blocks inherit their page's visit timestamp).

### KMP constraint
`kotlin.math.exp` is available in `kotlin.math` across all KMP targets (JVM, Android, iOS,
WASM). No platform-specific code required for the decay formula.
