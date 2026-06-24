# Paranoid Mode — UX Design

## Status: Draft
## Platforms: Desktop JVM (primary) + Web/WASM (noted where different)
## Requirements: `../requirements.md`
## Plan: `../implementation/plan.md`

---

## 1. Surface Inventory

| # | Surface | Trigger |
|---|---------|---------|
| S1 | Vault Unlock Dialog | Opening a paranoid-mode graph |
| S2 | Hidden Volume Unlock | Keyboard shortcut on Unlock Dialog |
| S3 | Paranoid Mode Creation Flow | "New Graph" → "Enable paranoid mode" checkbox |
| S4 | Vault Settings Screen | Settings → Graph → Paranoid Mode |
| S5 | Auto-Lock Settings Panel | Within Vault Settings |
| S6 | Error States | Wrong passphrase, corrupted vault, locked-while-using |
| S7 | Hidden Activation Gesture | Tap version number 5× in Settings → About |

---

## 2. Surface Designs

---

### S1 — Vault Unlock Dialog

**Trigger:** User opens a paranoid-mode graph directory. `GraphManager` detects `.stele-vault` and emits `VaultState.Locked`. The app immediately shows this dialog instead of loading the graph.

#### Wireframe

```
┌─────────────────────────────────────────────┐
│                                             │
│  🔒  Unlock Graph                           │
│      my-notes                               │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│  Passphrase                                 │
│  ┌─────────────────────────────────────┐    │
│  │ ••••••••••••••••••              👁  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ☐  Use key file                            │
│                                             │
│  (key file picker appears when checked)     │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│          [ Cancel ]   [ Unlock Graph ]      │
│                                             │
└─────────────────────────────────────────────┘
```

**When "Use key file" is checked:**

```
┌─────────────────────────────────────────────┐
│                                             │
│  🔒  Unlock Graph                           │
│      my-notes                               │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│  Passphrase                                 │
│  ┌─────────────────────────────────────┐    │
│  │ ••••••••••••••••••              👁  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ☑  Use key file                            │
│     ┌───────────────────────┐  [ Browse ]   │
│     │ /path/to/secret.key   │               │
│     └───────────────────────┘               │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│          [ Cancel ]   [ Unlock Graph ]      │
│                                             │
└─────────────────────────────────────────────┘
```

**During derivation (Argon2id running):**

```
┌─────────────────────────────────────────────┐
│                                             │
│  🔒  Unlock Graph                           │
│      my-notes                               │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│  Deriving key…                              │
│  ████████████████░░░░░░░░░░░░░░░░░░░░░░░░   │
│  This may take a few seconds.               │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│          [ Cancel ]   [ Unlock Graph ]      │
│                       (disabled)            │
└─────────────────────────────────────────────┘
```

#### Interaction Flow

| Step | User Action | System Response |
|------|-------------|-----------------|
| 1 | Opens a paranoid-mode graph | Unlock dialog appears; passphrase field has focus |
| 2 | Types passphrase | Characters masked; show/hide toggle (👁) available |
| 3 | (Optional) checks "Use key file" | Key file path field and Browse button appear |
| 4 | (Optional) clicks Browse | Native OS file picker opens; selection populates path field |
| 5 | Clicks "Unlock Graph" or presses Enter | Button disables; progress bar and "Deriving key…" label appear; Cancel remains active |
| 6a | Argon2id succeeds | Dialog dismisses; graph loads normally |
| 6b | Argon2id fails (wrong credentials) | Error state shown (see S6-E1); fields cleared; user can retry |
| 7 | Clicks Cancel at any point | Graph is not loaded; app returns to graph picker or last open graph |

#### Edge Cases

- **OS Keychain provider registered:** A third option appears below the key-file toggle: "Use saved credential (OS Keychain)" — clicking it skips passphrase entry and triggers keychain lookup directly. If keychain lookup fails, falls back to passphrase + error message.
- **Multiple providers registered:** The dialog always shows passphrase first. Any registered provider can unlock; the user does not need to know which slot their passphrase maps to.
- **Key file path invalid:** Inline error shown below the path field: "File not found. Please choose a valid key file." Unlock button remains disabled.
- **Cancel during derivation:** Zero-fills the passphrase `CharArray` and key material in memory immediately; progress bar dismisses; dialog returns to input state.

#### WASM Differences

- File picker uses browser `<input type="file">` — OS picker is not available on all browsers; dragging a file into the key-file area is also supported.
- OS Keychain provider is labelled "Browser saved credential" and uses `SubtleCrypto` non-extractable key stored in IndexedDB.

---

### S2 — Hidden Volume Unlock

**Trigger:** User presses `Ctrl+Shift+H` (desktop) or `Ctrl+Shift+H` (WASM) on the Vault Unlock Dialog. This is the only entry point — there is no visible button or menu item.

**Design principle:** The hidden volume must have zero UI indication of its existence to an observer who does not know the shortcut. The shortcut is documented only in in-app Help under an unrelated heading and in the user's own knowledge.

#### Wireframe (after shortcut activation)

