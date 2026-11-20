/*!
 * SteleKit service worker.
 *
 * Combines two concerns in one fetch handler because only one service worker
 * can control this scope:
 *
 * 1. Cross-origin isolation header injection — a straight port of
 *    coi-serviceworker v0.1.7 (github.com/gzuidhof/coi-serviceworker, MIT).
 *    GitHub Pages does not set COOP/COEP headers, and the WASM app needs
 *    cross-origin isolation for SharedArrayBuffer.
 * 2. Offline caching of the static app shell (JS/WASM/HTML/icons) for PWA installability
 *    and offline durability. User data lives in OPFS, not in this cache.
 *
 *    Content-hashed wasm files are immutable, so they are served cache-first. Everything
 *    else is network-first: a cache-first kmp.js outlives the wasm file it names (each
 *    deploy deletes the old one), which 404s and blanks the page. A network response only
 *    wins if it is ok and, for documents, passes looksLikeAppShell() (see
 *    looks-like-app-shell.js); otherwise the cached copy is served, so a bad deploy (e.g.
 *    the "coming soon" placeholder, still a 200) neither reaches nor poisons a returning user.
 */
const CACHE_NAME = "stelekit-app-shell-v1";
const HASHED_WASM = /\/[0-9a-f]{16,}\.wasm$/;
// A slow network must not hold a returning user's cached shell hostage; the fetch keeps going and refreshes the cache.
const NETWORK_WAIT_MS = 6000;

let coepCredentialless = false;

