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
 * 2. Network-first offline caching, for PWA installability and offline
 *    durability (the app is local-first — user data lives in OPFS, not in
 *    this cache — this only covers the static app shell: JS/WASM/HTML/icons).
 *
 * ponytail: one unversioned cache name. Network-first means every successful
 * fetch refreshes the cache, so there's no accumulating staleness to version
 * away. Bump CACHE_NAME manually if that assumption ever needs revisiting.
 */
const CACHE_NAME = "stelekit-app-shell-v1";

let coepCredentialless = false;

if (typeof window === "undefined") {
    // ── Service worker context ──────────────────────────────────────────────
    self.addEventListener("install", () => self.skipWaiting());
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

    self.addEventListener("fetch", (event) => {
        const r = event.request;
        if (r.cache === "only-if-cached" && r.mode !== "same-origin") {
            return;
        }

        // Only same-origin GETs are cached — POST/cross-origin responses can't
        // be cached and don't need an offline fallback here.
        const cacheable = r.method === "GET" && new URL(r.url).origin === self.location.origin;

        const request = coepCredentialless && r.mode === "no-cors" ? new Request(r, { credentials: "omit" }) : r;

        event.respondWith(
            fetch(request)
                .then((response) => {
                    if (response.status === 0) {
                        return response;
                    }

                    const newHeaders = new Headers(response.headers);
                    newHeaders.set("Cross-Origin-Embedder-Policy", coepCredentialless ? "credentialless" : "require-corp");
                    if (!coepCredentialless) {
                        newHeaders.set("Cross-Origin-Resource-Policy", "cross-origin");
                    }
                    newHeaders.set("Cross-Origin-Opener-Policy", "same-origin");

                    const patched = new Response(response.body, {
                        status: response.status,
                        statusText: response.statusText,
                        headers: newHeaders,
                    });

                    if (cacheable && response.ok) {
                        const toCache = patched.clone();
                        caches.open(CACHE_NAME).then((cache) => cache.put(r, toCache));
                    }

                    return patched;
                })
                .catch((e) => (cacheable ? caches.match(r).then((cached) => cached || Promise.reject(e)) : Promise.reject(e)))
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
