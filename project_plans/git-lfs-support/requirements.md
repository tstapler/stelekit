# Requirements: git-lfs-support

**Date**: 2026-10-11
**Type**: feature addition (git sync), all platforms

## Problem Statement
SteleKit's git sync does not understand Git LFS. On JVM/Android (plain JGit 7.3.0, no `org.eclipse.jgit.lfs`), clone/merge leave LFS-tracked files as ~130-byte pointer files and `git add` without the clean filter commits raw binaries into repos that track those paths with `filter=lfs`. On web (no JGit; GitHub/GitLab REST write-back) LFS files are likewise unresolved and browser CORS blocks the LFS batch API. Owners of Logseq graphs that keep `assets/` (images, PDFs) in LFS get broken assets and risk corrupting their repo with raw binaries.

## Users / Consumers
- End users syncing a Logseq graph that uses LFS for assets (Desktop, Android, iOS, web).
- Both human users and automated systems (background sync, write-back queue, shares).

## Success Metrics
- Safety floor: on every platform, SteleKit never commits or uploads a raw binary for a path whose `.gitattributes` says `filter=lfs` (regression-tested).
- JVM + Android: clone/pull materialises LFS content; commit/push of changed LFS files uploads objects and commits pointers (verified against a local LFS server fixture).
- Web: LFS assets viewable (read path) and writable via a REAL design, with a precise statement of what needs no infrastructure, what needs a user-configured proxy, and what is impossible.
- Pointer parse/serialise property-tested against the spec.

## Constraints
- Owner direction: real full LFS support on JVM, Android and web; "find a REAL way, not just a guard" for web, and state its cost precisely. Guard is wanted regardless as the safety floor on every platform.
- Browser CORS: verified github.com and gitlab.com LFS batch endpoints send no `Access-Control-Allow-Origin` on preflight (see research/web-lfs.md).
- ADR-015 (no proxy / no extra infrastructure in v1) must be superseded or qualified by a new ADR if a proxy is chosen.
- Pursue full scope; no downscale questions (owner standing instruction). Estimate in price-weighted cost classes, never human time.
- Planning only: no implementation, commit, push or PR.

## Scope
### In Scope
- Common pointer model, `.gitattributes` LFS detection, commit guard (all platforms, ships first).
- JVM/Android JGit LFS integration (smudge on checkout, clean on add, batch upload on push, auth passthrough, shallow clones).
- Web read path (pointer resolution), web write path per recommended design incl. settings/UX.
- iOS stance. Docs/demo/site updates. Test fixtures (local LFS server, wasm mocked endpoint).

### Out of Scope
- Implementation, commits, PRs (this run).
- Hosting SteleKit-operated infrastructure (unless the owner decides so; see Open Questions).
- LFS file locking API, `git lfs migrate`/history rewriting.

## Open Questions
1. Owner decision: ship an opt-in user-configured CORS proxy (and a template) for web LFS writes? Who hosts it?
2. Auto-download default and size cap for LFS objects on mobile / metered networks.
3. Private-repo behaviour of media.githubusercontent.com with a token (untested; research/web-lfs.md).
4. iOS: is a native LFS client in scope for this plan or only the guard + read path?
5. Is there a real user repo using LFS for the first-sync fixture?
