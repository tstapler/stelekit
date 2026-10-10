/*!
 * Pure predicate, no service-worker/browser globals — loaded via importScripts()
 * from stelekit-service-worker.js, and unit-tested directly in Node (see
 * looks-like-app-shell.test.js). Keep this file free of `self`/`window`/`caches`
 * so it stays testable without a browser or SW harness.
 *
 * True if `html` looks like SteleKit's real index.html app shell rather than a
 * fallback/placeholder page. A placeholder (e.g. the site's "coming soon" page
 * served from /app/ when the demo build was unavailable) is still valid HTML
 * returned with a 200 — indistinguishable from a real deploy by HTTP status
 * alone. The service worker uses this to avoid caching (and instantly serving)
 * a placeholder over a previously-cached working app shell.
 */
function looksLikeAppShell(html) {
    return typeof html === "string" && html.includes('src="kmp.js"');
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = { looksLikeAppShell };
}