```
┌─────────────────────────────────────────────┐
│                                             │
│  🔒  Unlock Graph                           │
│      my-notes                               │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│  Passphrase                                 │
│  ┌─────────────────────────────────────┐    │
│  │ ••••••••••••••••••              👁  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ☐  Use key file                            │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│          [ Cancel ]   [ Unlock Graph ]      │
│                                             │
└─────────────────────────────────────────────┘
```

The dialog is visually identical to S1 — no "hidden mode" label, no indicator. The title "Unlock Graph" does not change. Internally, the unlock attempt targets the hidden-volume keyslot namespace instead of the outer namespace.

#### Interaction Flow

| Step | User Action | System Response |
|------|-------------|-----------------|
| 1 | Opens paranoid-mode graph; Unlock Dialog shown | As per S1 |
| 2 | Presses `Ctrl+Shift+H` | No visible UI change; internal flag set to attempt hidden-namespace unlock |
| 3 | Enters hidden-volume passphrase | As per S1 |
| 4 | Clicks "Unlock Graph" | Argon2id runs against hidden-namespace keyslots (slots 4–7); progress shown as per S1 |
| 5a | Hidden namespace unlock succeeds | Hidden graph loads; app looks identical to outer graph experience |
| 5b | Passphrase doesn't match hidden namespace | Error shown as per S6-E1 (same generic message as wrong outer passphrase — no indication which namespace was tried) |

#### Edge Cases

- **Shortcut pressed again after first press:** Toggles back to outer namespace attempt. No UI change.
- **Tab order / screen reader:** The hidden shortcut is never announced by screen readers. The dialog's accessible name is always "Unlock Graph" regardless of which namespace will be attempted.
- **Wrong passphrase for hidden volume:** Error message is identical to wrong passphrase for outer volume — no distinguishing text.

#### WASM Differences

- Same keyboard shortcut (`Ctrl+Shift+H`). Browser may intercept in some configurations; a fallback shortcut (`Alt+Shift+H`) is supported.

---

### S3 — Paranoid Mode Creation Flow

**Trigger:** User opens "New Graph" dialog and checks "Enable paranoid mode."

#### Phase A — New Graph Dialog with Paranoid Mode toggle

```
┌─────────────────────────────────────────────┐
│  New Graph                                  │
│                                             │
│  Graph name                                 │
│  ┌─────────────────────────────────────┐    │
│  │ My Secure Notes                     │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  Location                                   │
│  ┌───────────────────────┐  [ Browse ]      │
│  │ ~/Documents/          │                  │
│  └───────────────────────┘                  │
│                                             │
│  ☑  Enable paranoid mode                    │
│     Encrypt all files in this graph.        │
│     Learn more ↗                            │
│                                             │
│          [ Cancel ]   [ Next → ]            │
│                                             │
└─────────────────────────────────────────────┘
```

#### Phase B — Passphrase Setup

```
┌─────────────────────────────────────────────┐
│  Set Up Encryption                          │
│  Step 1 of 2: Choose a passphrase           │
│                                             │
│  Choose a strong passphrase. You will need  │
│  this to open the graph on any device.      │
│                                             │
│  Passphrase                                 │
│  ┌─────────────────────────────────────┐    │
│  │ ••••••••••••••••••              👁  │    │
│  └─────────────────────────────────────┘    │
│  Strength: ████████░░ Good                  │
│                                             │
│  Confirm passphrase                         │
│  ┌─────────────────────────────────────┐    │
│  │ ••••••••••••••••••              👁  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ─────────────────────────────────────────  │
│  ⚠  If you lose this passphrase, your       │
│     notes cannot be recovered.              │
│  ─────────────────────────────────────────  │
│                                             │
│          [ ← Back ]   [ Next → ]           │
│                                             │
└─────────────────────────────────────────────┘
```

#### Phase C — Security Profile (Argon2id parameters)

```
┌─────────────────────────────────────────────┐
│  Set Up Encryption                          │
│  Step 2 of 2: Unlock speed vs. security     │
│                                             │
│  Choose how much work your device does to   │
│  verify the passphrase. Slower = harder     │
│  for attackers.                             │
│                                             │
│  ○  Fast      ~0.5 s  (64 MiB, 2 iter)     │
│               Suitable for very low-end     │
│               hardware or browser use.      │
│                                             │
│  ●  Standard  ~1.5 s  (128 MiB, 3 iter)    │
│               Recommended for most users.  │
│                                             │
│  ○  Strong    ~3 s    (256 MiB, 4 iter)    │
│               For high-security needs.     │
│                                             │
│  Measured on your device right now.         │
│                                             │
│          [ ← Back ]   [ Create Graph ]     │
│                                             │
└─────────────────────────────────────────────┘
```

**During vault creation (key generation + first write):**

```
┌─────────────────────────────────────────────┐
│  Creating encrypted graph…                  │
│                                             │
│  ✓  Generating encryption key               │
│  ✓  Writing vault header                    │
│  ○  Preparing graph directory               │
│                                             │
└─────────────────────────────────────────────┘
```

#### Interaction Flow

