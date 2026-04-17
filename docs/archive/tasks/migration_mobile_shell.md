# Migration Plan: Mobile App Shell

## 1. Discovery & Requirements
The Mobile Shell is the native container (Android/iOS) that hosts the KMP logic and UI.

### Existing Artifacts
- `src/main/mobile`: Existing mobile specific code (Capacitor/Cordova based).
- `src/main/mobile/deeplink.cljs`: Deep link handling.

### Functional Requirements
- **Entry Point**: Launch the app, initialize DB, load UI.
- **Deep Links**: Handle `logseq://` URLs to open specific graphs/pages.
- **Sharing**: Receive text/images from other apps (Share Intent).
- **Permissions**: Request File/Camera/Notification permissions.

### Non-Functional Requirements
- **Startup Time**: Cold start < 2s.
- **Lifecycle**: Handle background/foreground transitions (save state on pause).
- **Safe Area**: Respect notch/dynamic island on iOS/Android.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **AppLifecycleObserver**: React to foreground/background events.
- **DeepLinkHandler**: Parse URLs and navigate.
- **ShareHandler**: Process incoming shared content.

### Platform Layer (Native)
- **Android**: `MainActivity` (Kotlin).
    - Configures `WindowCompat` for edge-to-edge.
    - Handles `Intent.ACTION_SEND`.
- **iOS**: `iOSApp.swift`.
    - Configures `ComposeUIViewController`.
    - Handles `onOpenURL`.

### UI Layer (Compose Multiplatform)
- **Component**: `AppScaffold` (Handles Safe Area insets).
- **Component**: `ShareSheet` (UI for processing shared text).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 UX: Keyboard Handling [SEVERITY: High]
- **Description**: On mobile, the virtual keyboard overlaps the editor.
- **Mitigation**: Use `WindowInsets.ime` in Compose to automatically pad the content. Test on both Android and iOS (behavior differs).

### 🐛 Logic: Process Death [SEVERITY: Medium]
- **Description**: Android OS kills background apps. When restored, the app must reload the last state.
- **Mitigation**: Save "Current Graph" and "Current Page" to `SavedStateHandle` or persistent prefs. Restore on launch.

## 4. Implementation Roadmap

### Phase 1: Skeleton
- [ ] Create Android `MainActivity` and iOS `ContentView`.
- [ ] Set up Compose entry point.
- [ ] Implement Edge-to-Edge (Safe Area) handling.

### Phase 2: Intents & Links
- [ ] Implement Deep Link parsing.
- [ ] Implement "Share to Logseq" intent filter.

### Phase 3: Lifecycle
- [ ] Implement Auto-Save on `onPause`.
- [ ] Implement State Restoration.

## 5. Migration Checklist
- [ ] **UI**: App launches and renders full screen.
- [ ] **Logic**: Deep links open the correct page.
- [ ] **Logic**: Sharing text from another app creates a block.
- [ ] **UX**: Keyboard does not hide the cursor.

