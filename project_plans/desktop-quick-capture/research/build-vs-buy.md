# Build vs. Buy: Desktop Quick Capture

Research for each OS-integration surface in `requirements.md`: is there a maintained
library / OS-native feature to adopt, or does it need bespoke code.

## Repo grounding

Existing packaging, confirmed by reading the repo directly:

- Desktop packaging is `jpackage` via Compose Desktop's `nativeDistributions` block
  (`kmp/build.gradle.kts:1010-1039`), targeting Dmg, Msi, Deb, Rpm.
- Linux distribution is an AppImage built by wrapping the jpackage app-image output
  with `appimagetool` (`.github/workflows/release.yml:258-317`) — not the Deb/Rpm
  jpackage produces directly. Homebrew (`Formula/stelekit.rb`) installs that AppImage
  and ships a hand-written `.desktop` file; there is no `nautilus-python` or
  `fdroid`-style Linux desktop-integration scaffolding beyond that.
- macOS packaging is jpackage's `.dmg`, **ad-hoc signed but not notarized**
  (`Casks/stelekit.rb:23-28` caveat). No Xcode project, no provisioning profile, no
  Share Extension target exists anywhere in the repo.
- Windows packaging is jpackage's `.msi` (built via WiX under the hood); no custom
  WiX authoring or registry customization exists today.
- No existing global-hotkey, system-tray, or single-instance/socket code in
  `kmp/src/jvmMain` — this would be new for all three.

## 1. Global hotkey capture on JVM

