# Research: Architecture — integration point for whitespace normalization

Backlog item: `a3b1ba34-7ecf-456b-9b4a-ad25eb2de5d5`
Scope: where to hook the normalization pass so it satisfies AC6 (share-only),
is unit-testable without Robolectric, and doesn't duplicate logic across
`onCreate`/`onNewIntent`.

## Facts established by reading the code

- `CaptureActivity.buildShareText(clipText, extraText, subject)`
  (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201`)
  is an `internal` **companion-object** function, pure `String? × String? ×
  String? -> String`. It takes no `Intent`/`Context`.
- `CaptureActivity.parseShareIntent(intent: Intent)`
  (`CaptureActivity.kt:127-144`) is a **private instance method**. It reads
  `intent.clipData?.getItemAt(0)?.coerceToText(this)` — `coerceToText` needs
  an Android `Context` (`this`, the Activity) — so `parseShareIntent` cannot
  be unit-tested on the plain JVM; it needs Robolectric (or an
  instrumented/`androidTest` run).
- `CaptureShareTextTest`
  (`androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`)
  proves this split empirically: all 12 existing cases call
  `CaptureActivity.buildShareText(...)` directly — never `parseShareIntent`
  — and the test class has no `@RunWith(RobolectricTestRunner::class)` and no
  Robolectric imports. `buildShareText`'s pure-function, companion-object
  shape is *why* it is reachable from a plain `junit:junit:4.13.2` test today.
- `CaptureActivity.onCreate` (`:79-86`) and `onNewIntent` (`:115-124`) both
  call `parseShareIntent(intent)` once each, then branch on
  `shareContent.imageLocalPath` before calling either
  `viewModel.initializeText("[image: ...]\n${shareContent.text}".trim())` or
  `viewModel.initializeText(shareContent.text)`. Both call sites are
  already funneled through the single `parseShareIntent` → `buildShareText`
  chain — there is no duplication to fix at the `CaptureActivity` level
  regardless of where normalization is hooked in.
- `CaptureViewModel.updateText(text)` (`CaptureViewModel.kt:42-44`) is a
  distinct method from `initializeText(text)` (`:47-51`) — `updateText` sets
  `_captureText.value = text` directly and is the only method wired to the
  `OutlinedTextField`'s `onValueChange = viewModel::updateText`
  (`CaptureActivity.kt:289`). `initializeText` is only ever called with
  `shareContent.text` (share-sourced), guarded by
  `_captureText.value.isEmpty()` for `singleTop` re-launch idempotency.
- Robolectric (`org.robolectric:robolectric:4.13`) and
  `androidx.test:core:1.6.1` **are** on `androidApp`'s test classpath
  (`androidApp/build.gradle.kts:117,119`), so a `CaptureViewModelTest`
  constructing a real `CaptureViewModel(application)` via
  `ApplicationProvider.getApplicationContext()` is feasible if needed — this
  is not a blocker for any of the three options below, it only affects how
  cheap/fast the AC6 proof test is.

## Options compared

### Option A — inline the normalization steps inside `buildShareText()`

Splice the regex/replace steps directly into `buildShareText()`'s body,
applied to the value the existing `when` block currently returns.

- (a) Share-only: yes — `buildShareText` is never called on manually-typed
  text.
- (b) Pure/testable: yes — still a companion-object `String -> String`
  function, still reachable from `CaptureShareTextTest` with zero new test
  infra.
- (c) No duplication: yes — one call site (`parseShareIntent`) already
  covers both `onCreate`/`onNewIntent`.
- Downside: entangles two concerns in one function body — "which source
  wins + dedup subject/body" (existing, requirements call this out as logic
  that must be *preserved*, not rewritten) and "collapse whitespace runs"
  (new). Every future change to source-priority logic has to be read
  alongside unrelated regex steps, and vice versa. Also makes the "which of
  these 4 AC1-AC4 behaviors regressed" question harder to answer from a
  diff/stack trace, since there's only one function to blame.

### Option B — separate pure function, called from inside `buildShareText()` (recommended)

Add a new `internal fun normalizeShareWhitespace(text: String): String` to
the `CaptureActivity` companion object (pure stdlib `Regex`/`replace`, per
`research/stack.md`'s survey — no new dependency). Change `buildShareText()`
only at its return: wrap the existing `when { ... }` result,
e.g. `return normalizeShareWhitespace(when { ... })` (exact expression
shape is an implementation-phase detail; the point is the existing branch
logic is untouched, only its output is piped through one more pure
function).

- (a) Share-only: yes, for the same structural reason as Option A —
  `buildShareText` (and everything downstream of it, including the new
  normalizer) is only ever reached from `parseShareIntent`. Manually-typed
  text goes `OutlinedTextField` → `updateText()` → `_captureText.value =
  text` directly and never touches `buildShareText` or
  `normalizeShareWhitespace` at all. This is a structural guarantee, not a
  discipline one — there is no code path by which manual text could reach
  the normalizer without a future author deliberately routing it there.
- (b) Pure/testable: yes, and *more* granularly than Option A —
  `normalizeShareWhitespace` can be tested in total isolation from the
  source-priority/dedup branching (clean regex-behavior tests: AC1 space/tab
  collapsing, AC2 NBSP, AC3 blank-line collapsing incl. CRLF, AC4
  single-newline preservation), while `buildShareText` keeps being tested
  for source priority as it is today. AC7 additionally asks for "one
  combined realistic browser-share payload" test — that one exercises the
  *composition* of both functions through `buildShareText()`'s public
  surface, which only works if normalization is reachable through
  `buildShareText()`'s return value (true for A and B, **not** true for a
  variant of Option C where normalization is applied outside
  `buildShareText()` — see below).
- (c) No duplication: yes, same reasoning as Option A.
- Matches the requirements doc's own framing almost verbatim: "normalization
  is an additional, **separable** transform, not a rewrite of that logic"
  (`requirements.md:52-53`) — "separable" argues for a distinct function,
  not steps interleaved into the existing `when` block.
- Cost: one new small function + one line changed in `buildShareText`.
  Marginally larger diff than Option A, but the separability is exactly what
  the requirements ask for, and it keeps the two future failure modes
  (broken source-priority vs. broken whitespace collapsing) independently
  diagnosable and independently testable.

### Option C — apply normalization in `parseShareIntent()`, wrapping the call to `buildShareText()`

`val text = normalizeShareWhitespace(buildShareText(clipText, extraText, subject))`
inside `parseShareIntent`, leaving `buildShareText()` itself untouched.

- (a) Share-only: yes, same structural reasoning.
- (b) Pure/testable: the normalizer function itself is still a pure
  companion-object function and can be unit-tested directly. **But** this
  breaks the AC7 "combined realistic browser-share payload" test as written
  against `CaptureShareTextTest`'s existing pattern: all 12 current tests
  call `build(clip, extra, subject) = CaptureActivity.buildShareText(...)`
  directly (`CaptureShareTextTest.kt:12-13`), which is the only entry point
  reachable without Robolectric. Under Option C, `buildShareText()` returns
  *unnormalized* text, so a combined test written the same way as the
  existing 12 would not exercise normalization at all — proving AC1-AC4
  through that helper would require either (i) testing
  `normalizeShareWhitespace` in isolation and never proving it composes with
  `buildShareText`'s dedup/priority output, or (ii) reaching for
  `parseShareIntent` itself, which requires Robolectric/`Intent`/`Context`
  and is a heavier, slower test than anything else in this file today.
- (c) No duplication: yes, same reasoning.
- Rejected: it's the only option of the three that makes AC7's "combined...
  payload" test either impossible to write in the established
  `CaptureShareTextTest` style, or forces a strictly more expensive test
  (Robolectric) than the problem needs. It also relocates a concern that the
  requirements pin to "the result of `buildShareText()` (or equivalently
  inside it)" (`requirements.md:54`) to a third location the requirements
  don't mention.

### Option D — hook into `CaptureViewModel.initializeText()`

Normalize inside `initializeText(text)` before assigning
`_captureText.value = text`, leaving `updateText(text)` untouched.

- (a) Share-only: **satisfied only by convention, not by structure.**
  `initializeText` and `updateText` are already separate methods today, so
  routing normalization through `initializeText` alone does technically
  keep it off the manual-typing path *as the code exists right now*. But
  the guarantee now depends on every future caller of `initializeText`
  continuing to only ever pass share-sourced text — e.g. a hypothetical
  "restore last draft" or "prefill from clipboard on cold start" feature
  that reused `initializeText` for its idempotent-first-write behavior would
  silently start getting whitespace-normalized text with no share intent
  involved, and nothing in the type system or the function's name
  (`initializeText`, not `initializeShareText`) would flag that as wrong.
  Options A/B/C instead make the guarantee structural: normalization is
  physically unreachable except via `parseShareIntent`.
- (b) Pure/testable: **weaker.** `initializeText` is an instance method on
  `CaptureViewModel`, which extends `AndroidViewModel(app)` — constructing
  one for a test needs at minimum `ApplicationProvider.getApplicationContext()`
  (Robolectric) or a mocked `Application`, i.e. heavier than the
  `CaptureActivity.buildShareText(...)`-style plain-JVM call every existing
  `CaptureShareTextTest` case uses. This directly conflicts with the
  research question's testability requirement ("testable via a pure
  function... unit-testable without Robolectric/Android context").
- (c) No duplication: yes — same single call site reasoning applies equally
  here since both `onCreate`/`onNewIntent` call `initializeText` the same
  way today.
- Also conflicts with the requirements doc's explicit placement guidance:
  "Normalization must be applied to the *result* of `buildShareText()` (or
  equivalently inside it, ... not scattered across call sites)"
  (`requirements.md:54-56`). `initializeText` is downstream of
  `buildShareText`'s result, not `buildShareText`'s result itself, and
  mixes a ViewModel-lifecycle concern (idempotent first-write for
  `singleTop` re-launch) with a text-transform concern — two reasons to
  change the same function, which `CaptureViewModel.kt`'s existing
  single-purpose methods (`updateText`, `initializeText`, `save`) don't do
  elsewhere in this class.
- Rejected on both testability and requirements-fit grounds, even though it
  would technically satisfy AC6.

## Recommendation

**Option B**: add `internal fun normalizeShareWhitespace(text: String):
String` to `CaptureActivity`'s companion object
(`CaptureActivity.kt:174-202`), and change `buildShareText()`'s `return`
to pipe its existing `when` result through it. `parseShareIntent()`,
`onCreate`, `onNewIntent`, and `CaptureViewModel` all stay untouched.

Rationale, in priority order:

1. **AC6 is structural, not discipline-based.** Manually-typed text's only
   path is `OutlinedTextField.onValueChange = viewModel::updateText` →
   `_captureText.value = text` (`CaptureActivity.kt:289`,
   `CaptureViewModel.kt:42-44`) — a path that never calls `buildShareText`
   or the new normalizer. There is no future refactor of `updateText` that
   could accidentally start normalizing manual text without someone
   deliberately wiring it in, unlike Option D where that guarantee rests on
   every future caller of `initializeText` happening to only pass
   share-sourced text.
2. **Matches the requirements' own placement language** ("applied to the
   result of `buildShareText()`... or equivalently inside it... not
   scattered across call sites", `requirements.md:54-56`) — Option B is the
   literal "equivalently inside it" case, while keeping the new logic in
   its own function rather than interleaved with the existing
   priority/dedup branches (the requirements' "separable transform" language,
   `requirements.md:52-53`).
3. **Testability matches the file's own established pattern exactly.**
   `CaptureShareTextTest`'s `build()` helper
   (`CaptureShareTextTest.kt:12-13`) calls `buildShareText` directly today
   with zero Robolectric. Because normalization lives inside
   `buildShareText()`'s return path, every one of AC1-AC4 and the AC7
   combined-payload test can be written the same way, in the same test
   file, with the same zero-Robolectric cost — and `normalizeShareWhitespace`
   can *additionally* be unit-tested standalone (companion-object, pure) for
   the regex-collapsing edge cases (AC1-AC4) independent of source-priority
   noise, giving two independent, cheap test surfaces instead of one.
4. **AC5 (no regression) falls out for free.** Since all 12 existing test
   inputs contain no internal whitespace runs, NBSP, or excess blank lines,
   `normalizeShareWhitespace` is a no-op on them by construction — the
   existing assertions don't need to change.

### Composition with `CaptureViewModel.save()`'s existing `.trim()`

The two transforms are complementary, not redundant, and apply at different
times for different reasons:

- `normalizeShareWhitespace` runs **once, at ingestion time**, inside
  `buildShareText()` — i.e. the moment `parseShareIntent` resolves a share
  intent, before the text ever reaches the ViewModel. It only touches
  *internal* whitespace: runs of 2+ spaces/tabs, NBSP, and excess blank-line
  sequences (AC1-AC3). It does not need to strip leading/trailing
  whitespace of the whole string — a regex collapsing `\n{3,}` (or
  whitespace-only-line runs) to `\n\n` can still leave, e.g., a single
  leading space if the original body started with one.
- `.trim()` in `CaptureViewModel.save()` (`CaptureViewModel.kt:54`) runs
  **every time the user saves**, regardless of whether the text originated
  from a share intent, manual typing, or (most commonly) a share-sourced
  string the user then hand-edited in the `OutlinedTextField` between parse
  and save. It only strips *leading/trailing* whitespace of whatever is in
  `_captureText.value` at save time — it does nothing for internal runs,
  which is the gap this whole backlog item exists to close.
- Net effect for a share-sourced note: `normalizeShareWhitespace` cleans
  internal noise once at parse time → user optionally edits via
  `updateText` (untouched, no re-normalization) → `save()`'s `.trim()`
  does its usual outer cleanup on whatever is in the field, same as it does
  today for purely manual text. Neither transform duplicates the other's
  job (internal collapsing vs. outer stripping), and `save()` needs no
  changes at all.

## Key files referenced

- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:79-86`
  (`onCreate`), `:115-124` (`onNewIntent`), `:127-144` (`parseShareIntent`),
  `:174-202` (companion object / `buildShareText`), `:287-296`
  (`OutlinedTextField` wiring to `updateText`)
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:42-44`
  (`updateText`), `:47-51` (`initializeText`), `:53-69` (`save`, incl. the
  `.trim()` at `:54`)
- `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt`
  (existing 12-case pure-function test pattern, no Robolectric)
- `androidApp/build.gradle.kts:115-120` (test dependencies: JUnit4,
  Robolectric, androidx.test — Robolectric is available if a
  `CaptureViewModelTest` needs it for AC6's proof, but the normalization
  logic itself does not)
- `project_plans/android-share-capture-whitespace/requirements.md:52-56`
  (placement constraint), `:36-39` (AC6 text)
- `project_plans/android-share-capture-whitespace/research/stack.md`
  (regex/stdlib approach for the normalizer's internals — not re-derived
  here)
