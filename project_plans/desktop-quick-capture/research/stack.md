# Research: Technology stack for Desktop Quick Capture (OS-level)

**Project**: desktop-quick-capture (caff12c9-008e-4ff1-a2de-ccf0b6ff8232)

## Codebase baseline (verified by reading the repo)

- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt:36-153` — desktop entry point.
  Uses `androidx.compose.ui.window.application { Window(...) { MenuBar { ... } } }`. No
  `Tray`/`SystemTray` composable, no global-hotkey code, no socket/HTTP server, no
  single-instance guard of any kind today.
- `kmp/build.gradle.kts:1010-1039` — `compose.desktop { application { nativeDistributions {
  targetFormats(Dmg, Msi, Deb, Rpm) ... } } }`. This is the JetBrains Compose Gradle plugin,
  which wraps `jpackage` under the hood — confirms jpackage is already the packaging
  mechanism for macOS/Windows/Linux, so any native helper/launcher work builds on top of an
  existing jpackage pipeline rather than introducing a new one.
- `kmp/build.gradle.kts:23` — `jvmToolchain(21)`, so JEP-380 Unix-domain-socket channel APIs
  (`java.nio.channels.SocketChannel`/`ServerSocketChannel` with `UnixDomainSocketAddress`,
  stable since JDK 16) are available with zero new dependencies.
- `kmp/build.gradle.kts:117-371` — only a Ktor **client** (`ktor-client-core` etc., pulled in
  transitively for `coil-network-ktor3`) is on the classpath. No `ktor-server-*` artifact
  exists anywhere in the build. Adding an HTTP listener means adding a new dependency, not
  reusing one that's already there.
- Write path to reuse (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:53-118`,
  called from `CaptureActivity.kt:68-112`): resolve `GraphManager.getActiveRepositorySet()` →
  `repoSet.journalService.ensureTodayJournal()` → build a `Block` (UUIDv7 position via
  `FractionalIndexing.generateKeyBetween`) → `repoSet.writeActor.saveBlock(newBlock)` (falls
  back to direct `@OptIn(DirectRepositoryWrite)` write if no actor) → construct
  `GraphWriter(fileSystem, writeActor = repoSet.writeActor)` and call `writer.savePage(page,
  existingBlocks + newBlock, graphPath)` to flush Markdown to disk. This logic is
  Android-`ViewModel`-shaped but platform-agnostic underneath (`GraphManager`, `JournalService`,
  `DatabaseWriteActor`, `GraphWriter` all live in `commonMain`/`jvmMain`), so an in-process
  desktop hotkey popup can call the same sequence directly — this matches the requirement's
  "Key architectural fact."

## 1. Global hotkey registration on JVM/Compose Desktop

