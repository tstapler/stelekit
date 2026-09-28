# Research: Build vs. Buy — Share Capture Whitespace Normalization

item_id: a3b1ba34-7ecf-456b-9b4a-ad25eb2de5d5

## Question
Should whitespace normalization for `CaptureActivity.buildShareText()` be hand-written or sourced from an existing library?

## What exists in this repo today

- No Gradle version catalog (`libs.versions.toml`) exists anywhere in the repo — dependencies are declared inline per-module (`androidApp/build.gradle.kts`, `kmp/build.gradle.kts`).
- `androidApp/build.gradle.kts` dependencies (lines 102-126): Arrow, AndroidX activity-compose/core-ktx/glance/car-app, Compose BOM. No string-utility library.
- `kmp/build.gradle.kts`: the only "apache" hits are `org.eclipse.jgit` (SSH) and `org.apache.pdfbox` (PDF text extraction) — unrelated to string manipulation. No Apache Commons Lang3/Text, no Guava, no ICU4J anywhere in the module.
- Repo-wide grep for `normalizeSpace|normalize.?whitespace|collapseWhitespace` and multi-space/whitespace regex patterns across `*.kt/*.py/*.go/*.ts` (excluding `build/`, `node_modules/`, `generated/`) returned **zero hits**. No prior whitespace-normalization code exists anywhere in the monorepo (`kmp/`, `androidApp/`, `stapler-scripts/`) to fork or reuse.

Relevant code:
- `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt:186-201` — `buildShareText()`, the correct insertion point per requirements (source-selection + fallback logic only, no normalization today).
- `CaptureViewModel.kt:54` — `save()` calls `.trim()` only (outer whitespace); per scope, this must remain untouched.

## Option 1: Existing OSS library (Commons Lang3 `StringUtils.normalizeSpace`, Guava `CharMatcher`, ICU4J)

**Pros:**
- Commons Lang3's `normalizeSpace` handles collapsing runs of whitespace (including some Unicode whitespace) into single spaces and trims ends, in one call, battle-tested across millions of deployments.
- ICU4J has the most correct Unicode whitespace/line-break handling (bidi-aware, full Unicode `White_Space` property table) — relevant if the app ever needs to handle exotic Unicode whitespace beyond NBSP.

**Cons:**
- **Zero of these libraries are currently on the classpath** in `androidApp` or `kmp` — this is a brand-new dependency, not free reuse.
- Commons Lang3 (~600KB) and ICU4J (~14MB, includes full Unicode locale/collation data) are heavyweight for an Android APK size budget, especially ICU4J. Guava is smaller-footprint but still a large general-purpose library to pull in for one string transform.
- None of them do *exactly* what's specced: `StringUtils.normalizeSpace` collapses **all** whitespace (including newlines) into single spaces — it does not have the "collapse spaces/tabs but preserve intentional single line breaks, collapse 3+ newlines to at most 2" behavior the requirements call for. Achieving the spec'd behavior still requires custom regex composition on top of the library, eroding the "adopt it and get correctness for free" argument.
- Adds a new transitive dependency surface (proguard/R8 rules, license tracking, version-bump maintenance) for functionality Kotlin's stdlib already covers.

**Verdict: Not recommended.**

## Option 2: SaaS/managed API

Not applicable — this is a pure, offline, client-side string transform with no external data dependency, executed synchronously on share-intent ingestion. No managed API adds value, and would introduce unacceptable latency/offline-failure risk into a capture flow. **Dismissed.**

## Option 3: LLM-generated bespoke implementation (hand-rolled Kotlin regex)

**Pros:**
- The spec decomposes into 3 independent, well-understood regex/replace operations, expressible with Kotlin stdlib alone:
  1. NBSP → space: `text.replace(' ', ' ')`
  2. Collapse runs of spaces/tabs (not newlines): `Regex("[ \\t]+").replace(text, " ")`
  3. Collapse 3+ newlines (with optional trailing whitespace/CR) to exactly 2: `Regex("\\n{3,}").replace(text, "\n\n")`
- Each step is a pure function, trivially unit-testable in isolation and in combination — matches the existing `CaptureShareTextTest` pattern already in the repo for `buildShareText()`.
- Zero new dependencies: no APK size impact, no proguard rules, no license/CVE surface to track, no version to bump.
- Matches this project's stated dependency ladder (CLAUDE.md: stdlib > native > already-installed dependency > new dependency) — stdlib fully covers this case, so the ladder terminates at step 1.
- Because the required behavior (preserve single newlines, collapse only 3+) is a custom composition no single library call replicates anyway, hand-rolling is not "reinventing a wheel" — it's the only way to get the exact spec'd behavior regardless of which option is chosen.

**Cons:**
- Unicode whitespace is famously easy to get subtly wrong if scope creeps beyond what's specified (e.g., zero-width spaces, other Unicode space separators like U+2000-200A, U+3000 ideographic space). The requirements explicitly scope this to NBSP only, spaces/tabs, and newlines — so this risk is bounded by the spec, not open-ended.
- No pre-existing battle-testing; correctness rests entirely on the unit tests added to `CaptureShareTextTest`.

**Verdict: Recommended.** The spec is narrow enough (3 documented transforms, explicitly NOT full Unicode whitespace handling) that stdlib regex is both sufficient and correct, and no library saves meaningful effort since none matches the spec's exact newline-preservation semantics out of the box.

## Option 4: Fork/adapt existing code elsewhere in the monorepo

Repo-wide search (`kmp/`, `androidApp/`, `stapler-scripts/`) for whitespace-normalization logic returned no matches. There is nothing to extract or reuse. **Not applicable.**

## Recommendation

Hand-write the normalization as 2-3 chained `Regex`/`String.replace` calls inside (or called from) `buildShareText()`, following the existing dependency ladder (stdlib first) and the requirement that no library on the classpath already provides this exact behavior. Add corresponding cases to `CaptureShareTextTest` covering: multi-space/tab collapse, NBSP normalization, 3+ newline collapse to 2, and preservation of single intentional line breaks.
