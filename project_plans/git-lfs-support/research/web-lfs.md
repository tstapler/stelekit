# Web (wasmJs) Git LFS: research

Date: 2026-10-11. All curl results below were run today from this machine with `Origin: https://example.app`. VERIFIED = command run; INFERRED = reasoned from docs or analogy; UNVERIFIED = could not test.

## 0. Headline

- The blocker is narrower than "CORS blocks LFS". GitHub's batch endpoint answers an ACTUAL request with `access-control-allow-origin: *` but its preflight (OPTIONS) returns 204 with no CORS headers. A preflight is only triggered by non-simple requests, and `Authorization` is non-simple. So anonymous (public repo) reads work with no proxy, authenticated reads/writes do not.
- A real, no-SteleKit-infrastructure web write path exists, but not by talking LFS from the browser: commit the raw file to a staging branch via the Git Data API and let a workflow in the user's own repo (GitHub Actions / GitLab CI) convert it to an LFS pointer commit. Cost: a workflow file in the user's repo, token scope for dispatch, latency, and raw blob lingering unreachable in the host's object store.
- The only way to speak LFS directly from the browser for writes on github.com / gitlab.com is a proxy that adds CORS headers (user-configured, e.g. a Cloudflare Worker). Needs a user-owned deployment, plus exposes the token to it.

## 1. CORS matrix (VERIFIED unless noted)

| Endpoint | Preflight (OPTIONS, ACRH authorization,content-type) | Actual response |
|---|---|---|
| `github.com/o/r.git/info/lfs/objects/batch` | 204, NO `access-control-allow-origin` | POST (public, any Content-Type incl. text/plain) -> 200 with `ACAO: *` |
| `gitlab.com/o/r.git/info/lfs/objects/batch` | 200, NO CORS headers | POST -> 401/200, NO ACAO |
| `codeberg.org/.../info/lfs/objects/batch` (Forgejo) | 404 on OPTIONS, no CORS | POST 200, no ACAO |
| `gitea.com/.../info/lfs/objects/batch` | 404 on OPTIONS, no CORS | POST 200, no ACAO |
| `bitbucket.org/.../info/lfs/objects/batch` | 200, `ACAO: *`, allows Authorization, Content-Type, methods incl. PUT/POST | POST (unauth) 401 with `ACAO: *` |
| `dev.azure.com/.../info/lfs/objects/batch` | 200, `ACAO: *`, allow-headers `authorization,content-type` (path was a dummy; generic dev.azure.com CORS) | not tested (needs auth) |
| `api.github.com/...` (blobs, dispatches, workflow dispatches) | 204, `ACAO: *`, allows Authorization, Content-Type, X-GitHub-Api-Version | n/a (already used by ADR-015) |
| `media.githubusercontent.com/media/<o>/<r>/<ref>/<path>` | `ACAO: *`, allow-headers `X-CSRF-Token,Authorization,Accept,GitHub-Remote-Auth` | 200 `ACAO: *`; also serves non-LFS files |
| `raw.githubusercontent.com/...` | n/a | `ACAO: *` |
| `github.com/o/r/raw/<ref>/<path>` | n/a | 404 for the path tested (not usable as a CORS source) |
| `gitlab.com/api/v4/projects/:id/repository/files/:path/raw[?lfs=true]` | 200, `ACAO: *`, echoes `private-token,authorization` allowed | 200 `ACAO: *` |
| `gitlab.com/<ns>/<p>/-/raw/<ref>/<path>[?lfs=true]` | 200 but no CORS headers | 200, NO ACAO (unusable from browser) |
| `gitlab.com/api/v4/projects/:id/pipeline`, `.../packages/generic/...` | 200, `ACAO: *` | n/a |
| `api.github.com/repos/o/r/dispatches`, `.../actions/workflows/x.yml/dispatches` | 204, `ACAO: *` | n/a |
| `uploads.github.com/.../releases/:id/assets` | 400 (no CORS info) | UNVERIFIED |
| `objects-origin.githubusercontent.com` | 204, `ACAO: https://github.com` only (pinned to github.com) | n/a |
| `github-cloud.s3.amazonaws.com`, `github-cloud.githubusercontent.com` | 403 on bogus key, no CORS headers | UNVERIFIED |

