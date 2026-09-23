# Research: Architecture — llm-service

Scope: how to structure the unified LLM provider abstraction and the approval-gated
edit workflow inside SteleKit's existing layered architecture (`CLAUDE.md`
conventions: Arrow `Either`, `DatabaseWriteActor`/`@DirectSqlWrite` write-gating,
dispatcher matrix, bounded reads, `rememberCoroutineScope` rules). This document
proposes structure only — no code changes.

## Summary of existing code reviewed

| File | Role today |
|---|---|
| `voice/LlmFormatterProvider.kt` | `fun interface LlmFormatterProvider { suspend fun format(transcript, systemPrompt): LlmResult }` — the low-level capability contract. `NoOpLlmFormatterProvider` is the null-object fallback. |
| `voice/ClaudeLlmFormatterProvider.kt`, `voice/OpenAiLlmFormatterProvider.kt` | Remote HTTP implementations, each with a `CircuitBreaker`, a `withDefaults(apiKey)` factory, and shared helpers in `voice/LlmProviderSupport.kt`. |
| `androidMain/.../voice/MlKitLlmFormatterProvider.kt` | Android-only on-device implementation (ML Kit Prompt API / Gemini Nano). Has `checkEligible(): Boolean` and a `create(): MlKitLlmFormatterProvider?` factory returning null on init failure. **Currently referenced nowhere outside its own file** — dead code, not wired into `App.kt` or any feature. No iOS/JVM/JS equivalent exists. |
| `tags/LlmTagProvider.kt`, `TagSuggestionEngine.kt`, `TagSuggestionViewModel.kt` | Consumes a single injected `LlmFormatterProvider`. `TagSuggestionEngine.hasLlmProvider` gates a second suggestion tier on top of a local `AhoCorasick` matcher; already returns `Either<DomainError, List<TagSuggestion>>`. |
| `domain/ClaudeTopicEnricher.kt` | A third, independent hand-rolled Claude HTTP client (own retry-on-429, own JSON models) — does not reuse `ClaudeLlmFormatterProvider` at all. Confirms the "every feature re-solves this" problem statement. |
| `voice/VoiceSettings.kt` | Anthropic/OpenAI keys stored **in plaintext** via the generic `platform.Settings` key-value store (`platformSettings.getString(KEY_ANTHROPIC, ...)`). No encryption. |
| `ui/App.kt` (~L983-1011, L1647-1653) | Ad hoc composable-scoped DI: `tagEngine`, `tagSuggestionViewModel`, `voiceCaptureViewModel` are all built with `remember(...) { ... }` directly in the root composable. `buildLlmFormatterForTags(voiceSettings)` is a private top-level function that inline-checks `getAnthropicKey() ?: getOpenAiKey()` — no registry, no on-device fallback. |
| `repository/RepositoryFactory.kt` | `GraphBackend` enum (`SQLDELIGHT`, `IN_MEMORY`, ...) selects **one** concrete backend per repository type per graph. Single-active-implementation-per-interface pattern, not a multi-provider registry. |
| `git/CredentialStore.kt` + 4 platform actuals + `git/CredentialAccess.kt` | **Exact precedent for secure credential storage.** `expect class CredentialStore() : CredentialAccess { store/retrieve/delete }`. Android: `EncryptedSharedPreferences` (AES-256-GCM). iOS: Keychain (`Security` framework, `SecItemAdd`/`SecItemCopyMatching`). JVM: AES-256-GCM file with PBKDF2 machine-bound key in `~/.config/stelekit/credentials.enc`. WASM/JS: no-op stub. `CredentialAccess.isAvailable()` handles "backing store locked" (used by the paranoid-mode `VaultCredentialStore`, a second `CredentialAccess` implementation). `GraphManager.removeGraph()` already constructs `CredentialStore()` directly and uses namespaced string keys (`git_https_token_$id`). |
| `db/DatabaseWriteActor.kt` | Single serialized writer coroutine; `sealed class WriteRequest` includes typed hot-path arms (`WriteBlockContent`, `WriteBlock`, `DeleteBlock`, `MergeBlocks`, `WriteBlockProperties`) plus a generic `Execute(op: suspend () -> Either<DomainError, Unit>)` escape hatch. All writes funnel through `@OptIn(DirectRepositoryWrite::class)`-gated `blockRepository`/`pageRepository` calls inside the actor. |
| `db/GraphWriter.kt` (+`GraphWriterPort.kt`) | The **actual entry point features use to persist edits** — higher-level than `DatabaseWriteActor`. `savePage(page, blocks, graphPath)` / `queueSave(...)` run an Arrow Saga: write markdown to disk → notify file-watcher suppression → write sidecar → update DB filePath, with compensating rollback of the file write if the DB step fails. `GraphWriter` takes `writeActor: DatabaseWriteActor?` as a constructor dependency and uses it internally for the DB half of the saga. Per `CLAUDE.md`'s data-flow section, `BlockEditor → BlockStateManager → (debounced 500ms) → GraphWriter.saveBlock()/queueSave()` is the existing edit path — not a direct `DatabaseWriteActor` call from UI/feature code. |
| `git/model/SyncState.kt`, `ui/StelekitViewModel.kt` (~L190-294), `ui/screens/git/JournalMergeReviewScreen.kt` | **Exact precedent for the approval-gated workflow.** `SyncState.JournalMergeReady(graphId, proposal: JournalMergeProposal)` is a sealed-state "here is a proposed change, go review it" case. `StelekitViewModel.observeSyncState()` flips `AppState.journalMergeReviewVisible = true` when this state is observed (mirrors `AppState.pendingConflicts`/`DiskConflict` for file-watcher conflicts). `acceptJournalMerge(mergedContent)` / `abortJournalMerge()` are explicit user-triggered methods that **re-validate the state hasn't advanced** (`if (syncState.value !is SyncState.JournalMergeReady) return@launch`) before executing, and the accept path writes through the normal `fileSystem.writeFile` + `gitRepository.commit` + `graphLoader.reloadFiles` pipeline — no bespoke write path. `JournalMergeReviewScreen(onAccept, onReject, onDismiss)` is a plain, stateless review composable. |
| `error/DomainError.kt` | Sealed error taxonomy already includes `NetworkError` (used by `LlmTagProvider` today) but no LLM-specific or suggestion-specific error family yet. |