| Step | User Action | System Response |
|------|-------------|-----------------|
| 1 | Opens "New Graph" dialog | Standard new graph form shown |
| 2 | Checks "Enable paranoid mode" | Checkbox checked; "Next" button activates |
| 3 | Clicks "Next" | Phase B (passphrase setup) shown |
| 4 | Enters passphrase | Strength indicator updates in real time |
| 5 | Enters confirm passphrase | If mismatch, "Passphrases do not match" inline error shown; Next disabled |
| 6 | Passphrases match; clicks "Next" | Phase C (security profile) shown; benchmark runs in background and updates timing labels |
| 7 | Selects security profile | Times update if benchmark just completed |
| 8 | Clicks "Create Graph" | Creation progress shown (checklist animation); on completion, graph opens normally |

#### Edge Cases

- **Passphrase too short (< 8 characters):** Inline warning: "Use a longer passphrase for better security." Next button is not blocked (user autonomy), but warning persists.
- **Benchmark takes > 2 s:** Security profile page shows "Measuring your device…" spinner next to timing labels until complete.
- **Creation fails (disk full, permissions):** Error dialog: "Graph could not be created. [reason]. Please choose a different location." Returns to Phase A with location field focused.
- **User clicks Back from Phase B:** Returns to Phase A with "Enable paranoid mode" still checked.
- **User unchecks paranoid mode after seeing Phase B:** Unchecking from Phase A clears any entered passphrase from memory immediately.

#### WASM Differences

- "Location" in Phase A becomes "Browser storage" with no path picker; graphs are stored in Origin-Private File System (OPFS).
- OS Keychain option is not offered during creation flow (can be added later in Vault Settings).
- Security profile timing labels are measured in-browser (Argon2id WASM port).

---

### S4 — Vault Settings Screen

**Trigger:** Settings → select a paranoid-mode graph → "Paranoid Mode" section.

#### Wireframe

