# Research Plan: Image/File Attachment — KMP Pitfalls

## Objective
Identify failure modes, tricky edge cases, and known pitfalls for implementing image/file
attachment in Kotlin Multiplatform + Compose Multiplatform, covering the 7 subtopics
requested.

## Subtopics and Search Strategy

### 1. Android SAF (Storage Access Framework)
**File**: findings-android-saf.md
**Axes**: permissions model, URI types, Coil 3 compatibility, persistable URI grants
**Searches (cap 4)**:
- "Android 10+ file access permissions SAF ACTION_OPEN_DOCUMENT content URI"
- "Android persistable URI permissions takePersistableUriPermission"
- "Coil 3 content URI Android custom fetcher"
- "Android content:// vs file:// URI differences Kotlin"

### 2. Coil 3 Custom Fetcher Pitfalls
**File**: findings-coil3-fetcher.md
**Axes**: memory management, caching layers, thread safety, error handling
**Searches (cap 4)**:
- "Coil 3 custom Fetcher implementation pitfalls"
- "Coil 3 ImageLoader keyer fetcher memory cache"
- "Coil 3 custom fetcher OkioSource ByteReadChannel"
- "Coil 3 fetcher disk cache key thread safety"

### 3. Image Loading Performance (many large images)
**File**: findings-image-perf.md
**Axes**: memory pressure, thumbnail generation, ContentScale, sampling
**Searches (cap 3)**:
- "Compose image loading performance many large images LazyColumn"
- "Coil 3 sampling size ContentScale lazy list performance"
- "Compose Multiplatform image memory pressure OOM large images"

### 4. File Copy Race Conditions / Unique Filename
**File**: findings-file-copy-race.md
**Axes**: atomicity, uniqueness, platform file API, Kotlin coroutines
**Searches (cap 3)**:
- "Kotlin file copy atomic unique filename race condition"
- "Kotlin multiplatform expect actual file system unique name"
- "Java NIO atomic file move ATOMIC_MOVE KMP"

### 5. Compose Drag-and-Drop Stability (Desktop JVM)
**File**: findings-drag-drop.md
**Axes**: API stability, CMP version requirement, AWT interop, known bugs
**Searches (cap 4)**:
- "Compose Multiplatform onExternalDrag Desktop JVM stability"
- "Compose Multiplatform drag drop file Desktop experimental"
- "compose-multiplatform DragAndDrop AWT DropTarget Desktop"
- "compose multiplatform 1.6 drag and drop file API"

### 6. iOS File Picker (UIImagePickerController vs PHPickerViewController)
**File**: findings-ios-picker.md
**Axes**: deprecation status, Kotlin/Native accessibility, multi-select, permissions
**Searches (cap 4)**:
- "PHPickerViewController vs UIImagePickerController iOS deprecated"
- "Kotlin Native UIImagePickerController PHPickerViewController interop"
- "Compose Multiplatform iOS image picker UIViewControllerRepresentable"
- "KMP iOS file picker PHPickerViewController callback Kotlin"

### 7. WASM / Web File Input and Coil ByteArray
**File**: findings-wasm-web.md
**Axes**: security restrictions, WASM file API, Coil WASM support, browser sandbox
**Searches (cap 4)**:
- "Kotlin WASM web file input security restrictions browser"
- "Coil 3 WASM ByteArray image loading web"
- "Compose Multiplatform WASM file picker FileReader API"
- "Kotlin JS WASM input type file Blob ByteArray"

## Execution Order
All 7 subtopics researched in parallel using training knowledge, then parent runs web
searches and appends results.

## Output
- 7 `findings-<subtopic>.md` files
- 1 `pitfalls.md` synthesizing top risks
