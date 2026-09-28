# UX Research: web-credential-persistence

Agent 5 (UX), SDD Phase 2. Scope: comparable-product patterns, user mental models for browser
credential persistence, accessibility of any new affordance, error/edge-case UX, and
jobs-to-be-done — all grounded against the *existing* `GitSetupScreen`/`LlmProviderSettings` UI
this feature slots into (no new screens).

## 0. What the existing UI already commits to (read first — sets the bar for "done")

- **`GitSetupScreen.kt:1246`** (visible only when `authType == HTTPS_TOKEN` and no saved
  connection is selected, so on every platform including web today):
  > "Token is encrypted on disk using device-specific keys. For stronger protection, use SSH key
  > auth."

  This line is **already shown to web users today** and is currently **false on web** — the PAT
  either goes through the no-op `CredentialStore` (nothing persisted) or through
  `persistWebGitCredentials`'s plaintext-`PlatformSettings` path (`GitSetupScreen.kt:1554-1568`,
  unencrypted `localStorage`). This is not a new copy requirement the feature invents — it is an
  **existing, shipped claim the fix must make true**, or the copy itself needs a platform-specific
  caveat. Flagging this as the single highest-priority UX/trust finding: shipping the encrypted
  wasmJs `CredentialStore` without also verifying this string becomes accurate leaves a
  false-security-claim bug in place even after the backend is fixed.
- **No other platform-specific or persistence-specific copy exists anywhere in either file.**
  Neither `GitSetupScreen` nor `LlmProviderSettings` has a "your token will be saved," "saved to
  this browser," or "remembered" string. There is no checkbox pattern at all — saving is implicit
  in clicking "Save"/completing the wizard. This matters for §2 below: the UI does not currently
  ask the user to opt in to persistence, so the mental-model question is about what "Save" already
  implies, not about a new checkbox's wording.
- **`LlmProviderSettings`'s existing delete-via-blank-and-save pattern** (`LlmProviderSettings.kt:208-214`):
  clearing the API key field and clicking "Save" calls `deleteApiKey`. This is the *only*
  precedent in either file for removing a stored credential — there is no explicit "Forget" /
  "Remove" button anywhere.
- **`GitCredentialConnectionStore.deleteConnection(id)`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitCredentialConnectionStore.kt:79-83`)
  already exists as a method but **has zero UI call sites** in `commonMain` (confirmed via repo-wide
  grep) — a saved git connection can be created and selected from `GitSetupScreen`'s "Saved
  tokens"/"Saved accounts" radio lists (`GitSetupScreen.kt:1192-1319`) but never deleted from the
  UI, on any platform, today. This is a pre-existing gap, not something this project's requirements
  ask to close — but see §3 for why it becomes more visible once web credentials are real and
  durable instead of silently no-op.
- **`CredentialAccess.isAvailable()`** defaults to `true` (`CredentialAccess.kt:23`) and the wasmJs
  stub never overrides it — so today, even though `store`/`retrieve` silently no-op on web, the
  interface reports "available." The only UI consumer of an `isAvailable() == false` signal today
  is `SyncState.CredentialVaultLocked`, surfaced via `SyncStatusBadge.kt:189` (a lock icon + "Vault
  locked — tap to unlock," clickable) and `App.kt:1742` — **at the sync-status-badge level, not at
  the point of credential entry.** Neither `GitSetupScreen`'s "Save" button nor
  `LlmProviderSettings`'s `EditBuiltInProviderKeyDialog` "Save" button has any error/unavailable
  state today. If the new wasmJs actual's `isAvailable()` can legitimately go `false` (e.g.
  `SubtleCrypto` missing), this is new UX surface, not a pre-existing pattern to reuse verbatim —
  see §4.

## 1. Comparable-product UX patterns

**GitHub.com's own PAT entry** (Settings → Developer settings → Personal access tokens): the token
is shown exactly once at creation time, with an explicit "Make sure to copy your personal access
token now. You won't be able to see it again!" warning. GitHub.com itself never asks you to "save"
the token into the browser — persistence is the user's own responsibility (password manager,
notes, etc.). This pattern doesn't directly transfer here (SteleKit *is* the tool consuming the
PAT, not the issuer), but the "shown once, then never displayed in plaintext again" principle is
worth carrying: `GitSetupScreen`'s `OutlinedTextField` + `PasswordVisualTransformation` +
show/hide `IconButton` (`GitSetupScreen.kt:1229-1244`) already does this correctly — masked by
default, explicit reveal action, not automatically shown after a reload.

**VS Code for Web / VS Code Desktop's GitHub auth**: delegates to OAuth device flow by default
(matching SteleKit's own `GITHUB_OAUTH` path) and, when a PAT is entered manually as a fallback,
routes it through VS Code's own Secret Storage API (OS keychain on desktop; an encrypted
browser-backed store on vscode.dev) rather than `settings.json` — explicitly called out in the
VS Code GitHub extension's own security posture as a fix for an earlier plaintext-`settings.json`
mistake (see `sourcery-ai/sourcery-vscode#18`, a near-identical complaint to this project's
`persistWebGitCredentials` plaintext-`localStorage` gap). The transferable lesson: **when a
plaintext workaround for a missing secret store gets noticed, the fix is to route through a real
encrypted store transparently — not to add user-facing warnings about the plaintext state.** This
validates the requirements doc's plan (fix `CredentialStore`, then reconcile/remove
`persistWebGitCredentials`) over alternatives that just add a disclaimer.

