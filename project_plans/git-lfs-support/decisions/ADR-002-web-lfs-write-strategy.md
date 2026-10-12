# ADR-002: Web (wasmJs) LFS write strategy: layered, capability-probed

**Status**: Proposed. **Owner amendment 2026-10-11:** strategy ii (Proxy) is REJECTED (O1: no proxy at all); strategy iii (Converter) is APPROVED as an opt-in advanced mode (O2). Strategies i and iv stand. Body text below still describes the Proxy mode and needs reconciling (see the plan banner).
**Date**: 2026-10-11
**Deciders**: Repo owner (Tyler Stapler)
**Qualifies**: `docs/adr/ADR-015-wasm-git-data-api-write-back.md` (its "no server-side proxy in v1" at `:15`, "no CORS proxy, no additional infrastructure" at `:32`, and "Option C: server-side proxy commit, deferred to v2" at `:49-53`). ADR-015 stays in force for everything that is not an LFS-tracked path.
**Related**: ADR-003 (guard, staging-ref exception), ADR-004, `../research/web-lfs.md` sections 1 to 7

## Context
The browser cannot speak LFS to github.com or gitlab.com with credentials. Verified today (`../research/web-lfs.md` section 1): the batch endpoint preflight carries no `Access-Control-Allow-Origin` on github.com (204) and gitlab.com (200), `Authorization` forces a preflight, no REST endpoint on either host creates an LFS object, `objects-origin.githubusercontent.com` is CORS-pinned to `https://github.com`, a service worker or Pages cannot change this. Bitbucket and Azure DevOps preflights pass. Reads are easier: `media.githubusercontent.com` and GitLab `api/v4/.../raw?lfs=true` are CORS-open.

Owner direction (`../requirements.md`): find a real way, not just a guard, and state its cost precisely; the guard ships everywhere regardless.

## Decision
`LfsWriteCapabilityResolver` picks, per graph and per push, the first available mode in this order, and records which one it used:

| Order | Mode | Available when | Needs | Cost to user |
|---|---|---|---|---|
| 1 | `Direct` | A runtime probe (`WasmLfsCorsProbe`) shows the LFS batch endpoint passes the browser preflight with `Authorization`. Expected hosts: Bitbucket, Azure DevOps (preflight VERIFIED; object-storage hrefs UNVERIFIED), self-hosted Giftless with CORS enabled (INFERRED) | Nothing | None |
| 2 | `Proxy` | User set `lfsProxyUrl` in Settings and the runtime health check passes | A user-owned CORS proxy (template shipped in `docs/reference/lfs-cors-proxy/`, SteleKit hosts nothing) | A Cloudflare or Deno account and one deploy; the token transits the proxy (see Threat model) |
| 3 | `Converter` | Owner decision O2 is YES, `lfsConverterEnabled` is on, the workflow file exists in the repo, token has dispatch scope | Staging branch plus workflow in the user's repo (GitHub Actions or GitLab CI) | Workflow file in the repo, Actions runner usage, tens of seconds of latency, async result, raw bytes briefly in the host's object store |
| 4 | `ReadOnly` | None of the above | Nothing | LFS-tracked paths cannot be written from the browser; guard holds the files back with a clear message; text notes still sync |

Probe rules: the probe sends a real `POST <lfs>/objects/batch` with `operation=download` and `objects=[]` plus `Authorization`. A resolved response of any HTTP status means the preflight passed (`Direct` capable). A network-level `TypeError` means blocked. Results are cached per host in memory and in `Settings` key `git_lfs_cors_probe_<host>` with the probe time, and re-probed after a one-day TTL or on a CORS-shaped failure. Host tables are only hints; the probe decides.

### Direct and Proxy share one client
Both use the commonMain `LfsBatchClient` (ADR-004). Proxy mode differs only in the transport base URL: the batch call goes to `<proxy>/<host>/<repo>.git/info/lfs/objects/batch` with `X-Target`, and the template rewrites `actions.*.href` server-side to proxy URLs signed with an HMAC, so the client needs no href rewriting and the proxy can refuse any object URL it did not issue. The token is sent only on the batch call; object calls carry only the `header` map from the action (LFS spec rule; R12).