**Compose Desktop has no built-in global-hotkey API.** `Window`-level `onKeyEvent`/
`onPreviewKeyEvent` only fire while the window has focus; there is no OS-level
register-a-shortcut-that-works-when-unfocused primitive in JetBrains' Compose for Desktop.
This is a tracked, unresolved feature request
([compose-multiplatform#389](https://github.com/JetBrains/compose-multiplatform/issues/389),
open since 2021, still open as of 2025 search results) — confirms the gap is real, not a
research gap on my end.

Library options, in order of fit for this project:

| Library | Platform coverage | Mechanism | Status (verified) |
|---|---|---|---|
| **JNativeHook** (`com.github.kwhat:jnativehook`) | Win/macOS/Linux (X11) | JNI global keyboard/mouse hook | Latest release **2.2.2, tagged 2022-03-18**. [GitHub releases](https://github.com/kwhat/jnativehook/releases). No newer tag found. Widely used, but effectively unmaintained (3+ years stale) — accept as a dependency risk, not a "current" recommendation. |
| **JHotKeys** (`dstjacques/JHotKeys`) | Windows + Linux (wraps JIntellitype + JXGrabKey) | JNI, per-OS backend | Small wrapper project; no macOS backend at all — would still need JNativeHook or a macOS-specific Carbon/Cocoa shim for that platform. |
| **JIntellitype** (melloware fork, `com.melloware:jintellitype`) | **Windows only** | JNI, registers `RegisterHotKey` | Actively maintained fork — GitHub tags show **1.5.6** as latest vs. 1.3.9 on some mirrors; treat GitHub as source of truth. Good if you need a Windows-only fallback. |
| **JXGrabKey** | **Linux (X11) only** | JNI, X11 `XGrabKey` | SourceForge-hosted, low visible recent activity. |

**Recommendation**: JNativeHook is the only single library covering all three desktop OSes,
which matches this project's actual need (one Compose Desktop app targeting macOS/Linux/
Windows from one codebase). Its staleness is a real risk — pin the version, smoke-test on
each target OS as part of CI/release (this repo already has `TargetFormat.Dmg/Msi/Deb/Rpm`
release packaging, so a manual hotkey smoke test per platform per release is a reasonable
gate), and design the hotkey-capture code behind a small internal interface so it can be
swapped without touching call sites if JNativeHook becomes unusable (e.g., signing/notarization
issues on newer macOS, or a JDK version bump breaking the native glue).

**System tray** (adjacent, not strictly asked but relevant to a hotkey-popup UX — e.g. showing
a tray icon with "capture" as a fallback trigger): Compose Desktop ships an experimental
`@Composable Tray` API wrapping `java.awt.SystemTray`, with known HDPI/CJK-font issues on
Windows. Community alternative **ComposeNativeTray**
(`io.github.kdroidfilter:composenativetray-jvm`, latest **1.3.3** per Maven Central, actively
tagged through 2025) fixes those issues and covers Mac/Linux/Windows. Not required for hotkey
registration itself, but worth adopting alongside if the popup needs a persistent tray icon.

## 2. Single-instance detection / IPC for a second short-lived process

The requirement doc's own triage is correct: a Nautilus script, Windows context-menu handler,
or (theoretically) a macOS helper are **separate OS processes** with no in-process access to a
running SteleKit JVM. They need some hand-off mechanism, plus a cold-start story.

Options, cheapest first:

1. **Unix domain socket (macOS/Linux) / named pipe (Windows), JEP-380** —
   `java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX)` bound to a
   well-known path (e.g. `$XDG_RUNTIME_DIR/stelekit.sock` or `~/.stelekit/stelekit.sock`), JDK
   16+ stable API, **zero new dependencies** given this project's `jvmToolchain(21)`. On
   Windows there's no AF_UNIX-over-JNIO equivalent as clean as on POSIX; the idiomatic
   cross-platform building block is `com.github.kwhat`-style JNI or, simpler, just use a TCP
   socket bound to `127.0.0.1` on all three platforms (loses the "no network stack" purity
   but is trivial, dependency-free, and adequate for a same-machine-only capture handoff — the
   risk of another local process/port squatting is the same class of risk a lock file has).
   `scalacenter/ipcsocket` (Scala Center, MIT) wraps native Unix-socket/named-pipe JNI for JVMs
   that need true AF_UNIX everywhere including Windows named pipes, if that purity matters
   later.
2. **Lock file + file-watch** — simplest possible cold-start detector (does a PID file exist
   and is that PID alive?), but needs a *second* mechanism to actually deliver the captured
   text once you know an instance is running (a drop file the running instance's existing
   `PlatformFileSystem`/file-watcher machinery picks up — this repo already has a
   `GraphLoader.externalFileChanges` watcher pattern for external Markdown edits, so a
   capture-inbox directory watched the same way is architecturally consistent, at the cost of
   fs-event latency vs. a socket's immediacy).
3. **Minimal internal HTTP endpoint (not the general REST API #232)** — a single
   `POST /capture` on `127.0.0.1:<ephemeral-or-fixed-port>`, using either raw
   `com.sun.net.httpserver.HttpServer` (JDK built-in, zero dependency, sufficient for one
   route) or, if the project wants something more idiomatic to the rest of the codebase's
   Ktor usage, `ktor-server-cio`/`ktor-server-netty` (new dependency — currently absent). This
   *is* a reasonable narrow substitute for the out-of-scope general REST API, provided it's
   scoped to exactly one capture-write endpoint, bound to loopback only, and not exposed as a
   general query/read API. The JDK's built-in `HttpServer` is the lower-risk choice: no new
   dependency, no risk of accidentally growing into "the REST API" by dependency creep.

**Recommendation**: Unix domain socket (POSIX) is the cleanest, dependency-free mechanism for
macOS/Linux; for Windows, either fall back to loopback TCP (simplest, consistent code path
across all three OSes) or accept the added `ipcsocket`-style dependency for true named-pipe
parity. Either way, the "socket bind" *is* the single-instance detector: on startup the
already-running app owns the socket/port; the second process's bind attempt fails, meaning
"an instance is running" and "here is who to talk to" are the same check. Cold-start (no
instance running): the launcher/script should attempt the connection with a short timeout,
and on failure fall back to spawning `stelekit --capture <text>` as a full cold start that
performs the capture write and exits (or launches the full UI) — this needs a small CLI-arg
capture mode in `Main.kt`, separate from the always-open Compose window path.

## 3. macOS Share Extension / Services menu from a Kotlin/JVM app

Confirmed via Electron precedent (a JVM/Compose Desktop app faces the identical constraint to
Electron here — no JVM-hosted framework can register a macOS App Extension bundle, because
`NSExtension`/`XPC` extension bundles must be genuine Mach-O bundles conforming to Apple's
extension point protocol, loaded by the extension host process, not by an arbitrary runtime):

- Electron has the same limitation — years-old open feature requests
  ([electron#31984](https://github.com/electron/electron/issues/31984),
  [electron#17702](https://github.com/electron/electron/issues/17702)) with no native
  solution; the practical answers in that ecosystem are either (a) ship a separate small
  **native Swift helper app** as its own `.appex` bundle inside the `.app`'s
  `Contents/PlugIns/`, wired up at build/notarization time outside the JVM/Electron packaging
  tool, or (b) skip Share Extension and use the **Services menu** instead, which is a much
  lower bar.
- **Services menu is the realistic v1 target, not Share Extension.** A macOS Service is
  declared via an `NSServices` array in the app's `Info.plist` (`NSMessage`,
  `NSSendTypes: [NSPasteboardTypeString]`, a menu item title) — no separate extension bundle,
  no XPC host, no Swift code required in the common case where the Service just needs to
  invoke the *same running app* via Apple Event / `open -a`. jpackage (already in this
  project's pipeline via the Compose Gradle plugin) supports macOS `Info.plist` customization
  via `--mac-app-store`/plist-merge options or a custom `Info.plist` template
  (`compose.desktop.application.nativeDistributions.macOS.infoPlist` block in the Compose
  Gradle DSL) — so wiring `NSServices` onto the packaged `.app` is realistic without a native
  Swift helper: the Service handler can be the packaged app itself, launched via
  `open -a SteleKit --args --capture "$selected_text"` (or, better, the app registers a
  custom URL scheme, e.g. `stelekit://capture?text=...`, and the Service invokes that; the
  already-running instance's socket/IPC layer from §2 fields the request whichever way the
  handler reaches it).
- True Share Extension (the item that appears in the Share Sheet across apps, not just
  Services menu) genuinely needs a signed `.appex` — realistic for a solo developer only as a
  stretch goal, not v1. Flag this as a scope note back to planning: the requirements doc says
  "Share Extension / Services-menu entry" as if interchangeable; they are not equal effort,
  and Services menu is the low-effort one that should be v1.

## 4. Windows context-menu registry handler

Standard shape, verified against Microsoft's own docs
([context-menu-handlers.md](https://github.com/MicrosoftDocs/win32/blob/docs/desktop-src/shell/context-menu-handlers.md),
[Registering Your Context Menu Handler](https://learn.microsoft.com/en-us/windows/win32/wpd_sdk/registering-your-context-menu-handler)):

- For a **static, single-command** entry (no dynamic submenu, no icon-per-item logic), the
  lightest-weight approach — and the one that fits "invoke the same capture path," not a full
  COM shell extension — is a plain registry `.reg` entry under
  `HKEY_CLASSES_ROOT\*\shell\SendToSteleKit\command` (for files) or
  `HKEY_CLASSES_ROOT\Directory\Background\shell\...` (for background/folder actions), whose
  `command` value invokes the packaged app's `.exe` (from the same jpackage/Msi build) with
  the selected path(s) as argv, e.g. `"C:\Program Files\SteleKit\stelekit.exe" --capture-file "%1"`.
  This avoids writing a COM `IContextMenu`/`IExplorerCommand` handler DLL entirely — full COM
  shell extensions are the "typical implementation" for handlers needing icons, submenus, or
  per-selection-type logic, but a static reg-key command is sufficient for "one command, fixed
  text."
- Reaching a running instance: identical to §2 — the invoked `.exe` process is the same binary
  as the main app; give it a `--capture-file <path>` / `--capture-text <text>` CLI mode
  (argument parsing added to `Main.kt`) that first tries the local socket/loopback endpoint and,
  only on connection failure, falls back to a full cold start.
- Installer integration: since packaging already goes through jpackage → Msi (Wix-based), the
  `.reg` entries can be added either as post-install actions in a custom WiX fragment (more
  correct, uninstalls cleanly) or written by the app itself on first run (simpler, but leaves
  registry cruft on uninstall unless the app also cleans up). Given this project already
  produces an Msi via the Compose Gradle plugin, a WiX fragment is the properly-scoped
  approach — flag as an implementation-phase decision, not something to resolve in research.

## 5. Linux Nautilus "Send to" / custom action

Two documented mechanisms, confirmed via Nautilus/GNOME docs and community guides
([Fedora Magazine](https://fedoramagazine.org/integrating-scripts-nautilus/),
[GNOME/nautilus-python](https://github.com/GNOME/nautilus-python)):

- **Shell script in `~/.local/share/nautilus/scripts/`** — an executable script appears
  automatically under Nautilus's right-click → Scripts submenu; Nautilus passes selection info
  via env vars (`$NAUTILUS_SCRIPT_SELECTED_FILE_PATHS`, `$NAUTILUS_SCRIPT_SELECTED_URIS`,
  `$NAUTILUS_SCRIPT_CURRENT_URI`). No compiled extension, no Python GObject bindings, no
  packaging beyond dropping a file and `chmod +x`. This matches the requirements doc's own
  framing ("whichever is lowest-effort first") — **this is the lowest-effort option** and
  should be v1.
- **`nautilus-python` extension** (`~/.local/share/nautilus-python/extensions/`) — full
  `Nautilus.MenuProvider`/`Nautilus.LocationWidgetProvider` GObject API: real menu items with
  icons/submenus, background (folder-level) actions, live enable/disable logic. Requires the
  `nautilus-python` package to be installed system-side (a distro package, not something
  SteleKit's own installer controls) — meaningfully higher effort and an extra runtime
  dependency on the user's machine.
- **Recommendation**: ship the shell-script version for v1, matching the "lowest-effort first"
  instruction in requirements.md. The script's entire job is: read
  `$NAUTILUS_SCRIPT_SELECTED_FILE_PATHS` (or the current text-file's content), then invoke the
  same `stelekit --capture-file <path>` CLI entry point as the Windows handler (§4) and the
  cold-start fallback (§2) — meaning the CLI capture-mode work is shared across Linux, Windows,
  and macOS-Services triggers, and only needs to be written once in `Main.kt`.
- Nautilus "Send To" (the `nautilus-sendto` package's protocol) is a separate, largely
  unmaintained subsystem in modern GNOME — the Scripts mechanism above is the current,
  supported integration point and what "Send to" in the requirements doc should map to in
  practice.
- GNOME Files (Nautilus) modern custom-actions alternative worth flagging: `nautilus-actions`
  configuration via `.desktop`-style action files is largely superseded by the Scripts
  mechanism on current GNOME (43+); not worth chasing for v1.

## 6. Version/maintenance summary (as of 2026, VERIFIED where a source is cited)

| Component | Version found | Last visible activity | Verdict |
|---|---|---|---|
| JNativeHook | 2.2.2 | Tagged 2022-03-18 ([releases](https://github.com/kwhat/jnativehook/releases)) | Stale but only cross-platform option; pin + smoke-test per release |
| JIntellitype (melloware fork) | 1.5.6 (GitHub) vs 1.3.9 (some Maven mirrors) | Actively tagged | Windows-only; fine as a fallback/backend, not primary |
| ComposeNativeTray (kdroidFilter) | 1.3.3 | Actively tagged through 2025 | Healthy; adopt alongside hotkey work if a tray icon is wanted |
| JDK Unix domain sockets (JEP-380) | Stable since JDK 16 | N/A — JDK feature | Zero-dependency; project already on JDK 21 toolchain |
| Ktor (client, already in build) | 3.1.3 (pinned in `kmp/build.gradle.kts`) | N/A | No server module present — adding IPC-over-HTTP means a new dependency (`ktor-server-cio`) or the JDK's built-in `com.sun.net.httpserver.HttpServer` (no new dependency, recommended) |
| jpackage (via Compose Gradle plugin) | Whatever the pinned Compose Multiplatform Gradle plugin version resolves (not independently checked — out of scope for this pass, but already producing Dmg/Msi/Deb/Rpm today per `kmp/build.gradle.kts:1014`) | N/A | Confirmed as existing packaging backbone; macOS `Info.plist`/`NSServices` and Windows WiX-fragment registry entries both build on it |

## Open items to carry into planning

1. **JNativeHook staleness is the single biggest technology risk in this feature.** No actively
   maintained cross-platform Java global-hotkey library exists as of this research pass. If a
   hotkey popup is a hard v1 requirement, budget time for signing/entitlement friction on macOS
   (global input monitoring requires Accessibility permission, independent of library choice)
   and for a contingency (Linux/Windows-only hotkey via JIntellitype+JXGrabKey, macOS drops
   hotkey and relies on Services-menu instead).
2. Share Extension vs. Services menu are **not equal-effort** despite being written as
   alternatives in requirements.md — recommend the requirements doc (or the plan phase) be
   explicit that v1 targets Services menu only.
3. The "minimal internal IPC endpoint" question from the research prompt: a single-route
   loopback-only `com.sun.net.httpserver.HttpServer` endpoint is a reasonable narrow substitute
   for the out-of-scope REST API (#232), specifically because it needs no new dependency and
   is trivially scoped to one write operation — recommend this over a general Ktor server
   addition unless #232 lands first and this feature can just depend on it.