```
┌─────────────────────────────────────────────────────────────────┐
│  Graph Settings — my-notes                                      │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  PARANOID MODE                                           │   │
│  │  Status:  Enabled  ✓                                     │   │
│  │  Algorithm:  ChaCha20-Poly1305                           │   │
│  │  Format version:  1                                      │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  UNLOCK PROVIDERS                                        │   │
│  │                                                          │   │
│  │  ⚠  You have only 1 provider. Add another to avoid       │   │
│  │     permanent lockout if you lose it.                    │   │
│  │                                                          │   │
│  │  ┌────────────────────────────────────────────────────┐  │   │
│  │  │ 🔑  Passphrase          Added 2025-06-01  [Remove] │  │   │
│  │  └────────────────────────────────────────────────────┘  │   │
│  │                                                          │   │
│  │  [ + Add provider ]                                      │   │
│  │                                                          │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  KEY ROTATION                                            │   │
│  │                                                          │   │
│  │  Change passphrase — rewraps the key without             │   │
│  │  re-encrypting files.                       [ Change ]   │   │
│  │                                                          │   │
│  │  Re-encrypt all files — generates a new key and          │   │
│  │  re-encrypts every file. Takes several minutes.          │   │
│  │                               [ Re-encrypt… ]            │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  AUTO-LOCK                                               │   │
│  │  Lock graph after idle for:                              │   │
│  │  ● Never   ○ 5 min   ○ 15 min   ○ 30 min   ○ 60 min     │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**With 2+ providers (warning hidden):**

```
│  UNLOCK PROVIDERS                                               │
│                                                                 │
│  ┌────────────────────────────────────────────────────┐        │
│  │ 🔑  Passphrase          Added 2025-06-01  [Remove] │        │
│  └────────────────────────────────────────────────────┘        │
│  ┌────────────────────────────────────────────────────┐        │
│  │ 📄  Key file: secret.key  Added 2025-06-10  [Remove]│       │
│  └────────────────────────────────────────────────────┘        │
│  ┌────────────────────────────────────────────────────┐        │
│  │ 🖥  OS Keychain         Added 2025-06-10  [Remove] │        │
│  └────────────────────────────────────────────────────┘        │
│                                                                 │
│  [ + Add provider ]                                             │
```

#### Add Provider Flow (sheet/dialog)

```
┌─────────────────────────────────────┐
│  Add Unlock Provider                │
│                                     │
│  ● Passphrase                       │
│    Enter a new passphrase to unlock │
│    this graph.                      │
│                                     │
│  ○ Key file                         │
│    A file whose contents act as the │
│    unlock credential.               │
│                                     │
│  ○ OS Keychain                      │
│    Unlock automatically on this     │
│    device using saved credentials.  │
│                                     │
│       [ Cancel ]    [ Continue → ]  │
└─────────────────────────────────────┘
```

**After selecting Passphrase → Continue:**

```
┌─────────────────────────────────────┐
│  Add Passphrase Provider            │
│                                     │
│  New passphrase                     │
│  ┌───────────────────────────────┐  │
│  │ ••••••••••••••••          👁  │  │
│  └───────────────────────────────┘  │
│  Strength: ██████████ Strong        │
│                                     │
│  Confirm                            │
│  ┌───────────────────────────────┐  │
│  │ ••••••••••••••••          👁  │  │
│  └───────────────────────────────┘  │
│                                     │
│       [ Cancel ]    [ Add ]         │
└─────────────────────────────────────┘
```

**After selecting Key file → Continue:**

```
┌─────────────────────────────────────┐
│  Add Key File Provider              │
│                                     │
│  Key file                           │
│  ┌───────────────────┐  [ Browse ]  │
│  │                   │              │
│  └───────────────────┘              │
│                                     │
│  ⓘ  Any file may be used. Its      │
│     contents will be hashed to      │
│     derive the unlock key. Do not   │
│     modify or delete this file.     │
│                                     │
│       [ Cancel ]    [ Add ]         │
└─────────────────────────────────────┘
```

**After selecting OS Keychain → Continue (auto-adds, shows result):**

```
┌─────────────────────────────────────┐
│  OS Keychain Provider               │
│                                     │
│  ✓  Credential saved to OS keychain │
│     on this device.                 │
│                                     │
│  ⓘ  If the keychain entry is lost,  │
│     use your passphrase or key file │
│     to regain access.               │
│                                     │
│                         [ Done ]    │
└─────────────────────────────────────┘
```

#### Remove Provider Flow

Clicking [Remove] on a provider row:

```
┌─────────────────────────────────────┐
│  Remove Provider?                   │
│                                     │
│  Passphrase (added 2025-06-01)      │
│                                     │
│  This provider will no longer be    │
│  able to unlock this graph.         │
│  Your other providers are           │
│  unaffected.                        │
│                                     │
│   [ Cancel ]    [ Remove Provider ] │
└─────────────────────────────────────┘
```

**Blocking removal of the last non-keychain provider:**

```
┌─────────────────────────────────────┐
│  Cannot Remove Provider             │
│                                     │
│  You must keep at least one         │
│  passphrase or key-file provider.   │
│  The OS Keychain provider alone is  │
│  not sufficient — keychain entries  │
│  can be lost.                       │
│                                     │
│  Add another passphrase or key file │
│  before removing this one.          │
│                                     │
│                         [ OK ]      │
└─────────────────────────────────────┘
```

#### Key Rotation Flows

**Change passphrase (provider rotation):**

```
┌─────────────────────────────────────┐
│  Change Passphrase                  │
│                                     │
│  Current passphrase                 │
│  ┌───────────────────────────────┐  │
│  │ ••••••••••••••••          👁  │  │
│  └───────────────────────────────┘  │
│                                     │
│  New passphrase                     │
│  ┌───────────────────────────────┐  │
│  │ ••••••••••••••••          👁  │  │
│  └───────────────────────────────┘  │
│  Strength: ████████░░ Good          │
│                                     │
│  Confirm new passphrase             │
│  ┌───────────────────────────────┐  │
│  │ ••••••••••••••••          👁  │  │
│  └───────────────────────────────┘  │
│                                     │
│   [ Cancel ]  [ Change Passphrase ] │
└─────────────────────────────────────┘
```

**Re-encrypt all files (DEK rotation — confirmation required):**

```
┌─────────────────────────────────────┐
│  Re-encrypt All Files?              │
│                                     │
│  This generates a new encryption    │
│  key and re-encrypts every file.    │
│                                     │
│  ⚠  This may take several minutes   │
│     depending on graph size.        │
│     Do not close the app during     │
│     this operation.                 │
│                                     │
│  [ Cancel ]  [ Start Re-encryption ]│
└─────────────────────────────────────┘
```

**During re-encryption:**

```
┌─────────────────────────────────────┐
│  Re-encrypting…                     │
│                                     │
│  ████████████░░░░░░░░░░░░░░░░       │
│  142 / 380 files                    │
│                                     │
│  Do not close this window.          │
│                                     │
└─────────────────────────────────────┘
```

#### Interaction Flow (Settings Screen)

| Action | System Response |
|--------|-----------------|
| Open settings for a paranoid-mode graph | Paranoid Mode section shows status, algorithm, version |
| Click "+ Add provider" | Provider-type sheet opens |
| Select provider type and fill details | Provider added to list; confirmation shown |
| Click [Remove] on a provider with 2+ providers | Confirmation dialog; on confirm, provider removed |
| Click [Remove] on the last non-keychain provider | Blocking dialog explaining minimum requirement |
| Click [Change] under Key Rotation | Change passphrase dialog; requires current passphrase |
| Click [Re-encrypt…] under Key Rotation | Warning confirmation → progress screen |
| Change auto-lock setting | Setting saved immediately; no confirmation needed |

#### WASM Differences

- "OS Keychain" provider labelled "Browser saved credential."
- Browser keychain warning: "If you clear browser data, this credential will be deleted." Shown inline in provider list.
- Key file uses browser file picker; path not shown (only filename).

---

### S5 — Auto-Lock Settings Panel

This panel is embedded within the Vault Settings Screen (S4) rather than a standalone surface.

#### Wireframe

```
┌─────────────────────────────────────────────────────────────────┐
│  AUTO-LOCK                                                      │
│                                                                 │
│  Lock graph after idle for:                                     │
│                                                                 │
│  ● Never     No auto-lock. Graph stays unlocked until you       │
│              manually lock or close it.                         │
│                                                                 │
│  ○ 5 min     Locks after 5 minutes without editing or typing.   │
│                                                                 │
│  ○ 15 min                                                       │
│                                                                 │
│  ○ 30 min                                                       │
│                                                                 │
│  ○ 60 min                                                       │
│                                                                 │
│  Lock Graph Now                              [ Lock Now ]       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Interaction Flow