**Password managers' browser "Save password?" prompt**: the well-established pattern is a
post-submit toast/banner ("Save password for this site?" with Save/Never/Not now), not a
pre-submit checkbox. SteleKit's existing flow — type token, click Save, it's saved — is actually
closer to the *simpler*, single-provider "always save unless the user explicitly manages/deletes
it later" model (1Password, Bitwarden's own vaults) than to the browser-native prompt-every-time
model, which fits better here: this is the user's own SteleKit git/LLM identity, not a
website-by-website credential the app should ask about repeatedly. No change to the save flow
(no new checkbox) is indicated by this comparison — the existing implicit-save-on-Save-click model
is appropriate and shouldn't be second-guessed by this project.

**GitKraken/Gitea-style web git clients**: both keep credential entry inside a settings/connection
screen nearly identical in shape to `GitSetupScreen`'s existing "Saved tokens" radio-list +
"Use a new token" pattern (pick a saved identity or add a new one). Neither surfaces "encrypted at
rest" copy as prominently as SteleKit's existing `GitSetupScreen.kt:1246` string does — most
web git clients simply don't make an at-rest security claim in the credential UI at all, on the
theory that users can't verify it anyway. This is a mild argument for *softening or scoping*
SteleKit's existing claim (see §5) rather than an argument to add more security copy.

## 2. User mental models — does "Save" mean "survives a reload / new tab / cleared data"?

No checkbox exists today, so the operative question is: what does clicking "Save" in
`GitSetupScreen` or `LlmProviderSettings`'s dialog already imply to a user, and does the fix need
to meet or explicitly correct that expectation?

- **Survives a reload**: yes, unambiguously expected. This is the baseline "saving a setting in an
  app" mental model (cf. every desktop/Android build of SteleKit, where `JvmCredentialStore`/
  `AndroidCredentialStore` already deliver this). A user who re-opens the app and finds their PAT
  gone will read that as a bug, not as an intentional security boundary — this is exactly the
  behavior the requirements doc's Problem Statement describes as broken today.
