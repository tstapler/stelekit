# ADR-003: Plain-graph `AppOwned` ships with a mandatory warning + zip export on both platforms; Android `Link` mode is scoped to git-cloned graphs only

**Status**: Accepted (amended 2026-09-12 — Web export scope reversed, see Amendment below)
**Date**: 2026-09-12
**Project**: app-owned-storage-clone

## Context

Two related scope questions were left open by requirements.md and must be resolved before Phase 2
ships, not deferred as unresolved questions:

1. **`allowBackup="false"`** (`androidApp/src/main/AndroidManifest.xml:26`, confirmed) means Android
   Auto Backup does not cover `filesDir` at all. For a **git-cloned** graph relocated to `AppOwned`,
   the git remote is an existing, adequate backup (if pushed) — out of scope to duplicate per
   requirements.md's explicit exclusion of "cross-device sync/cloud backup." For a **plain
   (non-git)** graph, there is no remote and no OS backup: an uninstall is unrecoverable, total data
   loss, with zero existing mitigation (`research/pitfalls.md` §2, the single highest-severity
   finding in that document).
2. **Android "Link" mode for plain graphs**: no continuous-mirror primitive exists for plain
   (non-git) Android graphs today — only the git-shadow-worktree's write-back mechanism does
   continuous mirroring, and only for git-cloned graphs. Building a plain-markdown continuous
   mirror from scratch is a new sync engine, explicitly out of scope per requirements.md.

## Decision

**On (1):** Ship `AppOwned` as a selectable primary destination for **both** git-cloned and plain
graphs (deferring it for plain graphs would contradict Success Metric 1, which does not
distinguish git from plain). Mitigate instead of deferring:
- A mandatory, non-dismissable-without-reading warning shown at the moment a user selects
  `AppOwned` for a **plain** graph, worded per `ux.md`'s load-bearing-copy convention (state the
  concrete consequence, not a vague risk): *"Kept inside SteleKit only — not visible in your
  device's file manager, and permanently deleted if you uninstall the app. There is no automatic
  backup."*
- An **Android-only** one-shot "Export as .zip" affordance inline in that same warning, built on
  `java.util.zip.ZipOutputStream` (JVM/Android stdlib, already used elsewhere in this codebase —
  `SpanArchiver.android.kt`, `GzipHelper.jvm.kt` — for gzip, not zip, but the same stdlib package),
  reading the graph's markdown files via the existing `FileSystem` interface. This is a small,
  contained task (recursive read + zip stdlib call), not a new backup engine, and is Android-only
  because that platform has the specific `allowBackup="false"` finding; Web is not given a matching
  OPFS zip-export in this pass — see justification below.
- Web also gets the mandatory warning copy (*"Kept in this browser only — not backed up
  automatically, and lost if you clear site data."*) **and** an export affordance — see Amendment
  below; the two platforms are no longer asymmetric on this point.

**On (2):** Android `Link` mode ships **only for git-cloned graphs**, reusing the existing
git-shadow-worktree write-back mechanism (promoted from an invisible cache-mode detail to a
user-visible, explicitly-invoked continuous mirror). Plain-graph `Link` on Android is **out of
scope for this project** — flagged as a follow-up, not built. Web `Link` mode is unaffected by this
decision and ships for both git-cloned and plain graphs (it already has a general-purpose
continuous-mirror primitive, `HostDirectorySync`, that isn't git-specific).

## Consequences

- `plan.md` Phase 2 includes the warning-copy and export tasks — Android zip-export *and* Web
  export (per the Amendment below) — as part of each platform's `AppOwned`-for-plain-graphs story,
  not a follow-on. Both are **Phase-2-blocking**: `AppOwned` is not shipped as a selectable option
  for a plain graph on a platform until that platform's export task lands, per the Amendment's
  rationale (pre-mortem finding, 2026-09-12).
- `plan.md` Phase 4 (Link) explicitly notes Android plain-graph `Link` is out of scope, with a
  one-line pointer to this ADR so a future contributor doesn't read its absence as an oversight.
- If a user later asks for plain-graph Android `Link`, it requires new sync-engine design work
  explicitly outside this project's appetite — re-scope as its own project rather than bolting it
  onto this one.

## Amendment (2026-09-12): Web gets an export affordance too, as a Phase-2 blocker

**Reversed finding**: the original decision above deferred Web's zip-export as a "single narrow
mitigation" not worth a new dependency. A Phase 4 pre-mortem found this creates a worse-than-
baseline outcome: this project's own appetite note (`requirements.md`) already anticipates appetite
running out after Phase 2's "usable slice." If it does, Web users who picked `AppOwned` for a plain
graph — now an actively-promoted peer option, not a rare auto-fallback — are left with **no escape
hatch at all**: no zip export (original ADR-003 text above), and no relocate/link (Phase 3/4, which
may never ship for this app). That is strictly worse than the pre-project baseline, where OPFS was
only an invisible fallback a user didn't deliberately choose. A mitigation gated behind a phase that
might not ship does not mitigate anything.

**Revised decision**: Web ships a minimal export/download affordance in the same Story 2.3.3 warning
dialog, **required before `AppOwned` is offered as a selectable option for a plain graph on Web at
all** — i.e., a Phase-2 blocking task, not a Phase 3/4 nice-to-have. This does not require adopting
a new compression library, which preserves `research/build-vs-buy.md`'s "no new external dependency
is justified" finding:

- Build a **stored-only (uncompressed) ZIP** writer by hand in `commonMain` or `wasmJsMain` — the
  ZIP local-file-header / central-directory-record binary format with `STORED` (method `0`) entries
  needs only a CRC-32 implementation (a ~15-line table-driven routine, no library) and byte-buffer
  writes; no DEFLATE/compression code is required, since `STORED` entries are copied verbatim. This
  keeps the on-disk output a real, standards-compliant `.zip` any OS can open, without pulling in
  `java.util.zip` (JVM-only, unavailable on wasmJs) or a third-party Kotlin/Wasm compression library.
- Trigger the download through the existing `WasmJsShareProvider`
  (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/ui/PlatformShareProvider.js.kt`) — its
  `triggerBlobDownload` helper already creates a `Blob`/`<a download>` flow for text content; this
  needs a binary-`ByteArray`-accepting sibling (a `Uint8Array`-backed `Blob` with
  `type: "application/zip"`), not a new download mechanism.
- Scope: the same recursive-markdown-read approach as the Android exporter (Task 2.2.1e), reading
  via the existing `FileSystem` interface — no new file-enumeration logic per platform.

This is now symmetric with Android's mitigation in spirit (an escape hatch shipped in Phase 2,
before any deferrable phase), even though the two implementations differ (stdlib `ZipOutputStream`
on Android vs. a hand-rolled stored-only writer on Web) because of the platform's actual library
availability. See `plan.md` Epic 2.3, Story 2.3.3 for the updated task breakdown.