| Action | System Response |
|--------|-----------------|
| Select a radio option | Setting saved immediately |
| No user input for the selected duration | Graph locks; Vault Unlock Dialog shown (S1) |
| Click "Lock Now" | Graph locks immediately; Unlock Dialog shown |
| Any key press or pointer move | Idle timer resets |

**Auto-lock trigger UX:** When the timer fires, any unsaved edits are written to disk (encrypted) before locking. No data loss on auto-lock.

#### WASM Differences

- Page visibility change (tab hidden) resets the idle timer to 0 and can optionally trigger an immediate lock (add a separate toggle: "Lock when tab is hidden").

---

### S6 — Error States

#### S6-E1: Wrong Passphrase / Invalid Credential

```
┌─────────────────────────────────────────────┐
│                                             │
│  🔒  Unlock Graph                           │
│      my-notes                               │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│  Passphrase                                 │
│  ┌─────────────────────────────────────┐    │
│  │                                 👁  │    │  ← cleared after failure
│  └─────────────────────────────────────┘    │
│  ✗  Incorrect passphrase or key file.       │
│     Please try again.                       │
│                                             │
│  ─────────────────────────────────────────  │
│                                             │
│          [ Cancel ]   [ Unlock Graph ]      │
│                                             │
└─────────────────────────────────────────────┘
```

- Error message: "Incorrect passphrase or key file. Please try again."
- Passphrase field is cleared and refocused.
- No attempt counter shown (Argon2id is the rate limiter).
- Cancel button always visible and active.
- No distinction between wrong passphrase vs. wrong key file — same message for both.

#### S6-E2: Corrupted Vault Header

```
┌─────────────────────────────────────────────┐
│                                             │
│  ⚠  Cannot Open Graph                       │
│                                             │
│  The vault header for "my-notes" is         │
│  corrupted or has been tampered with.       │
│                                             │
│  The graph cannot be opened until the       │
│  header is repaired. If you have a backup,  │
│  you can restore the .stele-vault file.     │
│                                             │
│  [ Open Folder… ]         [ Close ]         │
│                                             │
└─────────────────────────────────────────────┘
```

- "Open Folder…" opens the graph directory in the OS file manager, so the user can locate and restore `.stele-vault`.
- "Close" dismisses and returns to graph picker.
- No retry possible — corruption must be fixed externally.

#### S6-E3: File Decryption Failure (Corrupted File)

```
┌─────────────────────────────────────────────┐
│  ⚠  Could Not Load Page                     │
│                                             │
│  "My Note" could not be decrypted.          │
│  The file may be corrupted.                 │
│                                             │
│  Other pages are not affected.              │
│                                             │
│              [ Open Graph Anyway ]          │
│                                             │
└─────────────────────────────────────────────┘
```

- Shown when a specific `.stek` file fails AEAD authentication.
- Non-blocking: the user can continue using the graph; the affected page shows an error placeholder.
- "Open Graph Anyway" loads all other pages normally; the corrupted page shows a "This page could not be decrypted" inline notice.

#### S6-E4: Unsupported Vault Format Version

```
┌─────────────────────────────────────────────┐
│  ⚠  Vault Format Not Supported              │
│                                             │
│  "my-notes" uses vault format version 3,   │
│  but this version of SteleKit supports      │
│  up to version 1.                           │
│                                             │
│  Please update SteleKit to open this graph. │
│                                             │
│  [ Check for Updates ]      [ Close ]       │
│                                             │
└─────────────────────────────────────────────┘
```

- "Check for Updates" opens the app's update mechanism or the release page.
- "Close" dismisses; no retry.

#### S6-E5: Graph Locked While In Use (Auto-lock fires)

The auto-lock timer fires while the user is reading (not editing) a page:

```
╔═════════════════════════════════════════════╗
║                                             ║
║  🔒  Graph Locked                           ║
║      my-notes has been locked after         ║
║      inactivity.                            ║
║                                             ║
║          [ Unlock Graph ]                   ║
║                                             ║
╚═════════════════════════════════════════════╝
```

- Full-screen overlay (not a dismissable dialog) prevents reading content while locked.
- "Unlock Graph" opens S1 Vault Unlock Dialog inline.
- In-memory DEK is already zeroed; no content visible behind overlay.

#### S6-E6: OS Keychain Access Failure

```
┌─────────────────────────────────────────────┐
│  ⚠  Keychain Access Failed                  │
│                                             │
│  SteleKit could not access the saved        │
│  credential from the OS keychain.           │
│                                             │
│  You can unlock the graph with your         │
│  passphrase or key file instead.            │
│                                             │
│  [ Use Passphrase ]       [ Cancel ]        │
│                                             │
└─────────────────────────────────────────────┘
```

- Shown when OS keychain lookup fails (e.g., permission revoked, keychain locked).
- "Use Passphrase" opens S1 Vault Unlock Dialog.
- Cancel dismisses; graph is not loaded.

#### S6-E7: Hidden Volume Capacity Warning (90% full)

Shown in the app's notification area (not a blocking dialog) when the user is working in the hidden graph:

