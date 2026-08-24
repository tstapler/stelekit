# Web (Wasm/JS) E2E tests

Playwright suite against the built `wasmJsBrowserDistribution` bundle, served locally
via `server.mjs` (sets the COOP/COEP headers the wasm build needs for
`crossOriginIsolated`).

## Running locally

```bash
# Build the bundle the suite serves (defaults to
# kmp/build/dist/wasmJs/productionExecutable — override with DEMO_DIST)
./gradlew :kmp:wasmJsBrowserDistribution -PenableJs=true

cd e2e
npm ci
npx playwright install --with-deps chromium

npm test                              # tests/demo.spec.ts + tests/benchmark.spec.ts
npx playwright test tests/demo.spec.ts        # functional suite only (what CI runs)
npx playwright test tests/benchmark.spec.ts   # timing benchmarks (not run in CI — see below)
npm run test:headed                   # watch it run in a real browser window
npm run test:ui                       # Playwright's interactive UI mode
```

## What's covered today

- `tests/demo.spec.ts` — wasm/Compose boot (canvas attaches, resizes, WebGL context
  present, no uncaught errors), OPFS persistence across reload, graph-picker dialog
  mode, named-graph OPFS override. This is the suite CI runs on every non-draft PR
  (`wasmjs-e2e` job in `.github/workflows/ci.yml`).
- `tests/benchmark.spec.ts` — wasm init time, journal render time, page-nav latency,
  block-edit round trip. **Not run in CI** — timing assertions are tuned for local
  hardware and are too flaky on shared runners to gate PRs on. Run manually when
  investigating a perf regression, or via `./scripts/benchmark-local.sh` for the JVM
  side of the same story.

## The canvas constraint

Compose Multiplatform for Web renders everything to a single `<canvas>` inside a
shadow root attached to `document.body` — not to semantic DOM elements. Standard
Playwright locators (`getByText`, `getByRole`, etc.) cannot see rendered UI content
as a result. The existing specs work around this via:

- Injected JS globals the app sets on boot/state changes (`window.__stelekit_ready`,
  `window.__stelekit_native_graph_picker`, `window.__stelekit_driver_backend`, ...)
- Canvas presence/resize and WebGL-context checks as boot proxies
- OPFS inspection (`navigator.storage.getDirectory()`) to verify persistence directly,
  bypassing rendering entirely

None of the current specs assert on actual rendered note content (page titles, block
text) — there's no DOM path to read it today. Writing real user-journey tests (create
page → edit block → verify text persisted → reload → re-verify) needs either more
purpose-built test-hook globals, or exposing Compose's semantics tree as accessible DOM
nodes so Playwright can query it directly. Tracked as follow-up work.