- **Survives a new tab in the same browser, same profile**: also expected, for the same reason —
  users generally do not distinguish "tab" from "app session" unless the product explicitly signals
  a session boundary (SteleKit's UI gives no such signal). `localStorage`/`IndexedDB` both satisfy
  this (unlike `sessionStorage`, which would violate the expectation and must not be used here).
- **Survives clearing browser data / "Clear browsing data" / a new browser profile**: **not**
  expected to survive, and users generally understand this correctly for browser-stored data —
  this matches the correct technical behavior of both `localStorage` and `IndexedDB` (both are
  wiped by a user-initiated site-data clear) and requires no special UX handling beyond, at most, a
  passive acknowledgment if the product wants one (not required by success metrics). This is
  different from a password manager, where the user's mental model is "my vault is independent of
  this browser" — SteleKit does not claim that, and should not accidentally imply it.
- **Survives switching browsers or devices**: correctly **not** expected — no sync mechanism exists
  or is in scope (`Non-functional Requirements`/`Out of Scope` in requirements.md correctly excludes
  multi-tab/multi-origin sync). No UX work needed here; just don't introduce copy that overclaims
  ("saved to your account") when it's actually "saved to this browser."
- **The one place the existing mental model is currently *wrong* against the code**: `Ephemeral
  Settings Mode` (`PlatformSettings.kt`, referenced in requirements.md Constraints) is presumably a
  "don't persist anything real, this session is temporary" mode. If a user opts into that mode
  expecting nothing to leak into permanent storage, but the new `CredentialStore` shares a storage
  layer with `PlatformSettings` and doesn't also respect the ephemeral gate, the credential
  silently *would* persist against the user's explicit intent — this is the most severe possible
  mental-model violation for this feature (a security-relevant one, not just a convenience one).
  This is already called out as a hard constraint in requirements.md; UX research concurs it's
  correctly scoped as a "must," not a "nice to have."

## 3. Accessibility (WCAG / ARIA / keyboard) — baseline already met, gaps only if new UI is added

**The existing code already follows the correct baseline pattern** for the masked-input +
reveal-toggle affordance used for both the git PAT and the SSH passphrase:
- `IconButton` wrapping the visibility-toggle `Icon`, each with a **state-dependent
  `contentDescription`** ("Show token"/"Hide token", "Show passphrase"/"Hide passphrase" —
  `GitSetupScreen.kt:1181-1187`, `1237-1242`). This is correct WCAG 4.1.2 (Name, Role, Value)
  practice: a screen reader announces the *current* action the button performs, not a static label.
  `LlmProviderSettings`'s `EditBuiltInProviderKeyDialog` API-key field, by contrast, has **no**
  visibility toggle at all (`LlmProviderSettings.kt:195-202`, plain `PasswordVisualTransformation`
  with no reveal option) — a pre-existing inconsistency between the two credential-entry surfaces,
  not something this project's scope requires fixing, but worth noting if planning touches this
  dialog anyway.
- `RadioButton` + `Role.RadioButton` + `Modifier.selectable` for the "Saved tokens"/"Saved accounts"
  lists (`GitSetupScreen.kt:1196-1226`, `1279-1313`) — correct Compose semantics for a radio-group
  pattern (keyboard-focusable, announces "radio button, selected/not selected" to
  TalkBack/VoiceOver/screen readers on desktop).

**If this project adds any new UI affordance** (the task prompt raises "a forget saved credentials
button" or "an error state when SubtleCrypto/storage is unavailable" as hypotheticals — neither is
in requirements.md's Scope, so treat both as *contingent*, not committed):
- **A "Forget"/"Remove saved connection" button** (wiring the already-existing
  `GitCredentialConnectionStore.deleteConnection(id)` to UI, if planning decides this project
  should finally close that gap alongside making the store real): follow the existing icon-button
  pattern exactly — an `IconButton` per saved-connection row (e.g. `Icons.Default.Delete`) with a
  `contentDescription` that names the specific connection ("Remove saved connection
  {accountLabel}"), not a generic "Delete" (WCAG 2.4.6, Headings and Labels — labels must describe
  purpose). Because deleting a credential is destructive and not easily undoable (the PAT isn't
  recoverable from the app once deleted — the user would have to re-paste or redo OAuth), pair it
  with a confirmation step (a `Dialog`, matching this file's existing `Dialog`/`Surface` pattern
  used for `EditBuiltInProviderKeyDialog` and the OAuth flow's own dialog) rather than an
  irreversible single tap — this matches WCAG's general guidance against destructive one-click
  actions with no confirmation (best-practice extension of 3.3.4, Error Prevention, which formally
  only mandates this for legal/financial/data-modifying *submissions* but is widely applied to any
  destructive delete).
- **An "unavailable" error state** (if `isAvailable()` can genuinely go `false` on web, e.g.
  missing `SubtleCrypto`): must be **inline at the point of entry**, not only in the sync-status
  badge — a user typing a PAT into `GitSetupScreen` or a key into `EditBuiltInProviderKeyDialog`
  needs to learn *before* clicking Save (or immediately after, synchronously — recall `store`/
  `retrieve` are non-suspend per the sync-API constraint) that the value will not actually persist,
  not discover it three sync cycles later via a lock icon in a status badge. Concretely: disable
  the "Save" button (or let it save but immediately show inline error text below the field, styled
  like the existing `wikiSubdirError`-style inline validation already used elsewhere in this file —
  `GitSetupScreen.kt:1521-1526` for a precedent of the existing inline-error-text pattern) with
  text such as "Couldn't save securely in this browser — try a different browser or disable private
  browsing" (see §4 for why "private browsing" is the most likely real-world trigger). Any such
  error text must be programmatically associated with the field (Compose `OutlinedTextField`'s
  `supportingText` parameter, or equivalent, gives this via `LiveRegion`/`isTraversalGroup`
  semantics) so it's announced by a screen reader without requiring the user to find it visually —
  WCAG 3.3.1 (Error Identification) and 4.1.3 (Status Messages).
- **Keyboard navigation**: no changes needed to existing tab order patterns (`OutlinedTextField` →
  trailing `IconButton` → next field is standard Compose focus order); any new button (forget/error
  retry) should slot into the existing top-to-bottom tab order of its section, not require a new
  keyboard shortcut.

## 4. Error states and edge cases

Grounding these in the requirements doc's own Feasibility Risks and Rabbit Holes, translated to
what the user should actually see:

- **`isAvailable() == false`** (e.g. `SubtleCrypto` genuinely missing — realistically near-zero in
  evergreen browsers per requirements.md's own risk note, but must degrade gracefully if it
  happens): per §3 above, inline error at the entry field, save disabled or save-then-explain. Do
  **not** silently accept the input and discard it (today's actual no-op-stub behavior) — that is
  precisely the currently-broken UX this project exists to fix, and inheriting it for a new
  degraded-availability case would be the same defect recurring one layer up.
- **Storage quota exceeded**: requirements.md correctly notes this is a low-probability risk given
  credential volume (a handful of short tokens, not bulk data) — `QuotaExceededError` is the
  standard `DOMException` browsers throw (confirmed via MDN/W3C references). If it somehow occurs
  (e.g. a already-near-full `localStorage` origin from unrelated app data), the UX contract is the
  same as unavailable: catch it at the `store` call, treat as a failed write, surface inline at the
  entry field ("Couldn't save — browser storage is full") rather than reporting fire-and-forget
  success. This should reuse whatever error-surfacing mechanism is built for `isAvailable() ==
  false`, not a separate one — from the user's perspective both are "my save didn't work," and a
  single consistent message pattern is easier to design once and get right than two.
- **Private/incognito browsing modes**: this is the most realistic real-world trigger for a
  degraded credential store, more likely than actual API unavailability. Current browser behavior
  (2025-era, confirmed via MDN's Storage API quota docs and browser bug trackers): Firefox private
  windows generally allow `localStorage`/`IndexedDB` within the session but discard everything on
  window close (matching the user's own "private" expectation — no surprise there, since the user
  already knows private mode is ephemeral). Safari private mode has historically been the
  strictest (near-zero `IndexedDB` quota). The UX-relevant case is **not** "credential vanished
  when you expected it to persist" (private-mode users already expect that — same as §2's
  "clearing data" case) — it's **"store() throws or silently caps out mid-session,"** which the
  in-mode user does *not* expect (they expect it to work *for the duration of that private
  session*, just not survive closing it). Same inline-error contract as above covers this; no
  special-cased "you're in private browsing" copy is necessary or advisable (the app generally
  cannot reliably detect private-mode as a category across all browsers — attempting to and getting
  it wrong is worse than a generic "couldn't save" message).
- **Decryption failure after a scheme change across app versions** (explicitly named in the task
  prompt, matches the "Rabbit Holes" section's framing that this ships without a data-migration
  step because nothing durable existed before): since success metrics state "no migration of
  existing data is needed" and rollback is "revert the actual to the no-op stub," the realistic
  failure mode here is *future*, not present — a later app version changes the encryption scheme
  and can no longer decrypt what an earlier version wrote. UX contract: treat exactly like "value
  not found" (`retrieve()` returning `null`) from the *consuming* UI's perspective — `GitSetupScreen`
  already handles a null/absent stored token correctly today (falls through to the "enter a new
  token" empty-field state, `GitSetupScreen.kt:205-209`/`1228`). The one addition worth planning
  attention: log the decryption failure distinctly from "key genuinely not set" (per the
  Observability Requirements' "log attempts and outcomes, never the secret value" discipline
  already mirrored from `JvmCredentialStore`) so it's diagnosable later, without surfacing a scary
  "corrupted data" message to the user for what is, from their point of view, indistinguishable
  from "I need to re-enter my token" — the same low-drama treatment already given to every other
  "please re-authenticate" moment in this file (OAuth token expiry, etc.).

## 5. Jobs-to-be-done

- **Functional job — "don't make me retype my PAT/API key every session."** This is the literal,
  primary job, already well-evidenced by the requirements doc's own baseline description (a git
  PAT survives a reload today only via a plaintext hack; LLM keys never survive at all). Success
  here is measured exactly as the requirements doc's Success Metrics state: real persistence
  verified by a `wasmJsTest`, not by inspection.
- **Emotional job — "trust that my token isn't just sitting in the clear."** This is where
  `GitSetupScreen.kt:1246`'s existing "Token is encrypted on disk using device-specific keys" copy
  matters most: the emotional job is already being *promised* by that string today, on a platform
  where the promise is currently false. Closing that gap (real encryption backing the existing
  claim) delivers the emotional job without needing new UI — the trust signal already exists in the
  product; it just needs to become true. Per the Rabbit Holes section's own honest framing (parity
  with "don't leave PATs in cleartext for casual devtools/backup inspection," explicitly *not* a
  hardened secret store, no durable device-bound entropy source in a browser sandbox), the copy
  should not be strengthened beyond what's actually true — if planning finds the web scheme's
  threat model is meaningfully weaker than JVM's PBKDF2-machine-bound-key approach (per the
  Rabbit Holes' own concern about key material with no non-guessable per-device identifier), the
  existing "encrypted on disk using device-specific keys" string may need a small,
  platform-conditional qualifier on web rather than being left to imply parity with desktop it may
  not have. This is a copy-accuracy question for planning to resolve once the actual encryption
  scheme is chosen, not a call this research phase can make — flagged here as a concrete follow-up
  rather than left implicit.
- **Social job — none materially in scope.** Credential persistence here is single-user,
  single-browser-profile, non-shared (per Out of Scope's explicit exclusion of multi-tab/
  multi-origin sync). There's no "show my team I've connected my account" or collaboration-visible
  surface — `GitSetupScreen`'s "Connected as @username" banner (`GitSetupScreen.kt:1269-1273`) is
  the closest thing to a social/identity confirmation, and it already works today (it reads from
  in-memory OAuth-flow state, not from the broken `CredentialStore`) — this project doesn't change
  that banner's behavior, only whether the underlying token survives a reload.

## Summary of concrete UX findings for planning

1. `GitSetupScreen.kt:1246`'s "Token is encrypted on disk using device-specific keys" string is
   already shown to web users and is currently false there — verify (or scope a copy tweak for) its
   accuracy once the actual web encryption scheme is chosen; this is the single highest-value UX
   fix bundled into this otherwise-backend project.
2. No checkbox/opt-in UX is needed or precedented — "Save" already implies "persist across
   reload/new-tab, not across a data clear," matching correct user expectations; just make that
   already-implied contract true on web. `EphemeralSettingsMode` is the one place today's implicit
   contract could be silently violated and must be explicitly respected.
3. If planning adds a "forget saved credential" affordance (closing the pre-existing, currently
   unused `GitCredentialConnectionStore.deleteConnection` gap) or an `isAvailable()==false`/quota
   error state, follow this file's existing accessible patterns exactly (state-dependent
   `contentDescription`, inline field-level error text, confirmation dialog for destructive
   delete) rather than inventing new ones — and surface storage-write failures inline at the entry
   point, not only via the sync-status badge, since neither credential-entry dialog has any error
   state today.