if (typeof window === "undefined") {
    // ── Service worker context ──────────────────────────────────────────────
    importScripts("looks-like-app-shell.js");

    self.addEventListener("install", () => self.skipWaiting());
    // Network-first refreshes mutable assets; keep the offline shell across worker updates.
    self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

    self.addEventListener("message", (ev) => {
        if (!ev.data) {
            return;
        } else if (ev.data.type === "deregister") {
            self.registration
                .unregister()
                .then(() => self.clients.matchAll())
                .then((clients) => clients.forEach((client) => client.navigate(client.url)));
        } else if (ev.data.type === "coepCredentialless") {
            coepCredentialless = ev.data.value;
        }
    });

    function patchIsolationHeaders(response) {
        const newHeaders = new Headers(response.headers);
        newHeaders.set("Cross-Origin-Embedder-Policy", coepCredentialless ? "credentialless" : "require-corp");
        if (!coepCredentialless) {
            newHeaders.set("Cross-Origin-Resource-Policy", "cross-origin");
        }
        newHeaders.set("Cross-Origin-Opener-Policy", "same-origin");
        return new Response(response.body, {
            status: response.status,
            statusText: response.statusText,
            headers: newHeaders,
        });
    }

    // Drop cached wasm the cached kmp.js no longer names, so deploys don't pile up 5 MB each. Runs only once every
    // wasm it does name is cached, so an interrupted download can't leave an offline shell with nothing to load.
    async function pruneUnreferencedWasm(cache) {
        const keys = await cache.keys();
        const kmpKey = keys.find((k) => /\/kmp\.js$/.test(new URL(k.url).pathname));
        const kmp = kmpKey && (await cache.match(kmpKey));
        if (!kmp) return;
        const wanted = new Set((await kmp.text()).match(/[0-9a-f]{16,}\.wasm/g) || []);
        const cachedNames = new Set(
            keys.map((k) => new URL(k.url).pathname).filter((path) => HASHED_WASM.test(path)).map((path) => path.slice(path.lastIndexOf("/") + 1))
        );
        if (wanted.size === 0 || [...wanted].some((name) => !cachedNames.has(name))) return;
        for (const key of keys) {
            const path = new URL(key.url).pathname;
            if (HASHED_WASM.test(path) && !wanted.has(path.slice(path.lastIndexOf("/") + 1))) {
                await cache.delete(key);
            }
        }
    }

    // Fetches from the network. `trusted` means safe to cache and to prefer over a cached copy; `stored`
    // settles when the cache write is done and must not delay the response, or wasm streaming would stall.
    async function fetchShell(request, cacheKey, cache, isDocument) {
        const response = await fetch(request);
        if (response.status === 0) return { patched: response, trusted: false, stored: Promise.resolve() };
        const patched = patchIsolationHeaders(response);
        // Only a full 200: a 206 partial can't be cached, and a non-document served as HTML is a placeholder or error page.
        let trusted = response.status === 200;
        if (trusted && !isDocument && /text\/html/i.test(response.headers.get("content-type") || "")) trusted = false;
        if (trusted && (isDocument || /\/kmp\.js$/.test(new URL(request.url).pathname))) {
            const text = await patched.clone().text();
            if (text.length === 0 || (isDocument && !looksLikeAppShell(text))) trusted = false;
        }
        let stored = Promise.resolve();
        if (trusted && cache) {
            stored = cache.put(cacheKey, patched.clone()).then(() => (isDocument ? undefined : pruneUnreferencedWasm(cache)));
        }
        return { patched, trusted, stored };
    }

    self.addEventListener("fetch", (event) => {
        const r = event.request;
        if (r.cache === "only-if-cached" && r.mode !== "same-origin") {
            return;
        }

        const cacheable = r.method === "GET" && new URL(r.url).origin === self.location.origin;
        const isDocument = cacheable && (r.mode === "navigate" || r.destination === "document");
        const request = coepCredentialless && r.mode === "no-cors" ? new Request(r, { credentials: "omit" }) : r;

        event.respondWith(
            (async () => {
                if (!cacheable) {
                    const response = await fetch(request);
                    return response.status === 0 ? response : patchIsolationHeaders(response);
                }
                // CacheStorage can be unavailable even when the network works.
                let cache = null;
                let cached = null;
                try {
                    cache = await caches.open(CACHE_NAME);
                    cached = await cache.match(r);
                } catch (e) {
                    cache = null;
                }

                if (cached && HASHED_WASM.test(new URL(r.url).pathname)) {
                    return cached;
                }

                const network = fetchShell(request, r, cache, isDocument);
                if (!cached) {
                    const n = await network;
                    event.waitUntil(n.stored.catch(() => {}));
                    return n.patched;
                }
                // A failed or slow network falls back to the cached copy; the fetch still finishes refreshing the cache.
                event.waitUntil(network.then((n) => n.stored, () => {}).catch(() => {}));
                const preferred = network.then((n) => (n.trusted ? n.patched : cached), () => cached);
                const timeout = new Promise((resolve) => setTimeout(() => resolve(cached), NETWORK_WAIT_MS));
                return Promise.race([preferred, timeout]);
            })().then((response) => response.status === 0 ? response : patchIsolationHeaders(response))
        );
    });
} else {
    // ── Page context — registration, reload-once, and COEP-degrade logic.
    // Unchanged from coi-serviceworker v0.1.7. ───────────────────────────────
    (() => {
        const reloadedBySelf = window.sessionStorage.getItem("coiReloadedBySelf");
        window.sessionStorage.removeItem("coiReloadedBySelf");
        const coepDegrading = reloadedBySelf == "coepdegrade";

        // You can customize the behavior of this script through a global `coi` variable.
        const coi = {
            shouldRegister: () => !reloadedBySelf,
            shouldDeregister: () => false,
            coepCredentialless: () => true,
            coepDegrade: () => true,
            doReload: () => window.location.reload(),
            quiet: false,
            ...window.coi,
        };

        const n = navigator;
        const controlling = n.serviceWorker && n.serviceWorker.controller;

        // Record the failure if the page is served by serviceWorker.
        if (controlling && !window.crossOriginIsolated) {
            window.sessionStorage.setItem("coiCoepHasFailed", "true");
        }
        const coepHasFailed = window.sessionStorage.getItem("coiCoepHasFailed");

        if (controlling) {
            // Reload only on the first failure.
            const reloadToDegrade = coi.coepDegrade() && !(coepDegrading || window.crossOriginIsolated);
            n.serviceWorker.controller.postMessage({
                type: "coepCredentialless",
                value: reloadToDegrade || (coepHasFailed && coi.coepDegrade()) ? false : coi.coepCredentialless(),
            });
            if (reloadToDegrade) {
                !coi.quiet && console.log("Reloading page to degrade COEP.");
                window.sessionStorage.setItem("coiReloadedBySelf", "coepdegrade");
                coi.doReload("coepdegrade");
            }

            if (coi.shouldDeregister()) {
                n.serviceWorker.controller.postMessage({ type: "deregister" });
            }
        }

        // If we're already coi: do nothing. Perhaps it's due to this script doing its job, or COOP/COEP are
        // already set from the origin server. Also if the browser has no notion of crossOriginIsolated, just give up here.
        //
        // ponytail: this also means the offline-caching half of this worker never registers when the host
        // already sets COOP/COEP natively. Not true of GitHub Pages today (verified: no COOP/COEP headers) —
        // revisit by decoupling registration from the isolation check if that host assumption ever changes.
        if (window.crossOriginIsolated !== false || !coi.shouldRegister()) return;

        if (!window.isSecureContext) {
            !coi.quiet && console.log("Service Worker not registered, a secure context is required.");
            return;
        }

        // In some environments (e.g. Firefox private mode) this won't be available
        if (!n.serviceWorker) {
            !coi.quiet && console.error("Service Worker not registered, perhaps due to private mode.");
            return;
        }

        n.serviceWorker.register(window.document.currentScript.src).then(
            (registration) => {
                !coi.quiet && console.log("Service Worker registered", registration.scope);

                registration.addEventListener("updatefound", () => {
                    !coi.quiet && console.log("Reloading page to make use of updated Service Worker.");
                    window.sessionStorage.setItem("coiReloadedBySelf", "updatefound");
                    coi.doReload();
                });

                // If the registration is active, but it's not controlling the page
                if (registration.active && !n.serviceWorker.controller) {
                    !coi.quiet && console.log("Reloading page to make use of Service Worker.");
                    window.sessionStorage.setItem("coiReloadedBySelf", "notcontrolling");
                    coi.doReload();
                }
            },
            (err) => {
                !coi.quiet && console.error("Service Worker failed to register:", err);
            }
        );
    })();
}
