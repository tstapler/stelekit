# Mobile Development (KMP & Compose Multiplatform)

Logseq is currently migrating to a **Kotlin Multiplatform (KMP)** and **Compose Multiplatform** architecture. This is the modern way to develop for Logseq mobile.

## Prerequisites

- **Java Development Kit (JDK) 21**: Required for Kotlin 2.0.
- **Android Studio**: Latest version (Ladybug or newer recommended).
- **Android SDK**: API Level 35 (Compile SDK) and 24+ (Min SDK).
- **Xcode (macOS only)**: For iOS development.
- **CocoaPods (macOS only)**: For iOS dependency management.

---

## Android Development (KMP)

The Android application is located in the `android/` directory and integrates the shared `:kmp` module.

### 1. Build and Install
You can use the provided helper script from the project root:

```bash
./run-android.sh
```

Or run the Gradle task directly:

```bash
cd android
./gradlew :app:installDebug
```

### 2. Run with ADB
Ensure your device is connected and recognized:

```bash
adb devices
```

If the app doesn't launch automatically, you can start it via ADB:

```bash
adb shell monkey -p dev.stapler.logseq.app -c android.intent.category.LAUNCHER 1
```

### 4. Wireless Debugging
If you prefer not to use a USB cable, you can use Android Wireless Debugging:

1.  **Enable Wireless Debugging**: In your phone's **Developer Options**, turn on **Wireless Debugging**.
2.  **Pair Device**:
    - Tap **Wireless Debugging > Pair device with pairing code**.
    - From your terminal, run:
      ```bash
      adb pair <IP_ADDRESS>:<PAIRING_PORT> <PAIRING_CODE>
      ```
3.  **Connect**:
    - Look at the **main Wireless Debugging screen** for the **IP address & Port** (this is usually different from the pairing port).
    - Run:
      ```bash
      adb connect <IP_ADDRESS>:<CONNECTION_PORT>
      ```
4.  **Run Build**:
    - If multiple devices are connected, you can specify your device ID:
      ```bash
      export ADB_DEVICE_ID="192.168.1.70:45015"
      ./run-android.sh
      ```

---

## Technical Note: Hybrid Architecture
The current mobile app is in a **hybrid state**. It uses **Capacitor/Cordova** for native shell features and some legacy UI, but it integrates the **`:kmp` module** for shared business logic, data models, and the new **Compose Multiplatform** editor.

### Key Implementation Details (Fixed during Migration):
1.  **Context Initialization**: The `:kmp` module (specifically `DriverFactory` and `PlatformUtils`) must be initialized with an Android `Context` before use to avoid `NullPointerException`. This is handled in `ComposeHost.kt` via `DriverFactory().init(context)`.
2.  **SQLite & FTS5**: Standard Android SQLite does not always bundle the `fts5` module required by Logseq. We use `com.github.requery:sqlite-android` via `RequerySQLiteOpenHelperFactory` in `DriverFactory.android.kt` to provide a modern SQLite with FTS5 support.
3.  **Directory Picking**: Android requires using the Storage Access Framework (SAF) for directory selection. `PlatformFileSystem` provides a `pickDirectoryAsync()` method which is implemented in `ComposeHost.kt` by delegating to `MainActivity`'s SAF intent.

---

## iOS Development (KMP)

The iOS application is located in `ios/App/`. It is currently a hybrid app transitioning to KMP.

### 1. Setup
```bash
cd ios/App
pod install
```

### 2. Build and Run
1. Open `ios/App/App.xcworkspace` in Xcode.
2. Select your device/simulator.
3. Click **Run** (Cmd + R).

*Note: The iOS UI is currently transitioning to Compose Multiplatform. Shared logic is already powered by the `:kmp` module.*

---

## Legacy Mobile Development (ClojureScript & Capacitor)

> [!WARNING]
> This workflow is for the older ClojureScript/Capacitor-based version of the app. New features should be developed in the KMP module.

### Android (Legacy)
- Run `yarn && yarn app-watch` from the root.
- Run `npx cap sync android` in another terminal.
- Run `npx cap run android` or use Android Studio.

### iOS (Legacy)
- Run `yarn && yarn app-watch` from the root.
- Run `npx cap sync ios` in another terminal.
- Run `npx cap open ios` to open in Xcode.
