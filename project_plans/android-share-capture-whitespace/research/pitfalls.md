# Research: Pitfalls in regex-based whitespace normalization (Kotlin/JVM)

Scope: `CaptureActivity.buildShareText()` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201`),
called from `parseShareIntent()` (line 134) and consumed only via `viewModel.initializeText(...)`
(lines 82, 84, 120, 122) — never via `viewModel.updateText(...)` (`CaptureViewModel.kt:42`, wired to
the `OutlinedTextField.onValueChange` at `CaptureActivity.kt:289`). This existing call-graph split is
itself the mechanism that should satisfy AC6: if the normalization pass lives inside/around
`buildShareText()` rather than in `CaptureViewModel`, manually-typed text (which only ever reaches
`updateText()`) structurally cannot pass through it — no flag or scope parameter needed. All claims
below marked VERIFIED were run against JDK 25 (`javac`/`java`) and Kotlin script (`kotlinc -script`)
in this sandbox; scratch files are in `/tmp/claude-1000/.../scratchpad/{RegexCheck.kts,RedosCheck*.kts,RawStringCheck2.kts}`.

## 1. Regex pitfalls

**Catastrophic backtracking — the AC-specified patterns themselves are safe.**
VERIFIED: `Regex("\\n{3,}")` and `Regex("[ \\t]{2,}")` run in ~2ms against a 50,000-char adversarial
non-matching input (a single `a`, 50k spaces, `b` — worst case for a greedy bounded quantifier). These
are single quantifiers over a single character class with no nesting, so they're inherently linear —
not a ReDoS risk as written.

The risk shows up if an implementer "gets clever" and merges concerns into one pattern with a
**nested** quantifier — e.g. trying to handle "whitespace-only line, repeated" and "blank-line run"
in one regex like `(\s*\n){2,}` or `(\s+)+`. `\s` overlaps with the literal `\n` the outer group also
consumes, which is the classic ReDoS shape (ambiguous partitioning when the overall match ultimately
fails). My own attempt to reproduce a blowup with `(\s*\n){2,}`/`^(\s*\n)+$` on adversarial input up to
n=35 did *not* show exponential growth at this scale — Java's backtracking engine handled it without
runaway cost in this specific case — but the shape is still a known anti-pattern (nested quantifier +
overlapping alternatives) and the negative result here doesn't prove safety at larger n or different
character mixes. **Recommendation for the plan/implementation:** keep each transform as its own
simple, sequential `replace()` call (NBSP→space, then `[ \t]{2,}`→space, then strip whitespace-only
lines, then `\n{3,}`→`\n\n`) rather than one combined mega-regex. This is both provably linear (per
the above) and far easier to unit-test in isolation.

**Off-by-one on "3+ newlines → at most one blank line."**
VERIFIED: `"a\n\n\nb".replace(Regex("\\n{3,}"), "\n\n")` → `a\n\nb` (2 newlines = exactly one blank
line), and `"a\n\nb"` (already 2 newlines) is left untouched by the same regex. So `\n{3,}` → `\n\n`
is the correct pair — the requirements.md wording ("3+ consecutive newlines ... collapse to at most
one blank line (2 consecutive newlines)", requirements.md:29-31 / AC3 at :75) matches this exactly.
The off-by-one trap is writing `\n{2,}` (which would also flatten a legitimate single blank line down
to zero, violating AC4) or replacing with `"\n"` instead of `"\n\n"` (same failure).

**Order of operations: NBSP normalization must run *before* space-run collapsing, not after or
interleaved.**
VERIFIED: Java/Kotlin `\s` does **not** match NBSP (U+00A0) by default — `Regex("a\\sb").containsMatchIn("a b")` is `false` (only true with `Pattern.UNICODE_CHARACTER_CLASS`, which Kotlin's `Regex` doesn't set unless you pass `(?U)` explicitly). Consequently:
- A mixed run like `"space NBSP space"` run through `\s{2,}`/`[ \t]{2,}` **first** does not collapse
  at all, because the NBSP in the middle breaks the run into two separate single-`\s` matches
  (neither ≥2 chars) — VERIFIED, output was unchanged (`a␣ ␣b`, still 3 tokens wide).
  Only after ` ` is replaced with a plain space does the same `[ \t]{2,}`/`\s{2,}` pass correctly
  collapse the now-contiguous run to one space.
- This directly matches AC2's own wording ("normalizes to a regular space ... **then** is subject to
  the same collapsing as AC1 if repeated", requirements.md:27) — the ordering isn't just a style
  choice, it's required for correctness against exactly this "space-NBSP-space" DOM artifact shape.

**CRLF handling — the naive fix is also the correct one, but must run before blank-line collapsing.**
VERIFIED: normalizing `\r\n` → `\n` first (`text.replace("\r\n", "\n")`) then applying the `\n{3,}`
blank-line logic produces the correct result on `"a\r\n\r\n\r\nb"` (3 CRLF pairs, i.e. 2 blank lines)
→ collapses to exactly one blank line, same as the LF-only case. The trap is running blank-line
collapsing *before* CRLF normalization, or using a pattern like `\n{3,}` directly against
un-normalized CRLF text: `\r\n\r\n\r\n` contains only 3 raw `\n` characters interleaved with `\r`, so
`\n{3,}` still numerically matches by luck, but a lone `\r` (old Mac line ending, or a `\r` not
followed by `\n`) is invisible to any `\n`-based pattern and will leak into the output un-normalized.
Since the requirement (`requirements.md:59-60`) only asks that `\r\n` be treated equivalently to `\n`,
normalize CRLF→LF as the very first step, before anything else touches whitespace. **Open question
for the plan (not resolved by requirements.md):** does the *output* keep `\r\n` for preserved single
line breaks, or does the whole pipeline normalize to bare `\n`? Requirements.md doesn't say explicitly.
Recommend the plan state this explicitly and the test for AC7's CRLF case assert on the actual chosen
output convention, not just "collapses correctly" — otherwise the CRLF test can pass while silently
picking a convention nobody decided on.

**Multiline flag: required for any per-line `^`/`$` anchor, and easy to silently omit.**
VERIFIED: `Regex("^[ \\t]+$")` (no `RegexOption.MULTILINE`) run via `.replace()` against
`"a\n   \nb"` leaves the whitespace-only middle line **completely untouched** — `^`/`$` anchor to the
whole-input start/end only, not each line. The identical pattern with `RegexOption.MULTILINE` (Kotlin)
/ `(?m)` correctly strips the whitespace-only line, producing `"a\n\nb"`. Any regex meant to detect
"a line containing only spaces/tabs" (needed for the "whitespace-only blank lines" half of AC3) that
uses `^`/`$` line anchors **must** pass `RegexOption.MULTILINE`, or use a non-anchored alternative like
`Regex("\\n[ \\t]+\\n")` matched repeatedly / replaced with `\n\n`. This is the single most likely
silent-failure pitfall in this task — the code compiles, single-line test inputs pass, and the bug only
surfaces on the specific whitespace-only-line AC7 test case.

**Sequencing note:** whitespace-only-line stripping must run *before* the `\n{3,}`→`\n\n` collapse,
not after — VERIFIED `"a\n \nb"` (one whitespace-only line, i.e. only 2 raw newlines) is not touched by
`\n{3,}` alone (only 2 `\n` present, pattern needs 3+). It must first become `"a\n\nb"` via the
whitespace-only-line strip, and *that* correctly represents "one blank line" — no further collapsing
needed in this particular case, but for chains of multiple whitespace-only lines the strip-then-collapse
order matters for the same reason NBSP-then-collapse does.

## 2. Kotlin-specific gotchas

**`Regex.replace()` semantics** — straightforward but worth stating for the plan: `Regex.replace(input, replacement)` replaces *all* non-overlapping matches (like `String.replaceAll` in Java, not `replaceFirst`). No special surprises found here; the risk is entirely in the pattern text and ordering above, not the API.

**`String.trim()` (Kotlin) vs `java.lang.String.trim()` — these are NOT the same behavior, despite
sharing a name.** This is the least obvious finding and directly relevant to `CaptureViewModel.save()`'s
existing `.trim()` call (`CaptureViewModel.kt:54`):
- VERIFIED (Java): `"  hello  ".trim()` (java.lang.String) → `"  hello  "` — NBSP is
  **not** stripped (Java's `String.trim()` only strips chars `<= U+0020`, and NBSP is U+00A0, above
  that cutoff... but *also* Java's rule is specifically char code `<= ' '`, so anything above `' '`
  including NBSP survives).
- VERIFIED (Kotlin): `"  hello  ".trim()` (Kotlin's `CharSequence.trim()` extension) →
  `"hello"` — NBSP **is** stripped. Root cause: Kotlin's `Char.isWhitespace()` = `Character.isWhitespace(char) OR Character.isSpaceChar(char)`. Java's `Character.isWhitespace(NBSP)` is `false`
  (Java explicitly special-cases NBSP as *not* whitespace), but `Character.isSpaceChar(NBSP)` is `true`
  (NBSP is Unicode category Zs, "space separator"), and Kotlin ORs the two — so Kotlin's `trim()`
  covers Unicode space separators that Java's `String.trim()` does not.
- **Practical implication:** `CaptureViewModel.save()`'s existing `.trim()` (line 54) *already*
  strips **leading/trailing** NBSP today, before this feature is even built. That's fine and doesn't
  need duplicating — but it means a naive first test of "does NBSP get normalized" using an
  NBSP-only-at-the-edges fixture will pass even with zero new code, because the pre-existing outer
  `.trim()` masks it. AC2/AC7 tests must place the NBSP **internally** (mid-string, surrounded by
  non-whitespace) to actually exercise the new normalization pass, not at the string edges.
- Also note: `String.trim(*chars: Char)` (the vararg overload) does **not** get this Unicode-aware
  behavior for free — VERIFIED `"...".trim(' ')` only strips the literal space char passed in, leaving
  NBSP untouched even at the edges. If the implementation uses `.trim(' ', '\t')` anywhere instead of
  the no-arg `.trim()`, NBSP handling silently regresses.

**`\s` in Kotlin/Java regex does not include NBSP** — already covered under Regex pitfalls above, but
worth restating as the Kotlin-specific gotcha it is: developers reasonably expect `\s` to mean "any
whitespace" and are surprised NBSP is excluded. This is exactly why AC2 mandates the NBSP→space
normalization step as a *separate, prior* pass rather than trying to fold NBSP into a single `\s`-based
regex.

**Triple-quoted (`"""..."""`) raw strings do not process escape sequences.** Kotlin's raw string
literals do not interpret `\n`, `\t`, ` `, etc. — they're literal text. This is standard,
well-documented Kotlin behavior (not something I could cleanly disprove interactively in this sandbox
because my own tooling kept auto-interpreting the escape before it reached the Kotlin source — which
is itself a small illustration of how easy these characters are to lose in transit). Practical
implication for the new tests: build NBSP/CRLF/whitespace-only-line fixtures using normal
double-quoted strings with ` `/`\r\n` escapes, or `${' '}` template interpolation inside a
raw string — never assume a triple-quoted block will honor ` `.

## 3. Testing pitfalls

**IDE/editor auto-trim-on-save can silently corrupt the *input* side of a whitespace test.** Most
IDEs (including IntelliJ/Android Studio's default "Strip trailing spaces on Save") will strip trailing
whitespace from source lines, including trailing whitespace *inside* a string literal if it happens to
sit at the physical end of a source line. A test author who writes something like:
```kotlin
val input = "line one   \nline two"   // trailing spaces intended before \n
```
is safe here because the trailing spaces are followed by `\n` mid-line, not at end-of-source-line — but
a whitespace-only "blank line" fixture written across multiple physical lines, e.g. inside a
triple-quoted block with a line that's meant to contain just a space, is exactly the shape editors
strip on save, silently turning the "whitespace-only line" test case into a genuinely-empty-line test
case that no longer exercises the code path AC3/AC7 asks for. **Recommendation:** construct these
fixtures programmatically (`"line one\n" + " " + "\nline two"`, or `"line one\n${" "}\nline two"`) or
with explicit ` `/`\t`/` ` escapes on a single physical source line, never as literal
whitespace sitting alone on its own line in the test source.

**Invisible-character diffs make `assertEquals` failures hard to read.** A failing assertion between
two strings that differ only by an NBSP vs regular space, or a trailing space vs none, renders
identically (or near-identically) in most JUnit failure output/terminal diffs — the two strings *look*
the same. Recommend either (a) an assertion message that includes a visualized form
(`actual.replace(' ', '·').replace(" ", "␣")`), or (b) comparing `.map { it.code }` /
`.toCharArray().joinToString(",")` on mismatch, so a future debugging session isn't spent visually
squinting at two identical-looking strings.

**CRLF test cases need the output convention decided first (see Regex section above)** — otherwise the
test itself has to guess what "correct" means, and a passing test doesn't actually pin down behavior
anyone agreed to.

## 4. Regression risk against the 12 existing `CaptureShareTextTest` cases

Read `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureShareTextTest.kt` in full (78 lines, 12
`@Test` methods). Checked every case's **winning/output** string (not blank/losing inputs, which never
reach the normalization pass) for multi-space or multi-newline content:

| Test | Winning output | Multi-space/multi-newline in output? |
|---|---|---|
| `url only in EXTRA_TEXT...` | `"https://example.com"` | No |
| `url in clipData preferred...` | `"https://clip.com"` | No |
| `empty clipData does not eat fallback` | `"https://example.com"` | No |
| `blank clipData does not eat fallback` | `"https://example.com"` | No (clipData `"   "` is the *losing*, blank-fallback-triggering input — never appears in output) |
| `subject and url combined with newline` | `"Example Page\nhttps://example.com"` | One single `\n` only — this is the AC4 case, must NOT collapse |
| `subject and clipData url combined` | `"My Page\nhttps://clip.com"` | Same — single `\n` |
| `subject only when no text fields` | `"Page Title"` | No |
| `all null returns empty string` | `""` | No |
| `subject equals body text` | `"https://example.com"` | No |
| `non-ACTION_SEND action...` | `"hello world"` | Single regular space, already normalized-looking — safe either way |
| `EXTRA_TEXT used as fallback` | `"Some shared text"` | Single spaces only |
| `subject falls back when both blank` | `"Just a title"` | No (extra `"  "` is a *losing*, blank input — never in output) |

**Finding: none of the 12 existing cases have multi-space or multi-newline content in their winning
output** — the only strings with internal whitespace runs in the fixtures (`clipData = "   "`, `extra =
"  "`) are deliberately-blank *losing* inputs used to test the fallback chain, and per
`buildShareText()`'s `takeIf { it.isNotBlank() }` guard (`CaptureActivity.kt:192-193`) they never reach
the output — so a new normalization pass, wherever it's inserted, cannot alter any of these 12
expected values as long as it's applied only to the already-selected winning text (which is what
requirements.md:54 specifies). **AC5 should not be at risk from these 12 cases by construction.**

The two cases with `\n` in the output (`subject and url combined...`, `subject and clipData url
combined`) are the ones that most directly exercise AC4 ("single line break preserved") — they are
implicitly a regression tripwire: if a new blank-line-collapse regex is written wrong (e.g. `\n{1,}`
instead of `\n{3,}`, or a whitespace-only-line strip that's too aggressive and treats a bare `\n` as
"blank"), these two pre-existing tests will start failing even though they predate this feature. That
makes them a useful implicit safety net, but the plan/implementation should treat them as first-class
regression cases to explicitly re-run, not just "the other 10."

**AC8 cross-check (why "passing locally" isn't sufficient proof today):** VERIFIED —
`.github/workflows/ci.yml:89`'s `android` job runs `./gradlew :kmp:testDebugUnitTest
:kmp:recordRoborazziDebug :androidApp:assembleDebug`, which never invokes
`:androidApp:testDebugUnitTest`. `CaptureShareTextTest` therefore does not run in CI today, and
`CaptureViewModelTest` doesn't exist yet (confirmed: `androidApp/src/test/kotlin/dev/stapler/stelekit/`
currently contains only `CaptureShareTextTest.kt` and an `auto/` dir). This means any regression
introduced by the whitespace-normalization change — including a break in one of the 12 existing cases —
would currently be invisible to CI and only caught by whoever happens to run
`:androidApp:testDebugUnitTest` locally. **Implication for planning:** AC8 (wiring
`:androidApp:testDebugUnitTest` into the `android` CI job) is a hard prerequisite for AC5 actually
being *enforced*, not just *true* — sequence the plan so the CI wiring change lands together with (or
before) the normalization logic, otherwise the "no regression" guarantee is only as strong as a
developer's memory to run the right Gradle task by hand.
