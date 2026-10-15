# Test Stack Research: UI Screen Coverage

## 1. Library Versions (from `kmp/build.gradle.kts`, `androidUnitTest` source set, lines 361–385)

| Library | Version |
|---|---|
| JUnit 4 | `junit:junit:4.13.2` |
| Robolectric | `org.robolectric:robolectric:4.16` |
| AndroidX Test Core | `androidx.test:core:1.6.1` |
| AndroidX Test JUnit ext | `androidx.test.ext:junit:1.2.1` |
| Compose UI Test JUnit4 | `androidx.compose.ui:ui-test-junit4:1.10.6` |
| Compose UI Test Manifest | `androidx.compose.ui:ui-test-manifest:1.10.6` |
| kotlinx-coroutines-test | `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2` |
| Roborazzi | `io.github.takahirom.roborazzi:roborazzi:1.59.0` |
| Roborazzi Compose | `io.github.takahirom.roborazzi:roborazzi-compose:1.59.0` |
| kotlin-reflect | `org.jetbrains.kotlin:kotlin-reflect:2.3.21` |
| androidx.arch.core:core-testing | `2.2.0` |

## 2. Fake/Mock Infrastructure

All fakes live in `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/FakeRepositories.kt` (accessible from `androidUnitTest` via dependency resolution or duplication — verify visibility before use):

| Class | Implements | Notes |
|---|---|---|
| `FakePageRepository` | `PageRepository` | In-memory `MutableStateFlow<Map<String, Page>>`; supports all query and write methods |
| `FakeBlockRepository` | `BlockRepository` | In-memory `MutableStateFlow<Map<String, Block>>`; full CRUD + indent/outdent/merge/split |
| `InMemorySettings` | `Settings` | Simple `mutableMapOf<String, String>()` back store |
| `FakeFileSystem` | `FileSystem` | All methods return safe no-op defaults |
| `PopulatedFakePageRepository` | `FakePageRepository` | Pre-seeded with 1 page + 1 journal (convenience) |
| `PopulatedFakeBlockRepository` | `FakeBlockRepository` | Pre-seeded with 2 blocks (convenience) |

Additional platform-specific fakes (in `androidUnitTest`):
- `FakeExternalStorageProvider` — `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/testsupport/FakeExternalStorageProvider.kt`

Domain error fakes: tests create `Either.left(DomainError.*)` inline using Arrow (`arrow.core.left`, `arrow.core.right`) — no dedicated error-stub class.

## 3. Established Test Pattern

Reference files:
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreenTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupStep5CancelTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/CopyPagesScreenTest.kt`

**Canonical anatomy:**
```kotlin
@RunWith(RobolectricTestRunner::class)
// Optional: @Config(sdk = [33]) to pin Android SDK level
class MyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // State captured by lambdas (no ViewModel needed for stateless/callback screens)
    private var capturedArg: Foo? = null

    private fun showScreen(param: Bar = defaultValue) {
        composeTestRule.setContent {
            MaterialTheme {           // required for Material3 theme tokens
                MyScreen(
                    state = ...,
                    onAction = { capturedArg = it },
                )
            }
        }
    }

    @Test
    fun `button is disabled until precondition met`() {
        showScreen()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Check this").performClick()
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }
}
```

Key rules observed across all test files:
1. **No `@Config` required** unless SDK-level pinning is needed; Robolectric 4.16 defaults to API 34.
2. **`MaterialTheme { }` wrapper** is always present — without it Material3 tokens crash at runtime.
3. **Stateless/callback screens** (e.g. `Step5TestAndSave`, `ConflictResolutionScreen`) are tested by passing lambda callbacks and asserting via captured vars — no ViewModel, no repository.
4. **Stateful screens** (e.g. `CopyPagesScreen`) pass pre-built `State` objects or `MutableStateFlow` directly.
5. **`composeTestRule.waitForIdle()`** is called after side-effect-triggering clicks (e.g. after "Finish Merge" triggers a suspend callback).
6. **`performScrollTo()` before assertion** for nodes that might be off-screen in a scrollable column.
7. **`@OptIn(ExperimentalTestApi::class)`** is needed for `performKeyInput` / keyboard tests.

## 4. Compose Test APIs Used

From direct imports observed across `androidUnitTest` UI tests:

| API | Package | Used for |
|---|---|---|
| `onNodeWithText(text, substring = false)` | `androidx.compose.ui.test` | Find by visible text |
| `onNodeWithTag(tag)` | `androidx.compose.ui.test` | Find by `Modifier.testTag(...)` |
| `onNodeWithContentDescription(cd)` | `androidx.compose.ui.test` | Find by accessibility label |
| `onAllNodesWithText(text)` | `androidx.compose.ui.test` | Multiple matches |
| `hasText(text)` | `androidx.compose.ui.test` | Semantic matcher combinator |
| `assertIsDisplayed()` | `androidx.compose.ui.test` | Visibility assertion |
| `assertIsEnabled()` / `assertIsNotEnabled()` | `androidx.compose.ui.test` | Interaction state |
| `assertIsOn()` / `assertIsOff()` | `androidx.compose.ui.test` | Toggle/checkbox state |
| `assertCountEquals(n)` | `androidx.compose.ui.test` | Node collection size |
| `performClick()` | `androidx.compose.ui.test` | Trigger click |
| `performScrollTo()` | `androidx.compose.ui.test` | Scroll node into view |
| `performTextReplacement(text)` | `androidx.compose.ui.test` | Replace text field content |
| `performKeyInput { pressKey(Key.X) }` | `androidx.compose.ui.test` | Keyboard events |
| `requestFocus()` | `androidx.compose.ui.test` | Give focus to a node |
| `onFirst()` | `androidx.compose.ui.test` | First node from collection |
| `getUnclippedBoundsInRoot()` | `androidx.compose.ui.test` | Layout position assertion |
| `createComposeRule()` | `androidx.compose.ui.test.junit4` | Test rule (no `Activity` context needed) |

For nodes requiring semantics inspection beyond standard assertions, `SemanticsProperties` and `getOrNull` from `androidx.compose.ui.semantics` are imported directly (see `CopyPagesScreenTest.kt`).

## 5. Build Configuration for `androidUnitTest`

**Source set location:** `kmp/src/androidUnitTest/kotlin/`

**Key Robolectric configuration:** No `robolectric.properties` file is needed for most tests. SDK is defaulted automatically. If SDK pinning is needed, use `@Config(sdk = [33])` at class or method level.

**`FakeRepositories.kt` visibility:** It lives in `jvmTest` — not directly available to `androidUnitTest` via standard Kotlin/Gradle source-set dependency rules. Existing `androidUnitTest` Compose tests that need repository fakes either:
- Inline minimal fakes (e.g. lambda-based service stubs passed as constructor params)
- Reference the `FakeRepositories` indirectly if the `jvmTest` source set is on the androidUnitTest compile classpath (verify with `./gradlew :kmp:testDebugUnitTest --dry-run` before assuming)

In practice, the screens targeted here (`CopyPagesScreen`, `ConflictResolutionScreen`, `AllPagesScreen`, etc.) are largely stateless composables that accept `State` data classes and action callbacks — so `FakeRepositories` is typically **not needed**; tests construct state objects inline and capture callbacks via lambdas.

**Test runner command:**
```bash
./gradlew :kmp:testDebugUnitTest
# single class:
./gradlew :kmp:testDebugUnitTest --tests 'dev.stapler.stelekit.ui.CopyPagesScreenTest'
```

No X11 display needed — Robolectric is fully headless.
