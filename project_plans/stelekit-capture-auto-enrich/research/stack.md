# Research: Technology Stack — stelekit-capture-auto-enrich

Agent 1 (Stack). Scope: exact pinned versions, established concurrency pattern for
race-safe lazy construction, established chip-tray Compose pattern, and confirmation
that no new external dependency is needed.

## 1. Pinned versions (this repo, read directly)

| Library | Version | Source |
|---|---|---|
| Kotlin | 2.3.21 | [`settings.gradle.kts:10`](../../../settings.gradle.kts#L10) |
| `org.jetbrains.compose` (Compose Multiplatform plugin) | 1.10.3 | [`settings.gradle.kts:17`](../../../settings.gradle.kts#L17) |
| `kotlinx-coroutines-core` / `-android` / `-swing` | 1.10.2 | [`kmp/build.gradle.kts:94,244,164`](../../../kmp/build.gradle.kts#L94) |
| `kotlinx-coroutines-test` | 1.10.2 | [`kmp/build.gradle.kts:147,340,362,392`](../../../kmp/build.gradle.kts#L147) |
| Compose Material3 (commonMain, via `compose.material3` KMP artifact) | resolved by the 1.10.3 Compose plugin BOM | [`kmp/build.gradle.kts:107`](../../../kmp/build.gradle.kts#L107) |
| `androidx.compose.material3:material3` (androidMain, Jetpack) | 1.4.0 | [`kmp/build.gradle.kts:260`](../../../kmp/build.gradle.kts#L260) |
| `androidx.compose.ui:ui` / `ui-graphics` (androidMain) | 1.10.6 | [`kmp/build.gradle.kts:258-259`](../../../kmp/build.gradle.kts#L258) |
| `androidx.compose:compose-bom` (androidApp module) | 2024.09.02 | [`androidApp/build.gradle.kts:108`](../../../androidApp/build.gradle.kts#L108) |
| `androidx.activity:activity-compose` | 1.9.2 | [`kmp/build.gradle.kts:241`](../../../kmp/build.gradle.kts#L241), [`androidApp/build.gradle.kts:106`](../../../androidApp/build.gradle.kts#L106) |
| Arrow (`arrow-fx-coroutines`) | 2.2.1.1 | [`kmp/build.gradle.kts:90`](../../../kmp/build.gradle.kts#L90) |
| `com.google.mlkit:genai-prompt` (on-device LLM, Gemini Nano/AICore tier) | 1.0.0-beta2 | [`kmp/build.gradle.kts:303`](../../../kmp/build.gradle.kts#L303) |

`androidApp/build.gradle.kts:104` — `implementation(project(":kmp"))` — confirms
`androidApp` already has the whole `commonMain`/`androidMain` classpath (domain layer,
`llm-service`, Compose) available; `CaptureActivity`/`CaptureViewModel` can reference
`ImportService`, `PageNameIndex`, `TopicExtractor`, `TopicEnricher`,
`LlmProviderRegistry`/`LlmProvider`, and `MlKitLlmFormatterProvider` with no new
`build.gradle.kts` edit.

All target files live under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/`
(`AhoCorasickMatcher.kt`, `ImportService.kt`, `PageNameIndex.kt`, `TopicEnricher.kt`,
`TopicExtractor.kt`) and `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/`
(`LlmProvider.kt`, `LlmProviderRegistry.kt`), with the Android on-device tier in
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt`.

## 2. Race-safe single-flight construction: `Mutex` + `CompletableDeferred` is the established pattern

**No new abstraction needed — reuse the shape of `RequestCoalescer<K, V>`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/RequestCoalescer.kt`).** This is
the closest existing analog to "rapid concurrent callers must share one in-flight
construction, not race to create N."

```kotlin
// RequestCoalescer.kt:39-74
class RequestCoalescer<K : Any, V> {
    private val mutex = Mutex()
    private val inflight = HashMap<K, CompletableDeferred<V>>()

    suspend fun execute(key: K, loader: suspend () -> V): V {
        val (deferred, isOwner) = mutex.withLock {
            val existing = inflight[key]
            if (existing != null) existing to false
            else CompletableDeferred<V>().also { inflight[key] = it } to true
        }
        if (isOwner) {
            try { deferred.complete(loader()) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { deferred.completeExceptionally(e) }
            finally { mutex.withLock { inflight.remove(key) } }
        }
        return deferred.await()
    }
}
```

Two caveats for `coordinatorFor`/`CaptureEnrichmentCoordinator` construction, since the
requirement is a **memoized, long-lived** instance per capture session (not a
transient coalesced call that self-clears after completion):

- `RequestCoalescer.execute` removes the key from `inflight` once the loader
  completes — the *next* unrelated call for the same key starts a fresh load. That's
  correct for its use case (DB reads) but wrong for a coordinator you want to keep
  alive for the rest of the capture session. The simplest correct adaptation is a bare
  `Mutex`-guarded nullable field checked/set *inside* the lock (not a
  double-checked-outside-the-lock read), e.g.:

  ```kotlin
  private val coordinatorMutex = Mutex()
  private var coordinator: CaptureEnrichmentCoordinator? = null

  private suspend fun coordinatorFor(graphPath: String): CaptureEnrichmentCoordinator =
      coordinatorMutex.withLock {
          coordinator ?: CaptureEnrichmentCoordinator(graphPath, /* ... */).also { coordinator = it }
      }
  ```

  This mirrors the codebase's other "mutex-guarded `getOrPut`" precedent — the
  per-path lock cache in `GraphLoader.getFileLock()`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt:1310-1317`,
  `fileLocksMutex.withLock { fileLocks.getOrPut(path) { Mutex() } }`) and the
  per-block lock cache in `BlockStateManager.contentMutationMutex()`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt:813-817`).
  Both use a guard `Mutex` around a `MutableMap.getOrPut`, which is exactly
  "construct-once-under-lock," never a nullable field checked outside the lock.
- Alternatively, a **memoized `Deferred`** (one `CompletableDeferred<CaptureEnrichmentCoordinator>`
  created once under the mutex, `await()`ed by every caller including the owner) is
  equally valid and is the shape `GraphManager._pendingMigration` and
  `AndroidPhotoPickerLauncher.pendingResult` use for "exactly one in-flight/complete
  operation, others await the same result"
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:91`,
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/sensor/AndroidPhotoPickerLauncher.kt:44,92`).
  Either `Mutex`-guarded nullable field or memoized `Deferred` satisfies AC 8
  ("single-flight via `Mutex` or memoized `Deferred`"); the plain nullable-field
  double-checked pattern the requirements explicitly forbid is the one *without* the
  mutex guarding both the check and the write.

Grep evidence (representative, not exhaustive — `Mutex(` appears ~30 times across
`kmp/src/commonMain` and `kmp/src/wasmJsMain`; `CompletableDeferred` in
`RequestCoalescer.kt`, `DatabaseWriteActor.kt`, `GraphManager.kt`,
`AndroidPhotoPickerLauncher.kt`, `HostDirectorySync.kt`):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/cache/RequestCoalescer.kt:40-41`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt:1310-1317`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt:813-817`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:91,567`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/sensor/AndroidPhotoPickerLauncher.kt:44,92`

## 3. Compose chip-tray pattern already in use — `LazyRow`, not `FlowRow`

`FlowRow` is not used anywhere in `kmp/src` (`grep -rl FlowRow kmp/src` returns no
matches). The established, already-shipped chip tray is `ImportScreen.kt`'s
`TopicSuggestionChip` inside a `LazyRow`:

- Matched-page chips: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt:390` (`LazyRow`) → `SuggestionChip` at `:395`.
- Topic-suggestion tray: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/ImportScreen.kt:509` (`LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp))`) wrapping `TopicSuggestionChip` (private composable, `:546-606`).

Chip anatomy implemented exactly as ADR-004 describes (confidence dot → term → dismiss
`×` / accepted checkmark), matching
`project_plans/import-topic-suggestions/decisions/ADR-004-suggestion-chip-tray-ux.md`:

```kotlin
// ImportScreen.kt:551-606 (abridged)
val dotColor = when {
    suggestion.confidence >= 0.7f -> MaterialTheme.colorScheme.primary
    suggestion.confidence >= 0.4f -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.error
}
val chipBackground = if (suggestion.accepted) MaterialTheme.colorScheme.secondaryContainer
                     else MaterialTheme.colorScheme.surface
Row(...) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))   // confidence dot
    Text(suggestion.term, style = MaterialTheme.typography.bodySmall) // term
    if (suggestion.accepted) Icon(Icons.Default.Check, ...)           // accepted state
    else IconButton(onClick = onDismissed) { Icon(Icons.Default.Close, ...) } // dismiss ×
}
```

For the capture bottom sheet's compact chip tray (v1 scope: single-item accept only,
no "Accept All" dialog, no Claude-status header badge), the recommended reuse strategy
is: extract or duplicate `TopicSuggestionChip`'s Row/Box/Text/Icon structure at a
smaller `padding`/`size` (the requirements call for "sized down for a compact bottom
sheet"), driven by a `LazyRow`, not introduce `FlowRow` or any new chip primitive.
`AssistChip`/`SuggestionChip` (Material3 built-ins) are already imported/used in
`ImportScreen.kt:395` (`SuggestionChip`) for the matched-pages row and are available on
the classpath (`compose.material3`) — no new dependency needed here either.

## 4. No new external dependency needed — confirmed

Every symbol named in the requirements doc already resolves on the `androidApp` →
`kmp` classpath with zero `build.gradle.kts` changes:

- `ImportService`, `PageNameIndex`, `AhoCorasickMatcher`, `TopicExtractor`,
  `TopicEnricher` — `kmp/src/commonMain/.../domain/` (pure Kotlin, already
  `commonMain`-visible to `androidMain`/`androidApp`).
- `LlmProviderRegistry`, `LlmProvider` — `kmp/src/commonMain/.../llm/`.
- `MlKitLlmFormatterProvider` (on-device Gemini Nano/AICore tier, backed by
  `com.google.mlkit:genai-prompt:1.0.0-beta2`, already pinned at
  [`kmp/build.gradle.kts:303`](../../../kmp/build.gradle.kts#L303)) —
  `kmp/src/androidMain/.../voice/`.
- `GraphWriter.savePage`, `DatabaseWriteActor.saveBlock` — `kmp/src/commonMain/.../db/`.
- Coroutine primitives (`Mutex`, `CompletableDeferred`, `Deferred`) — already
  transitively available via `kotlinx-coroutines-core:1.10.2`
  (`kmp/build.gradle.kts:94`) plus `-android:1.10.2` (`:244`) in `androidMain`.
- Compose (`LazyRow`, `Icon`, `IconButton`, Material3 chip/color/typography APIs) —
  already on the `androidApp` classpath via `androidx.compose:compose-bom:2024.09.02`
  (`androidApp/build.gradle.kts:108`) and the KMP `compose.material3` artifact
  resolved by the `org.jetbrains.compose` 1.10.3 plugin
  (`kmp/build.gradle.kts:107`, plugin version at `settings.gradle.kts:17`).

`androidApp/build.gradle.kts:104` (`implementation(project(":kmp"))`) is the one line
that makes all of the above reachable from `CaptureActivity`/`CaptureViewModel`.
**No new dependency coordinates need to be added anywhere.**