---

## 1. Provider abstraction

### Decision: keep `LlmFormatterProvider` as the low-level capability contract; add a thin `LlmProvider` wrapper + flat `LlmProviderRegistry` — not a `RepositoryFactory`-style backend enum.

**Why not the `RepositoryFactory` pattern.** `GraphBackend` selects exactly **one** active
implementation per interface per graph (`SQLDELIGHT` *or* `IN_MEMORY`, chosen once at
construction). The LLM requirement is different in kind: *N* providers can be configured
simultaneously (an Android user might have both an Anthropic key **and** on-device ML Kit
available), selection is **per-feature** (tagging might use on-device, voice might use
Claude), and availability is **dynamic** (on-device eligibility can flip from
`DOWNLOADABLE` to `AVAILABLE` mid-session; a key can be revoked). A single-slot enum
dispatch can't express "list what's usable right now, then let each feature pick." A flat
registry over a list of self-describing providers fits this shape.

**Interface shape** (illustrative — exact package/naming is a Phase 3 decision):

```kotlin
// voice/LlmFormatterProvider.kt — UNCHANGED. Existing implementations
// (ClaudeLlmFormatterProvider, OpenAiLlmFormatterProvider, MlKitLlmFormatterProvider,
// LlmTagProvider's consumer contract) keep working with zero call-site churn.
fun interface LlmFormatterProvider {
    suspend fun format(transcript: String, systemPrompt: String): LlmResult
}

// New: metadata + availability wrapper, one per configured provider instance.
enum class LlmProviderKind { REMOTE, ON_DEVICE }

sealed interface LlmProviderAvailability {
    data object Available : LlmProviderAvailability
    data object Downloading : LlmProviderAvailability   // on-device model still downloading
    data class Unavailable(val reason: String) : LlmProviderAvailability
}

interface LlmProvider {
    val id: String                 // "anthropic", "openai", "gemini", "android-ondevice",
                                    // "ios-ondevice", "custom:<uuid>" for user-added endpoints
    val displayName: String
    val kind: LlmProviderKind
    val formatter: LlmFormatterProvider   // delegates to the existing contract
    suspend fun checkAvailability(): LlmProviderAvailability
}

class LlmProviderRegistry(private val providers: List<LlmProvider>) {
    fun all(): List<LlmProvider> = providers
    fun find(id: String): LlmProvider? = providers.firstOrNull { it.id == id }
    suspend fun availableProviders(): List<LlmProvider> =
        providers.filter { it.checkAvailability() !is LlmProviderAvailability.Unavailable }
}
```