```
┌─────────────────────────────────────────────┐
│  ⚠  Hidden volume nearly full               │
│     Reserve space: 90% used.               │
│     No further writes will be possible      │
│     when full.               [ Dismiss ]    │
└─────────────────────────────────────────────┘
```

- Non-blocking banner at the top of the editor.
- "Dismiss" hides the banner for the session; it reappears on next launch if still at ≥ 90%.

---

## 3. UX Acceptance Criteria

### General / Cross-Surface

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| AC-G1 | The normal editing UX (typing, linking, formatting) is unchanged after a graph is unlocked. | Open a paranoid-mode graph, unlock it, write and read back 10 pages — editing experience matches a non-encrypted graph. |
| AC-G2 | The term "paranoid mode" does not appear in any dialog title or visible UI text; use "encrypted" or "encryption" instead. | Visual review of all surfaces. |
| AC-G3 | Every error state has at least one exit path (button or keyboard shortcut) that does not dead-end. | Step through each error state; confirm at least one actionable element exists. |
| AC-G4 | All interactive elements are reachable via Tab key alone, in logical order. | Tab through each surface without mouse; confirm every control receives focus. |
| AC-G5 | All interactive elements have visible focus rings. | Tab through each surface; confirm no element has invisible focus state. |
| AC-G6 | All form fields have accessible labels (not just placeholder text). | Inspect with a screen reader; each field announces its label before its type. |
| AC-G7 | Passphrase fields use `CharArray`-backed input — system clipboard does not contain the passphrase after typing. | After typing a passphrase and pressing Unlock, inspect clipboard; it must be empty or contain unrelated content. |

### S1 — Vault Unlock Dialog

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| AC-S1-1 | User can unlock a graph using passphrase alone in ≤ 3 interactions: type passphrase → press Enter (or click Unlock). | Count interactions from dialog open to graph loaded. |
| AC-S1-2 | User can unlock using passphrase + key file in ≤ 5 interactions: check toggle → browse/select file → type passphrase → press Enter. | Count interactions. |
| AC-S1-3 | Progress indicator appears within 100 ms of clicking Unlock. | Time from click to progress bar visibility. |
| AC-S1-4 | Cancel is always active — clicking Cancel at any point (including during derivation) returns to graph picker or prior state without error. | Click Cancel before and during derivation; confirm clean return. |
| AC-S1-5 | Passphrase field receives focus automatically when dialog opens. | Open a paranoid-mode graph; confirm cursor is in passphrase field without clicking. |
| AC-S1-6 | Show/hide passphrase toggle (👁) works; visible passphrase reverts to masked on next focus-out. | Click toggle; verify chars shown; click elsewhere; verify masked. |
| AC-S1-7 | Dialog is keyboard-navigable: Tab reaches all controls; Enter submits when focus is on Unlock button or in passphrase field. | Navigate via keyboard only; submit with Enter. |

### S2 — Hidden Volume Unlock

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| AC-S2-1 | The hidden volume shortcut (`Ctrl+Shift+H`) does not change any visible UI element. | Press shortcut; confirm no text, label, or indicator changes. |
| AC-S2-2 | Entering the hidden-volume passphrase and unlocking loads different content than the outer passphrase. | Unlock with outer passphrase → note page list. Lock. Activate shortcut → unlock with hidden passphrase → confirm different page list. |
| AC-S2-3 | An incorrect hidden-volume passphrase shows the same error message as an incorrect outer passphrase — no indication which namespace was tried. | Try wrong passphrase in hidden mode; compare error text to wrong passphrase in normal mode — must be identical. |
| AC-S2-4 | A screen reader or accessibility tree inspection reveals no text containing "hidden", "inner", "outer", or "secondary" on the unlock dialog when the shortcut has been activated. | Inspect accessibility tree after pressing shortcut. |

### S3 — Paranoid Mode Creation Flow

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| AC-S3-1 | User can create a paranoid-mode graph in ≤ 10 interactions: name → location → check toggle → Next → enter passphrase × 2 → Next → select profile → Create. | Count interactions from "New Graph" click to graph open. |
| AC-S3-2 | "Next" button in Phase B (passphrase setup) is disabled until both passphrase fields match. | Enter mismatched passphrases; confirm Next is disabled. |
| AC-S3-3 | Inline mismatch error appears within 500 ms of both fields being non-empty and different. | Type into both fields with deliberate mismatch; time error appearance. |
| AC-S3-4 | Passphrase strength indicator updates on each keystroke. | Type progressively longer passphrases; confirm indicator updates. |
| AC-S3-5 | Argon2id timing labels in Phase C reflect measurements taken on the actual device, not hardcoded defaults. | Create vault on two machines with different performance; confirm timing labels differ. |
| AC-S3-6 | Clicking Back from Phase B returns to Phase A with "Enable paranoid mode" still checked and no passphrase retained. | Navigate back; confirm checkbox state and confirm passphrase field is empty. |
| AC-S3-7 | If graph creation fails (disk error), the user is shown the failure reason and returned to Phase A — no partial graph is left on disk. | Simulate disk-full condition; confirm error and clean state. |

