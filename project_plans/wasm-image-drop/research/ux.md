# UX Research: wasm-image-drop

Agent 5 (UX), SDD Phase 2. Scope per `requirements.md`: drag-and-drop image attachment on
WASM/web, matching JVM's `PageDropTarget.kt` filter-to-`imageExtensions` behavior, no visible
hover-state UI in the current JVM code.

## 1. Comparable UX patterns

Every mainstream editor with drag-and-drop file attachment pairs the drop with a visible
dragover affordance — this repo's current JVM implementation (silent, no highlight) is the
outlier, not the norm:

- **General pattern** (file-upload UX literature): dragover/dragenter should change the drop
  zone's border style (commonly dashed→solid or a color change) and/or background tint, with
  `dragenter`/`dragover` both driving the highlight and `dragleave`/`drop` clearing it. A
  drag-enter/leave *counter* (not a boolean) is the standard fix for the classic bug where
  `dragleave` fires prematurely when the cursor crosses a child element inside the drop zone.
  [Pencil & Paper — Drag & Drop UX Design Best Practices](https://www.pencilandpaper.io/articles/ux-pattern-drag-and-drop),
  [UX Patterns for Developers — File Input Pattern](https://uxpatterns.dev/patterns/forms/file-input),
  [Smashing Magazine — vanilla JS drag-drop uploader](https://www.smashingmagazine.com/2018/01/drag-drop-file-uploader-vanilla-js/)
- **Notion**: block-level drag shows a slim horizontal insertion-line indicator that tracks the
  cursor in real time, so the user always sees exactly where content will land.
  [Eleken — Drag and drop UI examples](https://www.eleken.co/blog-posts/drag-and-drop-ui)
- **Obsidian**: native file-drop-to-embed behavior exists, and the community plugin ecosystem
  (Dragger, obsidian-block-drag-drop) converges on the same idea — a glowing/visible drop
  indicator line plus a cursor-following ghost preview — for block reordering.
  [Obsidian Help — Drag and drop](https://obsidian.md/help/drag-and-drop),
  [Dragger plugin](https://community.obsidian.md/plugins/dragger)
- **GitHub issues/PRs**: drops a file into the comment textarea; docs also surface a fallback
  "click to browse" affordance directly in the drop zone, not hidden in a separate toolbar.
  [GitHub Docs — Attaching files](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files)

**Verdict on this repo's silence-until-drop**: it is a UX gap, not an acceptable minimalist
choice, on *both* platforms — but it's pre-existing on JVM/desktop, not introduced by this fix.
Recommend flagging it as a follow-up for JVM too rather than scope-creeping visual polish into
this bug fix. If a hover affordance is added for web (where the "did anything happen"
uncertainty is worse — see §2), a one-line `Modifier.border()` color swap keyed off a
`isDragOver` boolean state is proportionate; a Notion-style insertion-line indicator is
disproportionate for a file-attach (not block-reorder) interaction and should not be pulled in.

## 2. User mental model

Dragging a file over any browser tab has a well-known failure mode if `dragover`/`drop` aren't
`preventDefault()`-ed: the browser navigates to or renders the file directly, discarding the
app state in that tab. Users who have hit this before (it is common — most sites do not accept
drops) arrive primed to expect either (a) nothing happens, or (b) the tab navigates away and
their work is gone. This makes web strictly higher-stakes for feedback than desktop, where
"drop somewhere without visible effect" degrades to "user tries again," not "user loses the
tab."

**Minimum feedback needed to reassure the user the drop target is "live"**:
1. The cursor changes to a "copy"/"move" affordance during dragover (native browser behavior
   once `dragover` is intercepted and `dropEffect` is set — free, no Compose UI work).
2. Nothing navigates away or opens the file in a new view — i.e., `preventDefault()` is called
   on `dragover` and `drop` on the canvas listener. This is a *correctness* requirement, not a
   nice-to-have: without it, the fix silently fails in the worst possible way (looks broken,
   loses the user's page).
3. Post-drop, the markdown appears in the block within a perceptible moment. Given the existing
   silent-failure gap in §4, this immediate visible insertion is effectively the *only*
   confirmation the user gets that the drop succeeded — which raises the cost of a silent
   rejection (§4) on web specifically, since there's no "well, the browser at least didn't
   navigate away" consolation the way there might be with a visible highlight.

Item 2 is already implied by "wire a JS `drop` listener on the canvas" in `requirements.md`,
but should be called out explicitly as an acceptance-relevant behavior in the plan phase (it is
not currently listed as one of the seven draft acceptance criteria) — a `drop` handler that
correctly extracts files but forgets `preventDefault()` on `dragover` will still let the browser
render the dropped image full-page on some browsers.

## 3. Accessibility

Drag-and-drop is definitionally a fine-motor pointer interaction. **WCAG 2.2 SC 2.5.7 Dragging
Movements (Level AA)** requires that any functionality operable via dragging also be operable
via a single pointer action (tap/click) without dragging, unless dragging is essential. The
canonical remedy for file-drop specifically is exactly what `requirements.md` scopes as the
"fast follow": a **"Browse…" / click-to-pick button alongside the drop zone**, not a novel
drag-alternative gesture.
[Silktide — WCAG 2.5.7](https://silktide.com/accessibility-guide/the-wcag-standard/2-5/input-modalities/2-5-7-dragging-movements/),
[Sparkbox — Understanding and Implementing 2.5.7](https://sparkbox.com/foundry/understanding_implementing_wcag_dragging_movements_accessibility)

**Is shipping drag-and-drop on web without the picker an acceptable interim state, or should it
block/reorder this work?** Acceptable to ship interim, with one caveat:

- SC 2.5.7 is a criterion about *not removing existing functionality's accessible path*, not a
  blanket ban on adding a new drag-only feature. Today, web has **zero** image-attachment path
  (drag, paste, and picker are all dead per requirements.md §"Root-cause investigation" item 2).
  Shipping drag-only restores *a* path where none exists today; it does not regress an
  already-accessible path to a less-accessible one. That distinguishes this from the WCAG
  violation pattern (e.g., removing a working "Move" button and replacing it with drag-only
  reordering).
- However, once drag-and-drop ships, a screen-reader/keyboard-only web user is *worse off
  relative to their JVM/desktop counterpart* than they are today (today, neither user has image
  attachment on web; after this fix, mouse users do and keyboard users still don't). That gap is
  real and the requirements doc is correct to flag the picker as a "likely fast follow," but
  given the picker's implementation cost is explicitly called out as low once the shared
  `MediaAttachmentService`/bytes-write plumbing exists ("same service, no drag/JS-interop
  plumbing needed"), **recommend the plan phase size the picker as a stretch item in the same
  project** rather than a separate backlog entry that risks languishing — the marginal cost
  after this fix's plumbing lands is small, and shipping both together closes the accessibility
  gap before it becomes visible in production rather than after. This is a scoping suggestion
  for the plan phase to weigh against velocity, not a hard blocker on this research.

## 4. Error states

**Existing pattern found — reuse it, do not invent a new one.** This app has a working toast
system: `NotificationManager.show(message, type)` → `NotificationOverlay` /
`NotificationToast` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/NotificationDisplay.kt`),
styled per `NotificationType` (`INFO`/`WARNING`/`ERROR`/`SUCCESS`) with matching
Material3 container colors and icons. It is already wired to user-visible errors elsewhere,
e.g. `GraphDialogLayer.kt:256`: `notificationManager.show("Failed to save bug report. Check
storage permissions.")`.

**But the image-attachment failure paths do not use it — this is a pre-existing, cross-platform
gap, not wasm-specific.** All three attachment entry points in `App.kt` swallow `Either.Left`
into a log-only call with no user-facing surface:

- `App.kt:1186` (`onAttachImage`): `graphContentLogger.warn("Image attachment failed: $err")`
- `App.kt:1213` (`onFileDrop`): `graphContentLogger.warn("Drag-and-drop attachment failed: $err")`
- `App.kt:1238` (`onPasteImage`): `graphContentLogger.warn("Clipboard paste failed: $err")`

None of these three call `notificationManager.show(...)`. A user on JVM today who drops a file
that fails (permission error, disk full, etc.) sees literally nothing — the failure only exists
in a logger nobody but a developer will read. This matters directly for this project: dropping
a non-image file is explicitly in scope as a *silent, expected* no-op (acceptance criterion 3 —
correct, matches JVM, no toast needed for "wrong file type" since it's not an error, just a
filtered-out input). But a *real* failure after a valid image drop — OPFS write failure, quota
exceeded, byte-conversion failure — is different from "wrong extension" and reaching the same
silent `graphContentLogger.warn` dead-end would reproduce a known, already-present UX bug on a
new platform.

**Recommendation for the plan phase**: route the wasm `onFileDrop` failure branch through
`notificationManager.show("Image attachment failed: ...", NotificationType.ERROR)` in addition
to (not instead of) the existing log call. This is a small, low-risk addition (the call site
and `NotificationManager` instance are already threaded into `App.kt` at the exact point where
the `Either.Left` is handled) and it closes a real silent-failure gap for the specific new
failure modes this project introduces (OPFS quota/write failures) without taking on the
larger, pre-existing "none of the three attachment paths toast on any platform" cleanup as
in-scope — that's a separate, cross-cutting fix the plan phase should explicitly defer, not
silently fix everywhere or silently ignore.

## 5. Jobs-to-be-done

The job "drag a screenshot/photo straight into an open page" is a **speed and flow-preservation**
job: the user has just taken a screenshot or has a photo open in another window/Finder/Explorer,
and the value is not having to break out of the note-taking moment to open a file picker dialog,
navigate a filesystem tree, and click through modal steps. This is the same job clipboard-paste
(`onPasteImage`) serves for screenshots already on the clipboard — drag-and-drop is the
equivalent for "I have a file, not clipboard content." For a Logseq-style outliner specifically,
where the core loop is rapid block-at-a-time capture, breaking flow to attach media is a
disproportionately large tax relative to typing a line of text, which makes this job more
valuable in *this* app than in a general-purpose document editor.

**Is the toolbar picker an adequate substitute, making drag-and-drop a nice-to-have?** No —
they serve overlapping but not equivalent jobs:

- The picker requires the image to already exist as a discoverable file the user can navigate
  to by name/location. Drag-and-drop lets the user drag directly from wherever the file already
  is (desktop, another window, a file manager they already have open) without that navigation
  step being reinvented inside an in-app file dialog.
  Picker: correct icon, may still be one click faster than nothing.
- The emotional job — "capture this idea/moment before I lose it" — is better served by drag
  (physically move the thing you're looking at into the thing you're writing) than by a picker
  (open a dialog, remember/guess where the file landed, click through). This is a bigger gap
  for screenshots (freshly saved to Desktop/Downloads with an unmemorable filename) than for a
  deliberately organized photo library.

**Worth the cost?** Given the picker is scoped out as a fast follow and the plumbing this
project builds (bytes-capable `MediaAttachmentService` method, OPFS `writeFileBytes`) is the
expensive, shared part of the work — the JS canvas-listener glue on top is comparatively cheap —
yes, this fix is worth it independent of whether the picker ships alongside it. The picker is a
smaller, faster follow-up precisely *because* this project pays down the shared plumbing cost;
sequencing drag-and-drop first (as scoped) rather than waiting to ship both simultaneously is
reasonable, though §3 above stands: the plan phase should weigh bundling the picker in given how
small its incremental cost becomes once this project's plumbing lands.

## Summary of UX-relevant items for the plan phase

1. `preventDefault()` on both `dragover` and `drop` at the JS listener is a correctness
   requirement (prevents browser navigating away with the file) — recommend adding it as an
   explicit acceptance criterion, not just implied by "wire a drop listener."
2. No hover-state visual affordance exists on JVM today; not introducing one for web either is
   consistent/acceptable for this bug-fix-scoped project. Flag as a separate, cross-platform
   polish item if pursued later.
3. WCAG 2.5.7 does not block shipping drag-only on web (it restores a currently-dead path,
   doesn't regress an existing accessible one) — but the plan phase should weigh pulling the
   toolbar-picker fast-follow into this same project's stretch scope, since its incremental
   cost drops sharply once this project's shared plumbing (bytes-capable
   `MediaAttachmentService`, OPFS byte writes) exists.
4. Route the new wasm failure branch (OPFS write/quota failures) through the existing
   `NotificationManager.show(..., NotificationType.ERROR)` toast pattern in addition to the
   `graphContentLogger.warn` call already used elsewhere — reuse, don't invent new UI. Treat the
   pre-existing gap (JVM/Android/iOS attachment failures also don't toast today) as an
   explicitly out-of-scope, separately-tracked cleanup.