| Option | License | Last push | Verdict |
|---|---|---|---|
| [JNativeHook](https://github.com/kwhat/jnativehook) | LGPL + linking exception ("other" per `gh repo view`) | Latest **release** 2.2.2 is 2022-03-18; repo last **code push** 2024-09-03 ([`gh repo view kwhat/jnativehook`](https://github.com/kwhat/jnativehook)) | Viable, aging |
| [JKeymaster](https://github.com/tulskiy/jkeymaster) (JNA-based) | LGPL-3.0 | Last push 2025-06-17 — more recently touched than JNativeHook | Viable |

Neither library's README mentions Wayland (confirmed: grepped JNativeHook's
README.md via the GitHub API for "wayland" — zero hits). Both are X11-only for
Linux; under Wayland compositors they either silently fail or (for JNativeHook,
which uses XGrabKey-style APIs) don't intercept keys unless the compositor
provides an XWayland-compatible input path.

**Wayland workaround — XDG `GlobalShortcuts` portal**: exists but adoption is
partial as of 2025-2026:
- KDE Plasma: full support.
- Hyprland: full support (own portal implementation).
- GNOME (the majority Linux desktop): **the portal route is currently a no-op**
  on GNOME — tracked as an open feature gap.
- wlroots-generic compositors (`xdg-desktop-portal-wlr`, used by Sway and others
  that don't ship their own portal): **no `GlobalShortcuts` implementation at
  all** — a bind attempt fails outright.

Neither JNativeHook nor JKeymaster implement the portal — both predate it and
require raw D-Bus calls (`org.freedesktop.portal.GlobalShortcuts`) written by
hand on top of them for any Wayland coverage at all, and even then GNOME users
get nothing.

**Verdict: Viable, not clearly recommended.** JKeymaster is the fresher of the
two (LGPL-3.0, June 2025 push) and is a thinner JNA wrapper, so it's the
better base if a library is adopted — but it only covers X11/Windows/macOS.
Given GNOME's no-op status, plan on the global-hotkey popup being an
X11/Windows/macOS-only feature in v1, with Linux Wayland users falling back to
the Nautilus script surface (item 5) — do not treat the hotkey popup as the
one universal capture path the requirements doc's "key architectural fact"
implies for Linux.

## 2. macOS custom URL scheme vs. Share Extension

**Confirmed prior art**: Logseq's own Electron app already ships exactly this
pattern — `logseq://x-callback-url/quickCapture?content=...&url=...`,
registered via Electron's `protocol`/`CFBundleURLTypes` mechanism, no Share
Extension. A JVM app has the equivalent hook: adding a `CFBundleURLTypes` entry
to the `Info.plist` that `jpackage`/Compose Desktop's `macOS { }` block
generates, then handling the incoming URL via the AWT/java.desktop
`Desktop.setOpenURIHandler()` API (standard since JDK 9, no native code).

Known pitfall to design around: [logseq/logseq#6185](https://github.com/logseq/logseq/issues/6185)
— quick capture invoked from a terminal (`xdg-open logseq://...`) works, but
the same URL from a browser bookmarklet crashes with `ERR_INVALID_URL` inside
Electron's `second-instance` handler, because the URL argument arrives in a
different argv shape than the direct-invocation path. This is exactly the
single-instance hand-off problem in item 3 — the lesson is to write one
robust URL-parsing/dispatch path shared by both "already running" and
"cold start" cases, not two.

| Option | Effort | Fit |
|---|---|---|
| Custom URL scheme (`stelekit://capture?text=...`) | Low — `Info.plist` entry + `Desktop.setOpenURIHandler()`, no Xcode target, works with the existing ad-hoc-signed jpackage `.dmg` | Covers "any process that can open a URL" (browser bookmarklet, Shortcuts.app, Raycast, terminal) but **not** the macOS Share sheet itself |
| True Share Extension | High — requires an Xcode extension target bundled inside the `.app`, an App Group for IPC with the main (non-sandboxed JVM) process, and in practice notarization to avoid Gatekeeper friction on the extension | Only way to appear in the native macOS Share sheet / system Quick Note surface |

**Verdict: Recommended (URL scheme) for v1.** It is a 1-2 day effort matching
the "no external API needed"/in-process framing of the requirements doc's
Linux hotkey option, reuses the same enrichment pipeline entry point as the
in-process hotkey popup, and has a working reference implementation to study.
Real Share Extension is **Viable but deferred** — flag it explicitly as
follow-up scope since it requires Xcode tooling this Gradle/Bazel repo doesn't
have today, plus notarization (the repo is currently ad-hoc-signed only, a
change of its own).

## 3. Local IPC / single-instance detection

No dedicated Kotlin/JVM library stands out as widely adopted for this; the
JVM desktop ecosystem's own convention (confirmed via Kotlin Slack threads
surfaced in search — `slack-chats.kotlinlang.org/t/32704761` and `/t/16660259`)
is: **don't use `ServerSocket` on a fixed port** (trips the Windows firewall
prompt on first run) — use a **lock file** for the "is another instance
running" check, then a small local mechanism (loopback socket on an
OS-picked/ephemeral port recorded in the lock file, or a named pipe on
Windows) purely for the message hand-off once you know an instance exists.

- `net.harawata:appdirs` solves a different problem (locating
  `~/.local/share`/`%APPDATA%`/`~/Library/Application Support` paths) — useful
  for locating the lock file, not for the IPC itself.
- Electron's `app.requestSingleInstanceLock()` + `second-instance` event is the
  closest analogous "solved problem" outside the JVM; the logseq#6185 bug above
  shows the failure mode to avoid (argv/URL parsing must be identical whether
  invoked cold or handed off to a running instance).

**Verdict: Not recommended to adopt a library** — the surface area (one lock
file + one loopback `ServerSocket`/named-pipe read-a-line-write-a-line
protocol) is small enough that a library adds a dependency without saving
meaningful code, and none of the candidates are purpose-built for this. Write
it bespoke, but explicitly model it on Electron's lock-then-notify shape and
give it one shared parsing path per item 2's lesson.

## 4. Windows context-menu registration

`jpackage`'s `--win-menu`/`--win-shortcut` flags only manage Start Menu/desktop
shortcuts — they do not expose any hook for File Explorer context-menu
(`IContextMenu`) registration. Under the hood jpackage's MSI target already
requires and invokes WiX (`light.exe`/`candle.exe`) — confirmed via Oracle's
`jpackage` docs — so the natural extension point is **authoring a WiX
fragment/custom action** (a small registry-key `Component` under
`HKCR\*\shell\SendToSteleKit`) that Compose Desktop's Gradle plugin doesn't
generate but can be merged in, since jpackage supports `--resource-dir`
overrides of its generated WiX sources.