### S4 — Vault Settings Screen

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| AC-S4-1 | Warning banner "You have only 1 provider" is visible when exactly 1 provider is registered. | Open vault settings with 1 provider; confirm banner is shown. |
| AC-S4-2 | Warning banner disappears when a second provider is added. | Add a second provider; confirm banner disappears without page reload. |
| AC-S4-3 | User can add a new passphrase provider in ≤ 6 interactions: click "+ Add" → select Passphrase → Continue → enter passphrase × 2 → Add. | Count interactions. |
| AC-S4-4 | User can add a key-file provider in ≤ 5 interactions: click "+ Add" → select Key file → Continue → Browse and select file → Add. | Count interactions. |
| AC-S4-5 | Removing the last non-keychain provider is blocked with an explanatory error — not silently ignored. | Attempt to remove the only passphrase with only OS keychain remaining; confirm blocking dialog. |
| AC-S4-6 | Remove confirmation dialog names the specific provider being removed. | Click Remove on a provider; confirm dialog text identifies it. |
| AC-S4-7 | "Change passphrase" flow requires the current passphrase; an incorrect current passphrase shows an error and does not change anything. | Enter wrong current passphrase; confirm error and no change. |
| AC-S4-8 | "Re-encrypt all files" shows a progress indicator with file count and does not close or allow navigation away until complete. | Start re-encryption; attempt to navigate; confirm blocked. |
| AC-S4-9 | After re-encryption, the graph can be unlocked with the same passphrase as before. | Complete re-encryption; lock and re-unlock with same passphrase. |

### S5 — Auto-Lock Settings

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| AC-S5-1 | Auto-lock setting takes effect immediately without requiring a Save button. | Change from "Never" to "5 min"; close and reopen settings; confirm 5 min is selected. |
| AC-S5-2 | "Lock Now" locks the graph and shows the Unlock Dialog within 1 second. | Click Lock Now; measure time to Unlock Dialog appearance. |
| AC-S5-3 | Auto-lock fires within 10 seconds of the configured idle timeout expiring (not before). | Set to 5 min; wait 5 min + 5 s; confirm lock occurred. |
| AC-S5-4 | Any key press or pointer move resets the idle timer (confirmed by not locking while actively using the app). | Configure 5 min; interact continuously for 6 min; confirm no lock. |
| AC-S5-5 | If an edit is in progress when auto-lock fires, the edit is saved to disk (encrypted) before locking — no data loss. | Edit a page; wait for auto-lock; unlock; confirm edit persists. |

### S6 — Error States

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| AC-S6-1 | Wrong passphrase error message is: "Incorrect passphrase or key file. Please try again." — exact text. | Enter wrong passphrase; read error text. |
| AC-S6-2 | After a wrong passphrase, the passphrase field is cleared and focused; the key-file path (if set) is retained. | Enter wrong passphrase; confirm field state. |
| AC-S6-3 | Corrupted vault header error offers "Open Folder" to allow manual recovery — this is not a dead end. | Corrupt `.stele-vault` header; confirm Open Folder button present and functional. |
| AC-S6-4 | A single corrupted `.stek` file does not prevent the rest of the graph from loading. | Corrupt one file's bytes; open graph; confirm other pages load. |
| AC-S6-5 | Unsupported format version error names the version number found in the vault and the maximum version supported. | Open a vault with version=99; confirm error text contains both numbers. |
| AC-S6-6 | Auto-lock overlay is non-dismissable by clicking outside or pressing Escape — the user must unlock via S1. | Trigger auto-lock; attempt Escape and outside click; confirm overlay persists. |
| AC-S6-7 | OS keychain failure error offers "Use Passphrase" as a fallback — this is not a dead end. | Revoke keychain permission; attempt to open graph; confirm fallback button. |
| AC-S6-8 | Hidden volume capacity warning at ≥ 90% is non-blocking — the user can dismiss it and continue working. | Fill hidden volume to 91%; confirm banner is dismissable and editing continues. |

---

## 4. Accessibility Summary

| Requirement | Coverage |
|-------------|---------|
| Keyboard navigation | All surfaces Tab-navigable; Enter submits focused form; Escape cancels dismissable dialogs |
| Screen reader labels | All fields have `contentDescription`/`semantics { }` labels; toggle states announced; progress bars labelled with action text |
| Focus management | Dialog open → focus moves to first field; dialog close → focus returns to triggering element |
| Error announcement | Inline errors are in the same semantic group as their field; announced live on appearance |
| Hidden volume stealth | No accessible text exposes "hidden", "outer", or "secondary" concepts anywhere |
| Color independence | Error states use text labels in addition to color (red text is accompanied by ✗ icon and descriptive message) |
| Minimum touch target | All buttons ≥ 48 dp × 48 dp (relevant for WASM on touch-screen devices) |

---

## 5. WASM-Specific Differences Summary

| Surface | Desktop JVM | Web/WASM |
|---------|-------------|---------|
| File picker | Native OS file dialog | Browser `<input type="file">` or drag-drop |
| OS Keychain provider label | "OS Keychain" | "Browser saved credential" |
| OS Keychain warning | None (platform is reliable) | "If you clear browser data, this credential will be deleted." |
| Graph storage location | User-chosen directory path | Browser OPFS (no path shown to user) |
| Argon2id progress | Blocking spinner on `Dispatchers.Default` | JS main thread blocks during `crypto_pwhash`; show "Deriving key…" label (no animated spinner — JS is single-threaded unless Web Worker is used) |
| Hidden volume shortcut | `Ctrl+Shift+H` | `Ctrl+Shift+H` with `Alt+Shift+H` fallback |
| Auto-lock on tab hidden | Not applicable | Optional "Lock when tab is hidden" toggle in S5 |

