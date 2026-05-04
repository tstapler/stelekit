# Current Page Integration — Voice Note Feature

## Research Question
How does the app track the currently-open page, and how can `VoiceCaptureViewModel` access it to insert voice notes to the current page instead of only today's journal?

---

## How the App Tracks the Current Page

### AppState.kt

`AppState` contains two relevant fields:

```kotlin
data class AppState(
    val currentScreen: Screen = Screen.Journals,
    val currentPage: Page? = null,
    // ...
)
```

- `currentPage: Page?` is `null` when the user is on a non-page screen (Journals, AllPages, etc.) and non-null when `currentScreen` is `Screen.PageView(page)`.
- Both are set together in `StelekitViewModel.navigateTo()`:
  ```kotlin
  state.copy(
      currentScreen = screen,
      currentPage = if (screen is Screen.PageView) screen.page else null,
      // ...
  )
  ```
- Also accessible as `(appState.currentScreen as? Screen.PageView)?.page`.

### StelekitViewModel.uiState

`StelekitViewModel` exposes:
```kotlin
val uiState: StateFlow<AppState> = _uiState.asStateFlow()
```

The current page UUID at any moment is:
```kotlin
viewModel.uiState.value.currentPage?.uuid
```

---

## How VoiceCaptureViewModel is Currently Wired (App.kt lines 462–463)

```kotlin
val voiceCaptureViewModel = remember(voicePipeline) {
    VoiceCaptureViewModel(voicePipeline, repos.journalService)
}
```

`VoiceCaptureViewModel` constructor signature:
```kotlin
class VoiceCaptureViewModel(
    private val pipeline: VoicePipelineConfig,
    private val journalService: JournalService,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)
```

The VM calls `journalService.appendToToday(...)` unconditionally in `processTranscript()` — there is no concept of a target page.

---

## JournalService.appendToToday vs. What We Need

`JournalService.appendToToday(content)`:
1. Calls `ensureTodayJournal()` to get/create today's journal page
2. Appends a new block to that page

For FR-3 we need an analogous `appendToPage(pageUuid, content)` path:
1. Resolve the page by UUID from `PageRepository`
2. Append a new block (same block-creation logic as `appendToToday`)

**Option A — Add `appendToPage` to `JournalService`:** Minimal coupling; `JournalService` already has `blockRepository` and can append to any page.

**Option B — Inject `BlockRepository` directly into `VoiceCaptureViewModel`:** More coupling but avoids bloating `JournalService` with non-journal logic.

**Recommendation: Option A** — `JournalService` already owns the block-append logic. Adding:
```kotlin
suspend fun appendToPage(pageUuid: String, content: String) {
    val blocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
    val nextPosition = (blocks.maxOfOrNull { it.position } ?: -1) + 1
    val newBlock = Block(
        uuid = UuidGenerator.generateV7(),
        pageUuid = pageUuid,
        content = content,
        position = nextPosition,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )
    if (writeActor != null) writeActor.saveBlock(newBlock)
    else blockRepository.saveBlock(newBlock)
}
```
is a near-copy of `appendToToday`'s block-creation section.

---

## Wiring the Current Page UUID into VoiceCaptureViewModel

### Option A — Pass `currentPageUuid` as a `StateFlow<String?>` parameter

```kotlin
class VoiceCaptureViewModel(
    private val pipeline: VoicePipelineConfig,
    private val journalService: JournalService,
    private val currentPageUuid: StateFlow<String?> = MutableStateFlow(null),
    scope: CoroutineScope = ...,
)
```

In `processTranscript()`:
```kotlin
val targetPageUuid = currentPageUuid.value
if (targetPageUuid != null) {
    journalService.appendToPage(targetPageUuid, buildVoiceNoteBlock(...))
} else {
    journalService.appendToToday(buildVoiceNoteBlock(...))
}
```