Modern Microsoft guidance steers packaged apps toward MSIX + manifest-declared
`FileExplorerContextMenus`, but that's a different, heavier packaging model
this repo doesn't use (jpackage produces classic MSI, not MSIX) — not
applicable here.

**Verdict: Not recommended to adopt a library.** No JVM-friendly abstraction
exists over this; it's inherently a WiX/registry authoring task regardless of
tooling. Bespoke WiX fragment (a few dozen lines of XML) merged into the
jpackage-generated installer via `--resource-dir`, not a hand-rolled `.reg`
file (which wouldn't uninstall cleanly) and not a native stub binary (the
context-menu verb can just shell out to `stelekit.exe --capture` directly).

## 5. Linux Nautilus integration

| Option | Maintenance burden | Extra deps beyond current packaging |
|---|---|---|
| Plain script in `~/.local/share/nautilus/scripts/` | Low — one shell script, Nautilus reads `NAUTILUS_SCRIPT_SELECTED_FILE_PATHS` env var and runs it | None — the Homebrew Formula already writes files into `~/.local/share/...` (icons, `.desktop` entry) on Linux post-install, so dropping a script into the sibling `nautilus/scripts/` dir is the same mechanism, same install hook |
| `nautilus-python` extension | High — separate Python package dependency (`nautilus-python` GObject bindings), a `.py` file with Nautilus's menu-provider API, packaging/versioning that tracks GNOME's own Python/GI bindings | New system dependency not currently required by this Kotlin/JVM project at all |

**Verdict: Recommended (plain script).** For a solo maintainer, the shell
script is strictly lower-maintenance and fits the existing install hook
(`post_install` in `Formula/stelekit.rb:58-105`) that already places
Linux-only files under `~/.local/share/...`. It only handles the
right-click-in-Nautilus case (not other file managers' "send to" menus), but
that matches the requirements doc's stated v1 scope. `nautilus-python` is
**Viable but not recommended** — reserve it only if the script's lack of a
custom icon/submenu ever becomes a real complaint.

## 6. Prior art to reference (not copy)

- **Logseq** (this project's own predecessor, AGPL-3.0 — fully inspectable):
  the `logseq://x-callback-url/quickCapture` URL-scheme pattern (item 2) and
  its Electron `second-instance` pitfall (logseq#6185, item 3) are the most
  directly transferable references found. AGPL covers Logseq's *expression*
  (its Electron/TypeScript code), not the *pattern* of "register a URL scheme,
  parse query params into a capture payload" — safe to reimplement the idea in
  Kotlin/JVM without triggering AGPL obligations, since no Logseq code is
  copied or linked against.
- **Obsidian**: ships an equivalent `obsidian://new?...` URL scheme and a
  global-hotkey "Quick capture" community plugin, but Obsidian's core app is
  closed-source (confirmed: commercial license required for business use) —
  only the externally observable URL contract can be referenced, not any
  implementation.

## Summary of verdicts

| # | Area | Verdict |
|---|---|---|
| 1 | Global hotkey (JVM) | Viable, not clearly recommended — JKeymaster over JNativeHook if adopted; Wayland/GNOME has no fix |
| 2 | macOS Quick Note / Share | Recommended: custom URL scheme now; true Share Extension deferred |
| 3 | Local IPC | Not recommended to adopt a library — bespoke lock file + loopback socket |
| 4 | Windows context menu | Not recommended to adopt a library — bespoke WiX fragment via `--resource-dir` |
| 5 | Linux Nautilus | Recommended: plain script in `~/.local/share/nautilus/scripts/` |
| 6 | Fork/adapt | Logseq's own AGPL Electron app is the best pattern reference (URL scheme + single-instance pitfall); Obsidian only as an external-contract reference |