Notes
- Bitbucket and Azure DevOps are the only hosts whose LFS batch preflight passes. Bitbucket's actual batch response also carried `ACAO: *`. Whether their object-storage action hrefs (signed URLs) carry CORS is UNVERIFIED (needs a real repo). INFERRED: Bitbucket serves objects from its own domain, so likely same policy.
- Forgejo/Gitea (codeberg, gitea.com) have no CORS on LFS. Self-hosted Gitea/Forgejo can be configured by the admin only via reverse proxy. Giftless (Python LFS server) supports a configurable CORS middleware (INFERRED from project docs, not tested; no instance reachable). lfs-test-server: no CORS support known (INFERRED).
- Anonymous workaround for GitHub (VERIFIED): a POST with `Content-Type: text/plain` and `Accept: application/vnd.git-lfs+json` returned 200 with ACAO `*` and a normal JSON body for download, so no preflight is triggered. Works only with no Authorization header, so only public repos. Upload operation unauthenticated returned 406.
- Whether GitHub accepts the credential through some non-header channel (query string) so the request could stay "simple": UNVERIFIED and unlikely (batch auth is HTTP Basic per the LFS auth spec; fetch() also forbids credentials in URL userinfo). Treat as not available.

Action hrefs and object storage CORS: the batch spec (https://github.com/git-lfs/git-lfs/blob/main/docs/api/batch.md) says `actions.download|upload|verify` carry `href`, optional `header`, `expires_*`; there is no CORS requirement in the spec. GitHub downloads go to github-cloud.githubusercontent.com / S3 (CSP of github.com lists `github-cloud.s3.amazonaws.com`, VERIFIED from response header). Since GitHub's own page whitelists this bucket under connect-src, GitHub's web app does fetches against it; that does not prove third-party origins are allowed. Could not be tested without a real signed URL: UNVERIFIED. This is why, for GitHub reads, media.githubusercontent.com is the safer choice: its CORS is verified. For GitLab, `api/v4/.../raw?lfs=true` is verified CORS-open and avoids the object-storage hop entirely (server-side resolves LFS).

## 2. (a) Opt-in user-configured CORS proxy

Pattern: isomorphic-git/cors-proxy (https://github.com/isomorphic-git/cors-proxy). It forwards only requests that look like git smart-HTTP, adds ACAO, and (for auth) moves credentials into `X-Authorization` because the proxied request itself needs `Authorization`. Its public instance is run by a sponsor; not an option for SteleKit tokens. Designed for a self-deployed Node/micro server (not Workers).

What a proxy fixes: the preflight. The Worker answers OPTIONS itself with ACAO + allow-headers, then forwards the real request. It must also handle the object-storage hrefs if those lack CORS (UNVERIFIED), by rewriting hrefs in the batch response to proxied URLs, so upload/download bytes also transit it (cost: bandwidth through the user's Worker; Cloudflare Workers free tier 100k requests/day, 10 ms CPU, request body limits of 100 MB on free/Pro plans, INFERRED from Cloudflare docs, re-verify before relying).

Template (Cloudflare Workers, module syntax; Deno Deploy is near-identical using `Deno.serve`). Illustrative, not created as a file:

```js
// Allowlisted LFS-only CORS proxy. Set ALLOWED_ORIGIN, HOSTS in Worker vars.
const HOSTS = new Set(["github.com", "gitlab.com", "codeberg.org"]); // exact hostnames only
const LFS_PATH = /^\/[^/]+\/[^/]+\.git\/info\/lfs\/objects\/batch$/;
export default {
  async fetch(req, env) {
    const origin = req.headers.get("Origin");
    const cors = {
      "Access-Control-Allow-Origin": env.ALLOWED_ORIGIN,       // e.g. https://stapler.dev ; never "*"
      "Access-Control-Allow-Headers": "Authorization, Content-Type, Accept, X-Target",
      "Access-Control-Allow-Methods": "POST, GET, PUT, OPTIONS", "Vary": "Origin",
    };
    if (origin !== env.ALLOWED_ORIGIN) return new Response("forbidden", { status: 403 });
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    const target = new URL(req.headers.get("X-Target") || "");
    const isBatch = HOSTS.has(target.hostname) && LFS_PATH.test(target.pathname);
    const isObject = target.protocol === "https:" && env.OBJECT_HOSTS.split(",").includes(target.hostname);
    if (!isBatch && !isObject) return new Response("blocked", { status: 403 });
    const h = new Headers(req.headers); h.delete("Origin"); h.delete("X-Target");
    const up = await fetch(target, { method: req.method, headers: h, body: req.body, redirect: "manual" });
    const out = new Response(up.body, up);                      // stream, do not buffer
    for (const [k, v] of Object.entries(cors)) out.headers.set(k, v);
    return out;
  },
};
```

Hardening requirements: Origin pinned to the one SteleKit origin (browser-enforced, but also checked server-side; non-browser clients can forge Origin, so also require the Authorization header present and never relay without it, otherwise it is an open anonymous proxy); exact-hostname allowlist (SSRF: block IP literals, `localhost`, redirects: `redirect: "manual"`); path allowlist (batch path regex; object hosts only for hrefs actually returned by a batch call, ideally an HMAC of hrefs issued by the proxy); methods limited; request size cap; no logging of headers.

Trade-offs
- Token exposure: the user's GitHub/GitLab PAT (already held in browser storage for ADR-015) is sent to a third-party Worker the user controls. The Worker owner sees every token. A user-owned deployment has the same trust boundary as the user's own account, a shared community proxy does not; recommend never defaulting to a shared one.
- Abuse: public Worker URL = anyone can try it. With Origin lock + required Authorization + allowlists, remaining abuse is bounded to LFS batch/object traffic of tokens the abuser already has; cost falls to the Worker owner's free quota.
- Differs from "infrastructure SteleKit hosts": it is configured by the user in Settings (URL field, default empty), SteleKit ships only a template and docs, no SteleKit-run service; ADR-015's "no extra infrastructure in v1" is qualified (opt-in, user-owned) rather than broken, but needs a new ADR that supersedes the "no CORS proxy" line (requirements.md constraint already says so).
- Cost to SteleKit: settings field, proxy-aware `LfsClient`, href rewriting, template docs, mocked-endpoint tests. Cost to the user: a Cloudflare/Deno account and one deploy.

## 3. (b) Host-native paths (no CORS involved)

- GitHub Contents API (`PUT /repos/:o/:r/contents/:path`) does not create LFS objects. It commits the bytes as a normal blob (limit: 100 MB for create/update per docs; reads 1 MB via JSON, up to 100 MB with `application/vnd.github.raw`). INFERRED from docs; the Git Data API blobs call used at WasmGitWriteService.kt:464 behaves the same: stores raw blob. There is no LFS flag on any REST endpoint of either host (searched GitHub and GitLab API docs; none found).
- Workflow converter (REAL, no proxy), GitHub:
  1. Browser creates blob/tree/commit via Git Data API on a staging ref such as `refs/heads/stelekit/lfs-inbox/<id>` (never main), raw binary included.
  2. Browser calls `POST /repos/:o/:r/actions/workflows/stelekit-lfs.yml/dispatches` (preflight VERIFIED `ACAO: *`, 204) with the staging ref as input. Needs token scope `actions:write` (fine-grained) or `workflow` (classic).
  3. Workflow (`actions/checkout` with `lfs: true`, `git lfs install`, `git lfs track` per `.gitattributes`, `git add --renormalize`, commit with pointers, `git push` to the target branch, delete staging ref). `GITHUB_TOKEN` with `contents: write` can push LFS objects.
  Costs: workflow file must exist in the user's repo (SteleKit can commit it itself through the Git Data API, token needs `workflow` scope to create files under `.github/workflows`), Actions minutes (free tier covers it), latency tens of seconds, async result (UI must poll the ref), the raw blob stays as unreachable object on GitHub for a while (privacy: raw bytes were uploaded to the git store, not LFS; for repos that moved to LFS for size reasons this bloats until GC). It contradicts the "never upload raw binary" safety floor in a strict reading, so the plan must phrase the floor as "never commit raw binary to a branch that people consume; staging ref is explicitly allowed".
- GitLab equivalent: raw commit to a staging branch via Commits API, `POST /projects/:id/pipeline` or trigger API (CORS `ACAO: *` VERIFIED on /pipeline preflight), `.gitlab-ci.yml` job pushes with `CI_JOB_TOKEN` or a project access token. Same costs, plus needing CI enabled and a runner.
- `git lfs migrate` is out of scope (requirements) and rewrites history; the converter above only rewrites its own staging commit.
- `repository_dispatch`: `api.github.com/repos/o/r/dispatches` preflight VERIFIED 204 ACAO `*`. Usable as the trigger alternative.
- LFS servers that allow CORS: Bitbucket and Azure DevOps (VERIFIED preflight above), where direct browser LFS works with no proxy (object hrefs UNVERIFIED). Self-hosted Giftless with CORS enabled (INFERRED). Forgejo/Gitea/GitHub/GitLab: no.

## 4. (c) Origin tricks

- Same-origin proxy under GitHub Pages: impossible. Pages is static (pages.yml uses `actions/upload-pages-artifact@v3` + `actions/deploy-pages@v4`, lines ~241-265 of `.github/workflows/pages.yml`, VERIFIED by reading). No server code, no rewrites/headers.
- Service worker: cannot help. A SW's `fetch()` to a cross-origin URL is subject to the same CORS rules; `no-cors` yields opaque responses (status 0, body unreadable) and cannot send `Authorization` or a JSON content type. A SW could only proxy to an upstream that already allows CORS, which is the same constraint. Impossible.
- Browser extension: can bypass CORS with host permissions, but is not available to the hosted PWA, per-browser, store review; Not a viable general design. Possible as a power-user option only.
- WebTransport/WebSocket/WebRTC: LFS is HTTPS; GitHub/GitLab expose no WebSocket/WebTransport LFS endpoint. Impossible.
- Hosting a same-origin proxy: would need moving the site from Pages to Cloudflare Pages/Workers (or adding a Worker route on a custom domain). Cost: new hosting account and deploy workflow, owned infra, and SteleKit would then handle users' tokens. Requirements mark SteleKit-hosted infrastructure out of scope unless the owner decides.

## 5. (d) Read path

Current code (VERIFIED by reading `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`):
- `fetchRemoteFileContent` (line 346) is the single remote read: GitHub -> `raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>` (line 353), GitLab -> `${apiBase}/repository/files/<path>/raw?ref=<ref>` (line ~357). It returns `String` via `bodyAsText()` (line ~375 in `fetchRawUrl`, 363). Two defects for LFS: (1) LFS content comes back as the ~130-byte pointer text; (2) binary files are decoded as text, so it cannot carry binary regardless.
- Callers: `checkoutFile` (line 302) and `mergeAndCommit`/`mergeGitHub` (lines 667/602) via `applyRemoteContent`. Initial population is `WasmGitRepository.clone` (line 245) and `fetch/merge` delegates (lines 115-145).
- Hooks needed: (1) after `fetchRawUrl` returns bytes, run pointer detection and, if a pointer, call a new `LfsObjectResolver.download(hostConfig, pointer)`; (2) `createBlob` (line 457) and `commitGitHub` (385) must consult `.gitattributes` and refuse/redirect LFS-tracked paths (safety floor); (3) `pushViaGitLab` (991) the same for the Commits API actions; (4) PlatformFileSystem write needs a binary path (bytes not String) and a pointer-vs-content cache.

Pointer detection, cheap: LFS pointers are < 1024 bytes by spec (https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md); check `size < 1024`, then the first 29 bytes `version https://git-lfs.github.com/spec/v1`, then parse `oid sha256:<64 hex>` and `size <n>`. Real pointers are ~130 bytes. Use the Git blob size from the tree listing (no download) as the first filter; only fetch small blobs under paths matched by `.gitattributes` `filter=lfs` (the attribute is the authoritative gate, the prefix is the confirm).

Resolution per host:
- GitHub, public repo: `POST github.com/<o>/<r>.git/info/lfs/objects/batch` with `text/plain`, no auth -> download href -> object GET (object-storage CORS UNVERIFIED), or simpler `GET media.githubusercontent.com/media/<o>/<r>/<ref>/<path>` (CORS VERIFIED `*`, accepts Authorization in preflight). Prefer media URL.
- GitHub, private repo: media.githubusercontent.com allows the Authorization header in preflight (VERIFIED), search results say `download_url` from Contents API for private repos includes a short-lived `?token=` (from a web search snippet, not primary docs, UNVERIFIED). Actual private-repo behaviour with a PAT cannot be tested without a private repo. Plan: Contents API `GET /repos/:o/:r/contents/:path?ref=` (CORS ok) and use its `download_url` (a media.githubusercontent.com URL for LFS files, tokenised for private repos); fall back to the proxy path if 401/403. Open Question 3 stays open: needs a one-time manual test by the owner with a private LFS repo.
- GitLab (public or private): `GET api/v4/projects/:id/repository/files/:path/raw?ref=<ref>&lfs=true` with `PRIVATE-TOKEN`/`Authorization` (VERIFIED CORS `*`, allows those headers; docs quote: "If true, determines if the response should be Git LFS file contents, rather than the pointer", https://docs.gitlab.com/api/repository_files/). Not verified end-to-end against a real LFS file (none found in a public project quickly). The `/-/raw/...?lfs=true` web route has no CORS (VERIFIED) so it is not usable.
- Contents API pointer behaviour: returns the pointer for LFS files (search result summary; INFERRED), `download_url` for media. Limits: 1 MB inline JSON, 100 MB with raw media type (docs.github.com Contents API).
- Browser memory: wasm heap ~2-4 GB total hard cap; large objects should stream into OPFS (`FileSystemSyncAccessHandle` in a worker, or `createWritable()` from main thread) and be exposed to the UI as blob URLs; set a default per-object cap (e.g., 50 MB, decision pending: requirements Open Question 2) and show a "download" affordance above it. OPFS quota is browser-dependent (typically a large fraction of free disk in Chromium; Safari lower, INFERRED; not measured here). Cache key: oid (sha256), which is content-addressed so cache never goes stale; verify sha256 of downloaded bytes against pointer oid before writing.

## 6. (e) Producing an LFS object from a pure browser, no proxy

Plainly: no, not by speaking LFS. On github.com and gitlab.com the batch preflight fails (VERIFIED), no REST endpoint creates an LFS object, the github.com web upload flow uses cookie-auth + CSRF on same-origin endpoints that a third-party origin cannot call, and `objects-origin.githubusercontent.com` is CORS-pinned to `https://github.com` (VERIFIED). Release assets (`uploads.github.com`) and GitLab Generic Packages (`ACAO: *` VERIFIED for GitLab) store files but they are not LFS objects and a pointer cannot reference them (pointer oid must resolve via the LFS batch API). The only no-proxy route is the workflow converter in section 3, or a CORS-enabled LFS server (Bitbucket, Azure DevOps, configured Giftless).

## 7. Recommended design

| Capability | GitHub | GitLab | CORS-enabled LFS (Bitbucket, Azure, Giftless) | Self-hosted/Forgejo/Gitea (no CORS) |
|---|---|---|---|---|
| Safety guard (never commit raw binary to LFS-tracked path) | No infra | No infra | No infra | No infra |
| Pointer detection + display placeholder | No infra | No infra | No infra | No infra |
| Read public | No infra (media.githubusercontent.com) | No infra (`api/v4 ... raw?lfs=true`) | No infra (batch + hrefs, hrefs UNVERIFIED) | Needs proxy |
| Read private | Probably no infra via Contents API `download_url` / media + Authorization (UNVERIFIED, owner test needed); else proxy | No infra (`raw?lfs=true` + token) | No infra | Needs proxy |
| Write (upload object + pointer commit) | Option A no infra: staging branch + workflow converter (async, repo workflow file). Option B user proxy: direct batch/upload, synchronous | Option A no infra: staging branch + CI converter. Option B user proxy | No infra, direct from browser | Needs proxy (only way) |
| Direct LFS from browser to host with auth | Impossible without proxy | Impossible without proxy | Possible | Impossible without proxy |
| Same-origin proxy via Pages or service worker | Impossible | Impossible | n/a | Impossible |
| SteleKit-hosted proxy | Out of scope unless owner decides | same | n/a | same |

Recommended: ship the guard everywhere first; read path via host-native CORS-open URLs (no infra); write via workflow converter as the default no-infra web path, plus an optional "LFS proxy URL" setting (template above) that enables synchronous direct LFS and covers self-hosted. Requires a new ADR superseding ADR-015's "no CORS proxy" for the opt-in case.

Open items I could not verify: object-storage CORS for GitHub/GitLab/Bitbucket action hrefs; GitHub private-repo media access with PAT; GitLab `lfs=true` end-to-end on a real LFS blob; Cloudflare Worker body limits; Giftless CORS config; OPFS quotas.