At the call site in `App.kt`:
```kotlin
val voiceCaptureViewModel = remember(voicePipeline) {
    VoiceCaptureViewModel(
        voicePipeline,
        repos.journalService,
        currentPageUuid = viewModel.uiState.map { it.currentPage?.uuid }.stateIn(
            scope = /* some scope */,
            started = SharingStarted.Eagerly,
            initialValue = null
        )
    )
}
```

**Problem:** `remember(voicePipeline)` recreates the VM only when `voicePipeline` changes. The `currentPageUuid` StateFlow is derived from `viewModel.uiState` which already updates reactively — the VM can read `.value` at the moment of insertion and always gets the current page.

**Scope for stateIn:** Cannot use `rememberCoroutineScope()` (violates CLAUDE.md rule). Must use a scope that lives at least as long as the VM. Use the coroutine scope already available in `GraphContent` (the scope owned by `viewModel` or a dedicated `remember { CoroutineScope(...) }`).

### Option B — Pass `() -> String?` lambda (simpler, no StateFlow overhead)

```kotlin
class VoiceCaptureViewModel(
    private val pipeline: VoicePipelineConfig,
    private val journalService: JournalService,
    private val currentOpenPageUuid: () -> String? = { null },
    scope: CoroutineScope = ...,
)
```

At call site:
```kotlin
VoiceCaptureViewModel(
    voicePipeline,
    repos.journalService,
    currentOpenPageUuid = { viewModel.uiState.value.currentPage?.uuid }
)
```

This is the **simpler option** — no `stateIn` scope issues, no StateFlow chain. The lambda captures `viewModel` (stable reference) and reads `uiState.value` at call time.

**Recommendation: Option B** — fewer moving parts, no scope ownership concern, straightforward testability (inject a lambda in tests).

---

## Test Strategy for FR-3

In `VoiceCaptureViewModelTest`:
```kotlin
// When page is open — should append to that page, not journal
val targetRepo = InMemoryBlockRepository()
val targetPageService = JournalService(InMemoryPageRepository(), targetRepo)
val targetPage = targetPageService.ensureTodayJournal() // or create a non-journal page

val vm = VoiceCaptureViewModel(
    pipeline = VoicePipelineConfig(sttProvider = ...),
    journalService = targetPageService,
    currentOpenPageUuid = { targetPage.uuid }
)
// assert block inserted in targetRepo under targetPage.uuid

// When no page is open — should fall back to journal
val vm2 = VoiceCaptureViewModel(
    pipeline = ...,
    journalService = ...,
    currentOpenPageUuid = { null }   // no page open
)
// assert block inserted into today's journal
```

---

## Edge Cases

1. **Page deleted while recording:** The page UUID resolves at insertion time. If `appendToPage` can't find the page, it should fall back to journal. Add a null-check on the page lookup and fall back to `appendToToday`.
2. **Journal page is open:** `currentPage?.uuid` will be non-null for journal pages too. FR-3 says "append to current page when open" regardless of whether it's a journal. This is correct behavior — the user can see which page they're on.
3. **Journals screen (no page open):** `currentPage` is `null` → `currentOpenPageUuid()` returns `null` → fall back to `appendToToday`. Correct.

---

## 3-Bullet Summary

- **`AppState.currentPage: Page?` in `StelekitViewModel.uiState: StateFlow<AppState>` is the canonical source** for the currently-open page — it is set to `null` on non-page screens and non-null whenever `Screen.PageView` is active.
- **The cleanest wiring is a `currentOpenPageUuid: () -> String?` lambda constructor parameter** on `VoiceCaptureViewModel` — the lambda reads `viewModel.uiState.value.currentPage?.uuid` at insertion time, requires no StateFlow chaining, and is trivial to fake in tests.
- **`JournalService.appendToPage(pageUuid, content)` needs to be added** (a near-copy of the block-creation logic inside `appendToToday`) to support inserting into non-today pages; the fallback path when `currentOpenPageUuid()` returns `null` continues calling `appendToToday`.
