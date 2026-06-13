# Adversarial Review: stelekit-share-note

**Date**: 2026-06-07
**Verdict**: CONCERNS

---

## Blockers

*(None — no items require resolution before implementation can start)*

---

## Concerns

- [ ] **Missing snackbar / event emission mechanism in ViewModel** — `shareToGoogleDocs()` (Story 1.2.2b) emits a "snackbar event on error," but `StelekitViewModel` has no snackbar event `SharedFlow` or `Channel` defined in the existing code. Without this channel, the composable cannot receive error signals from the ViewModel scope. Recommendation: confirm `StelekitViewModel` already has a snackbar/notification event bus (e.g., `_notificationFlow`) or add it as an explicit task in Story 1.2.2b. The plan says "emit Snackbar event via ViewModel event state" without specifying the exact mechanism.

- [ ] **`rememberShareProvider()` called inside `App.kt` but `SaveToFile` on Android requires Activity result launcher** — `AndroidShareProvider.saveToFile()` (Task 1.1.2b) suspends on a `CompletableDeferred<Uri?>` that must be completed by `MainActivity`'s SAF result launcher. The current `saveFileLauncher` in `MainActivity` is typed to `CreateDocument("application/json")` and is not injectable from `AndroidShareProvider`. The plan notes this risk but does not describe how `AndroidShareProvider` will receive the completed deferred from `MainActivity`. Recommendation: Task 1.1.2b must explicitly state that a process-level `CompletableDeferred<Uri?>` (like `pendingSaveFile`) is stored in `MainActivity` and `AndroidShareProvider` reads from it — or use a `SharedFlow` bridge identical to the OAuth bridge (ADR-5 pattern). The plan leaves this "TBD."

- [ ] **`viewModel.connectGoogle()` anti-pattern: ViewModel method that takes GoogleAuthManager as parameter** — Story 5.2.2a shows `viewModel.connectGoogle(googleAuthManager)`. The `GoogleAuthManager` is platform-specific; passing it into the ViewModel creates a platform dependency in a `commonMain` class. Current codebase pattern: `StelekitViewModel.setClipboardProvider()` injects after construction via `var clipboard`. Recommendation: follow the same inject-after-construction pattern for `GoogleAuthManager`, or call `googleAuthManager.authenticate()` directly from a `rememberCoroutineScope()` click handler (it's a fast non-network call on Android; the suspend happens inside it). Either pattern is fine — just make it explicit in the plan rather than leaving `connectGoogle(googleAuthManager)` which implies the ViewModel stores a platform type.

- [ ] **`exportPageWithLinks()` calls `graphLoader.loadPage()` — this function's return type and signature are unknown** — Task 4.2.1a says "call `graphLoader.loadPage(linkedPage)` for pages with `isLoaded == false`." The `GraphLoaderPort` interface must expose this method. Without verifying the exact method name and signature on `GraphLoaderPort`, this task may require interface changes that the plan doesn't account for. Recommendation: before implementing Story 4.2.1, inspect `GraphLoaderPort.kt` for the correct method name (it may be `loadPageFromFile`, `reloadPage`, or have a different signature). Add a note to the task.

- [ ] **Journal date range export: `getAllPages()` returns a `Flow`, not a `suspend` value** — Task 4.4.1a says `pageRepo.getAllPages().first()`. For a large graph, collecting all pages just to filter by date in memory could return tens of thousands of records. `PageRepository.getAllPages()` on the SQLDelight implementation likely returns ALL pages including non-journal ones. For correctness, filter by `namespace == "journals"` or use `getPagesInNamespace("journals")` which is already on `PageRepository`. Recommendation: update Task 4.4.1a to use `pageRepo.getPagesInNamespace("journals").first()` instead of `getAllPages()` — this is a smaller result set and avoids loading non-journal pages.

- [ ] **Missing `DomainError.ExportError.ShareFailed` variant** — Several share operations (SAF file write failure, iOS share dismiss) return `false` or silently succeed. If the SAF `ContentResolver.openOutputStream()` throws, the current `ShareProvider.saveToFile()` signature returns `Boolean` with no error details. The ViewModel / composable cannot distinguish "user cancelled" from "write failed." Recommendation: either change `saveToFile()` to return `Either<DomainError, Boolean>` where `false` = cancelled and `Left` = error, OR add a `DomainError.ExportError.ShareFailed(message)` variant and return `Either<DomainError, Unit>` with a dedicated cancellation signal.

- [ ] **iOS `UIActivityViewController` must be presented on the main thread** — Task 1.1.4a implements `IosShareProvider.shareText()` which calls UIKit APIs. iOS requires UIKit calls on the main thread. The `ShareProvider` interface uses `suspend fun` — the caller may be on any coroutine dispatcher. Recommendation: add `withContext(Dispatchers.Main)` around all UIKit calls in `IosShareProvider`. The plan does not mention this dispatcher requirement for iOS (it does for JVM `FileDialog`).

---

## Minors

- Task 3.2.3a says "exact path TBD on inspection" for the sidebar page context menu composable. This is acceptable for planning but risks scope creep at implementation time if the sidebar menu has multiple levels to thread `onShareClick` through. Pre-verify the call site during Task 3.2.3a to avoid surprise parameter threading.

- Task 3.2.4a says "exact path TBD on inspection" for the journal header. Same minor risk as above.

- `openInBrowser` (Epic 2.2b) is correctly identified as missing, but the plan adds it as an epic under Phase 2 which is "OAuth Fixes." Consider placing it in Phase 1 as a Foundation task since it's used by `shareToGoogleDocs()` in Story 1.2.2b.

- `ShareProvider.shareHtml()` on iOS degrades to `plainFallback` (Task 1.1.4a). This is stated in the plan but not in the Acceptance Criteria for Story 1.1.4. It should be an explicit AC so it's not inadvertently "fixed" by a future implementer who doesn't understand the scope.

- The plan's Dependency Visualization places Epic 2 (OAuth Fixes) as parallel with Phase 1, but `openInBrowser` (Epic 2.2b) is actually needed by `shareToGoogleDocs` in Phase 1's Story 1.2.2b. The visualization should show `2.2b → 1.2.2b`.

- `DriveApiClient` injects `GoogleAuthClient`, not `GoogleAuthManager`. The `shareToGoogleDocs()` ViewModel method (Task 1.2.2b) receives `driveApiClient: DriveApiClient` directly — this is correct and confirmed by the architecture. No concern here, but ensure the `ShareDialog` composable passes the `driveApiClient` it receives from `App.kt`.

- Test coverage for the iOS `ShareProvider` is not planned (no `iosTest` task). iOS UI tests are expensive to set up, so this is a minor omission — a unit test for the iOS save-to-file temp-file creation logic would be valuable but is not blocking.