---

## 6. Open UX Questions (Flagged for Product Decision)

| ID | Question | Default Assumption |
|----|----------|--------------------|
| UXQ-1 | Should "Enable paranoid mode" be a checkbox in New Graph, or a separate "Create Encrypted Graph" button? | Checkbox in New Graph (lower friction, discoverable) |
| UXQ-2 | Should hidden volume creation be in Vault Settings or a completely separate hidden flow? | Completely separate; no UI entry point other than documentation. |
| UXQ-3 | Is a passphrase strength indicator (Phase B) appropriate UX or does it give false confidence? | Include it; it is informational, not a gate. |
| UXQ-4 | Should the unlock dialog show the graph name ("Unlock Graph — my-notes") or just "Unlock Graph"? | Show graph name for clarity when multiple graphs are used. |
| UXQ-5 | Should auto-lock warn the user with a countdown (e.g., "Locking in 60 s…")? | No countdown for v1 (adds complexity; Argon2id cost is the primary protection). |

---

### S7 — Hidden Activation Gesture ("Low-key Mode")

**Trigger:** User taps/clicks the version number in Settings → About exactly 5 times within 3 seconds.

**Rationale:** Paranoid Mode must not be visible in the primary navigation — the feature's own existence should be deniable. The Android developer-mode pattern (tap build number 7×) is well-understood by power users who would seek out encryption features.

**Wireframe — Settings → About (mobile)**

```
┌─────────────────────────────────┐
│  ← About                         │
│                                   │
│  [SteleKit Logo]                  │
│                                   │
│  SteleKit                         │
│  Version 1.4.2  ◄── tap target   │
│       ↕ min 44 dp touch area      │
│                                   │
│  Open source · License · GitHub   │
└─────────────────────────────────┘

After 2nd tap (label fades in, auto-hides after 2 s):
┌─────────────────────────────────┐
│  Version 1.4.2                    │
│  3 more taps to unlock Low-key Mode │  ← 12sp muted text, no animation
└─────────────────────────────────┘

After 4th tap:
│  1 more tap to unlock Low-key Mode  │

After 5th tap → bottom sheet slides up:
┌─────────────────────────────────┐
│                                   │
│  ▬▬▬  (drag handle)               │
│                                   │
│  Low-key Mode                     │
│                                   │
│  Enable encrypted graphs with     │
│  plausible deniability. Your      │
│  notes stay ciphertext on disk    │
│  at all times.                    │
│                                   │
│  [ Enable ]     [ Not now ]       │
│                                   │
└─────────────────────────────────┘
```

**Interaction flow:**

| Step | User action | System response |
|------|------------|-----------------|
| 1 | Tap version (1×) | No visible response — timer starts (3 s window) |
| 2 | Tap version (2×) | Muted label: "3 more taps to unlock Low-key Mode" (auto-hides 2 s) |
| 3 | Tap version (3×) | Label: "2 more taps…" |
| 4 | Tap version (4×) | Label: "1 more tap…" |
| 5 | Tap version (5×) | Label disappears; bottom sheet / modal appears immediately |
| — | Timer expires (< 5 taps) | Count resets silently; label disappears |
| — | User taps "Enable" | Navigate to Vault Creation Wizard (FR-3 / S3) |
| — | User taps "Not now" | Sheet dismisses; count resets; no trace in UI |

**Desktop variant:** identical but uses mouse clicks; the bottom sheet becomes a centered modal dialog with the same content and buttons.

**WASM variant:** identical to mobile; no keyboard shortcut exists for this path.

**Once Low-key Mode is ever activated:** A "Low-key Mode" row appears in Settings → Graph for that specific graph only (per FR-9.4). The tap sequence remains available to create additional encrypted graphs.

**UX Acceptance Criteria — S7:**

| AC | Criterion |
|----|-----------|
| UX-S7-1 | Tapping the version number 5 times within 3 s on a mobile device shows the activation bottom sheet without requiring a keyboard. |
| UX-S7-2 | Tapping only 4 times within 3 s shows no activation sheet; the count resets silently. |
| UX-S7-3 | The countdown label ("N more taps…") never appears before the 2nd tap; the 5th tap produces no label (sheet appears directly). |
| UX-S7-4 | Tapping "Not now" leaves the Settings → About screen unchanged — no "Low-key Mode" entry visible anywhere. |
| UX-S7-5 | After enabling and creating one encrypted graph, Settings → Graph for that graph shows "Low-key Mode" settings; Settings → Graph for a non-encrypted graph shows nothing. |
| UX-S7-6 | The version number tap target is at least 44 × 44 dp on all platforms. |
| UX-S7-7 | No label, button, tooltip, or screen-reader hint on Settings → About reveals the existence of encryption before the 2nd tap of the sequence. |