### Proxy threat model and hardening (template requirements)
- Token exposure: the proxy operator sees the PAT. A user-owned deployment shares the user's own trust boundary; SteleKit never ships or defaults a shared proxy. Settings shows a one-time warning requiring explicit acknowledgment (`lfsProxyAckTokenExposure`).
- Open-relay and SSRF: `Origin` pinned to one value (`env.ALLOWED_ORIGIN`, never `*`); requests without `Authorization` are refused (so it is not an anonymous relay); exact-hostname allowlist, no IP literals, no `localhost`; batch path regex `^/[^/]+/[^/]+\.git/info/lfs/objects/batch$`; object URLs only if HMAC-signed by the Worker; `redirect: "manual"`; methods limited to POST, GET, PUT, OPTIONS; request size cap; no header logging.
- Per-push health: the check runs once per push before any request; a failure maps to `LfsProxyUnavailable` and the resolver falls to the next mode or `ReadOnly`. Runtime health check: `GET <proxy>/__health` must return JSON `{ "ok": true, "allowedOrigin": "<our origin>", "version": <n> }`; SteleKit refuses to enable the proxy if `allowedOrigin` does not equal `window.location.origin`.
- Known limit: Cloudflare Workers request-body limits and CPU time (INFERRED, re-verify before documenting numbers) cap object size; the template README states the cap and SteleKit's size cap defaults below it.

### Converter (strategy iii): defined fully, blocked on O2
GitHub: browser creates blob, tree and commit on `refs/heads/stelekit/lfs-inbox/<id>` via the Data API (never the configured branch), then `POST /repos/:o/:r/actions/workflows/stelekit-lfs.yml/dispatches` (preflight VERIFIED `ACAO: *`). The workflow checks out the staging ref with `lfs: true`, runs `git add --renormalize`, rebases onto the target branch, pushes pointers and objects with `GITHUB_TOKEN` (`contents: write`), and deletes the staging ref; on a rebase conflict it fails and leaves the ref for the user. GitLab: Commits API to a staging branch plus `POST /projects/:id/pipeline` (preflight VERIFIED `ACAO: *`), with `.gitlab-ci.yml` doing the same. The workflow validates the ref against `^refs/heads/stelekit/lfs-inbox/[A-Za-z0-9_-]+$` before acting.
This contradicts a strict reading of the safety floor, because the staging ref transiently holds a raw blob. ADR-003 defines the only allowed exception (`LfsStagingRefException`); if the owner answers NO to O2, the converter stories stay specified and unbuilt, and the resolver skips mode 3. Residual cost, stated plainly: the raw blob remains as an unreachable object on the host until its garbage collection, which for a repository that adopted LFS to avoid large blobs means a temporary size increase and a privacy note (raw bytes were in the git object store, not LFS).

### Rejected
| Alternative | Why not |
|---|---|
| SteleKit-hosted proxy | Out of scope (`../requirements.md`); SteleKit would handle user tokens and run infrastructure |
| Same-origin proxy via GitHub Pages or service worker | Impossible: Pages is static; a service worker is subject to the same CORS rules (`../research/web-lfs.md` section 4) |
| Browser extension | Not available to the hosted PWA, per-browser, store review |
| Release assets or GitLab generic packages | Not LFS objects; a pointer cannot reference them |
| Query-string or cookie auth to stay a "simple" request | UNVERIFIED and unlikely; batch auth is HTTP Basic per the spec; `fetch` forbids credentials in URL userinfo |

## Consequences
- ADR-015 needs a one-line pointer to this ADR (Task E6.1c); its Option C text stays as the record of the v1 stance.
- Web LFS writes work with no infrastructure only on CORS-enabled LFS hosts and, if O2 is YES, via the converter; on github.com and gitlab.com everything else requires the user-owned proxy. The summary SteleKit shows users: "No setup: reading, and writing on hosts that allow it. One user-owned proxy: writing on GitHub and GitLab. Never possible: writing from the browser with no proxy and no workflow."
- Real-host CORS validation (private repo, PAT) is a wall-clock blocker owned by the repo owner (checklist in Story E7.6).