`checkAvailability()` is the generalized form of `MlKitLlmFormatterProvider.checkEligible()`
— every provider gets one (remote providers can return `Available` unconditionally once a
key is present, or do a cheap reachability probe for the custom-endpoint provider per open
question 2).

**Per-feature selection** lives in a new `LlmSettings` class, same shape as the existing
`TagSettings`/`VoiceSettings` (`platform.Settings`-backed, namespaced keys):

```kotlin
enum class LlmFeature { VOICE_FORMATTING, TAG_SUGGESTION, GRAPH_EDIT_SYNTHESIS }
class LlmSettings(private val platformSettings: Settings) {
    fun getSelectedProviderId(feature: LlmFeature): String? = ...
    fun setSelectedProviderId(feature: LlmFeature, providerId: String?) = ...
}
```

**Wiring stays composable-scoped**, following the codebase's established pattern (`tagEngine`,
`voiceCaptureViewModel` in `App.kt` ~L983-1011) rather than introducing a global service
locator: `val llmRegistry = remember(llmCredentialStore, llmSettings) { buildLlmProviderRegistry(...) }`
built once in `App.kt` and threaded down, exactly like `voiceSettings`/`tagSettings` are today.
`buildLlmFormatterForTags()` (App.kt L1647) is replaced by `llmRegistry.find(llmSettings.getSelectedProviderId(TAG_SUGGESTION))`
with a fallback scan over `availableProviders()` when nothing is explicitly selected (this is
what gives tag suggestion automatic on-device fallback per the requirements' success metric).

### Cross-platform capability wiring (research question 5 / 1 combined)

`MlKitLlmFormatterProvider` today has **no expect/actual bridge at all** — it's a plain
`androidMain` class that nothing calls. There is no existing "capability not available on
this platform" expect/actual precedent in the LLM code specifically, but the codebase has
exactly this shape elsewhere: `git/CredentialStore` is `expect class` with a real
implementation on Android/iOS/JVM and a no-op stub on WASM/JS, and `MlKitLlmFormatterProvider.create()`
already returns `null` on init failure as its own local "not available" signal.

Recommended pattern — combine both precedents:

```kotlin
// commonMain
expect fun platformOnDeviceLlmProvider(): LlmProvider?

// androidMain — wraps the existing (currently unused) MlKitLlmFormatterProvider
actual fun platformOnDeviceLlmProvider(): LlmProvider? =
    MlKitLlmFormatterProvider.create()?.let { AndroidOnDeviceLlmProvider(it) }

// iosMain — new, backed by Foundation Models framework (see open question 4)
actual fun platformOnDeviceLlmProvider(): LlmProvider? = ...

// jvmMain / wasmJsMain
actual fun platformOnDeviceLlmProvider(): LlmProvider? = null
```

This is consistent with the "null = capability not available" convention already
established by `MlKitLlmFormatterProvider.create()` and the `CredentialStore` expect/actual
shape — no new pattern is being invented, just applied one level up.

---

## 2. Credential storage architecture

### Decision: generalize `git/CredentialStore` into a shared secure store; move it to a platform-layer package; migrate `VoiceSettings` keys into it (no permanent compat shim).

`git/CredentialStore` + `CredentialAccess` is already exactly the abstraction needed — an
expect/actual keyed secure string store with platform-appropriate backends (Android
`EncryptedSharedPreferences`, iOS Keychain, JVM PBKDF2+AES-GCM file, WASM/JS no-op) and a
generic `isAvailable()` hook for "backing store locked" (already exercised by
`VaultCredentialStore`, a second `CredentialAccess` implementation for paranoid-mode graphs).
`GraphManager` already treats it as general-purpose — `removeGraph()` constructs
`CredentialStore()` directly and stores non-git-specific values under namespaced string keys.

**Recommended move**: relocate `CredentialStore`/`CredentialAccess` from `git/` to a new
`platform/security/` package (or `platform/` directly), matching the project convention
that platform-specific capability abstractions live in `platform/`. This is a rename + import
update across 5 files (the expect + 4 actuals) plus the interface — low churn, and it removes
the awkwardness of LLM code importing from `dev.stapler.stelekit.git`. `VaultCredentialStore`
stays in `git/` (it's genuinely git/vault-specific — Argon2id, tied to paranoid-mode DEK
lifecycle) but continues to implement the now-relocated `CredentialAccess`, which is exactly
what it already does today, so no behavior changes.

**New credential keys**, same namespacing convention as `git_https_token_$id`:
`llm.anthropic.api_key`, `llm.openai.api_key`, `llm.gemini.api_key`,
`llm.custom.<providerId>.api_key`. Non-secret provider config (custom base URL, model name)
stays in `LlmSettings` (`platform.Settings`), matching how `git/model/GitConfig` already
separates non-secret config from `CredentialStore`-held secrets.

**Migration of `VoiceSettings` keys — full migration, not a shim.** `VoiceSettings` currently
stores Anthropic/OpenAI keys in **plaintext** via `platform.Settings` (`KEY_ANTHROPIC`,
`KEY_OPENAI`). This directly violates the requirements' "secure per-platform credential
storage... not new plaintext preferences" constraint, so it must be migrated, not
permanently dual-read. Model this as a one-shot migration, structurally similar to the
existing one-shot migrations in `db/UuidMigration.kt` / `MigrationRunner` (detect old state →
migrate once → old state no longer authoritative) but operating over `Settings` rather than
SQL: on `LlmSettings`/credential-store initialization, if `VoiceSettings.getAnthropicKey()`
returns non-null and the new `llm.anthropic.api_key` credential entry is absent, copy the
value into the credential store and clear the plaintext `Settings` key. `buildLlmFormatterForTags`
(App.kt L1647-1653) and any direct `voiceSettings.getAnthropicKey()`/`getOpenAiKey()` call
sites are updated to read through the registry/credential store instead. Do not keep a
"read old key if new key missing" runtime fallback indefinitely — that's a second live
plaintext-key code path that never goes away.

---

## 3. Approval-gated edit workflow architecture

### Decision: model directly on the existing `SyncState.JournalMergeReady` → `journalMergeReviewVisible` → `acceptJournalMerge()`/`abortJournalMerge()` pattern; pending suggestions in-memory only (per requirements' open question 3, resolved here as the v1 default); accepted writes go through `GraphWriter`, which itself calls `DatabaseWriteActor` internally — features never call the actor directly.

This is the single strongest structural precedent in the codebase for "a subsystem
proposes a change, the user reviews it in a screen, accept/reject are explicit methods that
re-validate before executing, and acceptance funnels into the existing write path." No new
pattern needs to be invented — it needs to be replicated for LLM-sourced proposals instead of
git-merge-sourced ones.

**Proposal model** (illustrative shape):

```kotlin
sealed interface PendingLlmSuggestion {
    val id: String              // UUIDv7, generated at propose time
    val sourceProviderId: String
    val proposedAtEpochMs: Long

    data class BlockEdit(
        override val id: String, override val sourceProviderId: String, override val proposedAtEpochMs: Long,
        val pageUuid: PageUuid, val blockUuid: BlockUuid,
        val currentContent: String, val proposedContent: String, val rationale: String?,
    ) : PendingLlmSuggestion

    data class TagChange(/* ... blockUuid, addedTerms, removedTerms ... */) : PendingLlmSuggestion

    data class NewPage(
        /* ... */ val proposedTitle: String, val proposedBlocks: List<ProposedBlock>,
    ) : PendingLlmSuggestion
}
```

This directly resolves open question 1 ("what does a synthesized note look like") at the
architecture level: a `NewPage` proposal is not a special case requiring a different review
UI or write path — `GraphWriter.savePage(page, blocks, graphPath)` already accepts a page plus
its full block list for both new and existing pages, so "create a new page" and "edit an
existing page's blocks" are the same write shape (`savePage`/`queueSave`) with different
input, not two different mechanisms. Block-level edits and new-page synthesis both resolve to
one `Page` + `List<Block>` before hitting the write layer.

**Where pending state lives — in-memory only (v1).** Model it as a small owned class, not a
new SQLDelight table:

```kotlin
class LlmSuggestionInbox {
    private val _pending = MutableStateFlow<Map<String, PendingLlmSuggestion>>(emptyMap())
    val pending: StateFlow<Map<String, PendingLlmSuggestion>> = _pending.asStateFlow()
    fun propose(suggestion: PendingLlmSuggestion) { _pending.update { it + (suggestion.id to suggestion) } }
    fun remove(id: String) { _pending.update { it - id } }
}
```

This mirrors `AppState.pendingConflicts: Map<String, PendingConflict>` (already the pattern
used for file-watcher conflicts that arrive while the user isn't looking) exactly in shape.
Reasons to default to in-memory rather than persisting suggestions across restarts:

- Requirements explicitly scope this as single-shot propose → review, **not** an agentic
  loop (Out of Scope) — there's no long-running background job whose progress needs to
  survive a restart.
- A persisted-suggestion table would need to follow the mandatory `SteleDatabase.sq` +
  `MigrationRunner.all` + `@DirectSqlWrite` machinery this codebase deliberately gates (see
  `CLAUDE.md`'s "Adding a new table" and "Write enforcement" sections) — real cost for a
  feature whose worst-case failure mode (app restarts mid-review) is "the user re-runs the
  proposal," not data loss, since **nothing is written to the graph until accept**.
- It matches the existing `DiskConflict`/`PendingConflict` precedent, which is also
  memory-only.

If real usage later shows suggestions need to survive restarts (e.g. a future long-running
synthesis job), that's a follow-up ADR with its own table + migration, not a v1 default.

**Data flow:**

1. Feature code (new synthesis/edit service, structurally parallel to `LlmTagProvider`) calls
   the feature's selected `LlmProvider`, parses a structured proposal from the response
   (parsing precedent: `LlmTagProvider.parseResponse`), and calls
   `LlmSuggestionInbox.propose(suggestion)`.
2. `StelekitViewModel` observes `LlmSuggestionInbox.pending` the same way it observes
   `syncState` today (`ui/StelekitViewModel.kt` ~L196-213) and flips a new
   `AppState.llmSuggestionReviewVisible = true` flag when the map is non-empty — same
   mechanism as `journalMergeReviewVisible`/`conflictResolutionVisible`.
3. A new stateless review screen (parallel to `JournalMergeReviewScreen(onAccept, onReject,
   onDismiss)`) renders the diff (current vs. proposed content) and calls
   `acceptLlmSuggestion(id)` / `rejectLlmSuggestion(id)` on the ViewModel.
4. **Accept** (`StelekitViewModel.acceptLlmSuggestion(id)`):
   - Re-validate the suggestion is still present in the inbox (same race guard as
     `abortJournalMerge`/`acceptJournalMerge`'s "state may have advanced" check) — guards
     against a stale accept firing after the suggestion was already resolved/expired.
   - Remove it from the inbox immediately (optimistic dismiss).
   - Materialize the target `Page` + updated `List<Block>` and call
     `graphWriter.savePage(page, blocks, graphPath)` (or `queueSave` for the debounced path)
     — **not** `DatabaseWriteActor` directly. `GraphWriter` already owns the
     file-write + DB-write saga (with rollback) that every other edit path in the app uses;
     routing through it means the LLM path automatically gets the same file-watcher
     suppression, sidecar handling, and DB-write serialization via the actor it holds
     internally, with zero new write surface.
   - Return `Either<DomainError, Unit>` and surface failures through the existing
     error/toast state — no new error channel. Extend `DomainError` with a small
     `LlmError`/`SuggestionError` family only if a genuinely new failure mode emerges (e.g.
     "target block no longer exists" — arguably reuses `DatabaseError.NotFound`).
5. **Reject**: `LlmSuggestionInbox.remove(id)` only — pure in-memory, cannot fail, no
   `Either` needed.

**No auto-apply, ever, for this path.** The inbox never self-drains; only explicit
`acceptLlmSuggestion`/`rejectLlmSuggestion` calls remove entries (besides app-session end,
which silently discards them given the in-memory choice above). This matches the resolved
requirement exactly and is structurally enforced by the inbox having no internal timer or
confidence-threshold logic — contrast with `TagSuggestionEngine.AUTO_APPLY_THRESHOLD`, which
is a **different, already-shipped** feature this workflow must not touch or resemble.

---

## 4. Settings/credential UI state management

Follow the existing `AppState`/`StelekitViewModel` pattern used for git setup
(`gitSetupVisible`, `gitSetupInitialStep`, `openGitSetup()`/`dismissGitSetup()` in
`StelekitViewModel.kt` ~L232-249) rather than a standalone settings ViewModel:

- Add `llmProviderSettingsVisible: Boolean` (and any wizard-step field it needs) to
  `AppState`, plus `openLlmProviderSettings()` / `dismissLlmProviderSettings()` on
  `StelekitViewModel`, mirroring `openGitSetup()`/`dismissGitSetup()`.
- The settings screen itself reads `llmRegistry.all()` (static list, no suspend needed to
  render identity/name) and, per row, calls `checkAvailability()` (suspend) to show live
  eligibility/download status — this is the generalized `MlKitLlmFormatterProvider.checkEligible()`
  surfaced in UI, same idea as how git setup already surfaces `SyncState` live.
- Per-feature provider pickers (tagging / voice / synthesis) read and write through
  `LlmSettings.getSelectedProviderId(feature)` / `setSelectedProviderId(feature, id)` —
  no new state management concept, just another `Settings`-backed class alongside
  `TagSettings`/`VoiceSettings`.
- Credential add/edit fields write through the relocated `CredentialStore` (§2) directly;
  the screen never touches `platform.Settings` for secret values.
- This keeps the settings screen a "dumb" composable reading `remember`-scoped registry/settings
  objects passed down from `App.kt`, consistent with how every other settings surface in this
  codebase is wired (no new DI/state-management pattern introduced).

---

## Key files for planning phase

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/LlmFormatterProvider.kt` — extend with `LlmProvider`/`LlmProviderAvailability`, keep the fun interface unchanged.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/ClaudeLlmFormatterProvider.kt`, `OpenAiLlmFormatterProvider.kt` — migrate under `LlmProvider` wrappers; add Gemini + generic OpenAI-compatible siblings here.
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt` — wire via new `expect fun platformOnDeviceLlmProvider()`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricher.kt` — retire in favor of the unified `ClaudeLlmFormatterProvider`-backed provider.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt` — remove Anthropic/OpenAI plaintext key storage after one-shot migration.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/CredentialStore.kt` (+ `CredentialAccess.kt` + 4 platform actuals) — relocate to a platform-layer security package; reuse for LLM keys.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSettings.kt` — pattern reference for new `LlmSettings`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (~L190-294), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/JournalMergeReviewScreen.kt` — direct structural precedent for the approval workflow.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` / `GraphWriterPort.kt` — the write entry point accepted suggestions must go through.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt` — confirm which existing `WriteRequest` arm (or a new one) `GraphWriter`'s internal call should use for suggestion-originated writes; this is a Phase 3 (planning) decision, not architecture.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` — add `llmSuggestionReviewVisible`, `pendingLlmSuggestions` (or delegate to `LlmSuggestionInbox.pending` directly), `llmProviderSettingsVisible`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (~L983-1011, L1647-1653) — replace `buildLlmFormatterForTags` and the ad hoc `tagEngine` LLM wiring with `LlmProviderRegistry`/`LlmSettings` lookups; add `LlmSuggestionInbox` to the same `remember` block.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt` — extend only if a genuinely new failure mode surfaces; default to reusing `NetworkError`/`DatabaseError` cases.
