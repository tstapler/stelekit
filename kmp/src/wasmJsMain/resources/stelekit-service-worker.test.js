// Run: node --test stelekit-service-worker.test.js
// Loads the real worker in a vm with an in-memory Cache Storage and fetch, then drives its fetch handler.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const ORIGIN = "https://example.test";
const SHELL = '<html><script src="kmp.js"></script></html>';
const PLACEHOLDER = "<html>coming soon</html>";

function makeWorker({ network, instantTimeout = false }) {
    const store = new Map();
    const cacheApi = {
        match: async (r) => store.get(typeof r === "string" ? r : r.url)?.clone(),
        put: async (r, res) => void store.set(typeof r === "string" ? r : r.url, res),
        keys: async () => [...store.keys()].map((url) => ({ url })),
        delete: async (k) => store.delete(k.url),
    };
    const listeners = {};
    const fetched = [];
    const context = {
        self: { location: { origin: ORIGIN }, addEventListener: (t, f) => (listeners[t] = f), registration: {}, clients: {} },
        importScripts: () => {},
        caches: { open: async () => cacheApi, delete: async () => true },
        fetch: (req) => {
            fetched.push(req.url);
            return network(req.url);
        },
        setTimeout: instantTimeout ? (f) => f() : setTimeout,
        URL, Response, Headers, Request, Promise,
        looksLikeAppShell: require("./looks-like-app-shell.js").looksLikeAppShell,
    };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname, "stelekit-service-worker.js"), "utf8"), context);

    async function request(url, { navigate = false, settle = true } = {}) {
        const waits = [];
        let responded;
        listeners.fetch({
            request: { url, method: "GET", mode: navigate ? "navigate" : "cors", destination: navigate ? "document" : "script", cache: "default" },
            respondWith: (p) => (responded = p),
            waitUntil: (p) => waits.push(p),
        });
        const res = await responded;
        const body = await res.text();
        if (settle) await Promise.allSettled(waits);
        return { body, status: res.status };
    }
    return { store, request, fetched };
}

const ok = (body) => Promise.resolve(new Response(body, { status: 200 }));
const seed = (w, url, body) => w.store.set(url, new Response(body));

test("a cached content-hashed wasm is served without touching the network", async () => {
    const w = makeWorker({ network: () => assert.fail("network used") });
    seed(w, `${ORIGIN}/app/0123456789abcdef0123.wasm`, "cached-wasm");
    assert.equal((await w.request(`${ORIGIN}/app/0123456789abcdef0123.wasm`)).body, "cached-wasm");
});

test("kmp.js comes from the network and refreshes the cache, not the stale cached copy", async () => {
    const w = makeWorker({ network: () => ok("new kmp 1111111111111111aaaa.wasm") });
    seed(w, `${ORIGIN}/app/kmp.js`, "old kmp");
    assert.equal((await w.request(`${ORIGIN}/app/kmp.js`)).body, "new kmp 1111111111111111aaaa.wasm");
    assert.equal(await w.store.get(`${ORIGIN}/app/kmp.js`).text(), "new kmp 1111111111111111aaaa.wasm");
});

test("a new kmp.js drops cached wasm it no longer names once the wasm it does name is cached", async () => {
    const w = makeWorker({ network: () => ok("loads 1111111111111111aaaa.wasm") });
    seed(w, `${ORIGIN}/app/1111111111111111aaaa.wasm`, "keep");
    seed(w, `${ORIGIN}/app/2222222222222222bbbb.wasm`, "stale");
    seed(w, `${ORIGIN}/app/icon.png`, "png");
    await w.request(`${ORIGIN}/app/kmp.js`);
    assert.deepEqual([...w.store.keys()].sort(), [`${ORIGIN}/app/1111111111111111aaaa.wasm`, `${ORIGIN}/app/icon.png`, `${ORIGIN}/app/kmp.js`].sort());
});

test("old wasm survives a kmp.js refresh until the new wasm is cached, then is pruned", async () => {
    const w = makeWorker({ network: (url) => ok(url.endsWith("kmp.js") ? "loads 1111111111111111aaaa.wasm" : "new wasm bytes") });
    seed(w, `${ORIGIN}/app/2222222222222222bbbb.wasm`, "old wasm");
    await w.request(`${ORIGIN}/app/kmp.js`);
    assert.ok(w.store.has(`${ORIGIN}/app/2222222222222222bbbb.wasm`), "old wasm dropped before the new one was cached");
    await w.request(`${ORIGIN}/app/1111111111111111aaaa.wasm`);
    assert.ok(!w.store.has(`${ORIGIN}/app/2222222222222222bbbb.wasm`));
    assert.ok(w.store.has(`${ORIGIN}/app/1111111111111111aaaa.wasm`));
});

test("a script answered with HTML, an empty kmp.js, or a 206 never replaces the cached copy", async () => {
    const html = makeWorker({ network: () => Promise.resolve(new Response("<html>oops</html>", { status: 200, headers: { "content-type": "text/html" } })) });
    seed(html, `${ORIGIN}/app/sqlite-stelekit-worker.js`, "good worker");
    assert.equal((await html.request(`${ORIGIN}/app/sqlite-stelekit-worker.js`)).body, "good worker");
    assert.equal(await html.store.get(`${ORIGIN}/app/sqlite-stelekit-worker.js`).text(), "good worker");

    const empty = makeWorker({ network: () => ok("") });
    seed(empty, `${ORIGIN}/app/kmp.js`, "good kmp");
    assert.equal((await empty.request(`${ORIGIN}/app/kmp.js`)).body, "good kmp");

    const partial = makeWorker({ network: () => Promise.resolve(new Response("part", { status: 206 })) });
    seed(partial, `${ORIGIN}/app/kmp.js`, "good kmp");
    assert.equal((await partial.request(`${ORIGIN}/app/kmp.js`)).body, "good kmp");
    assert.equal(await partial.store.get(`${ORIGIN}/app/kmp.js`).text(), "good kmp");
});

test("a placeholder document is not served over, or cached over, the cached app shell", async () => {
    const w = makeWorker({ network: () => ok(PLACEHOLDER) });
    seed(w, `${ORIGIN}/app/`, SHELL);
    assert.equal((await w.request(`${ORIGIN}/app/`, { navigate: true })).body, SHELL);
    assert.equal(await w.store.get(`${ORIGIN}/app/`).text(), SHELL);
});

test("a real new document replaces the cached one", async () => {
    const fresh = SHELL + "<!--new-->";
    const w = makeWorker({ network: () => ok(fresh) });
    seed(w, `${ORIGIN}/app/`, SHELL);
    assert.equal((await w.request(`${ORIGIN}/app/`, { navigate: true })).body, fresh);
    assert.equal(await w.store.get(`${ORIGIN}/app/`).text(), fresh);
});

test("a 404 or an offline network falls back to the cached copy", async () => {
    const w404 = makeWorker({ network: () => Promise.resolve(new Response("nope", { status: 404 })) });
    seed(w404, `${ORIGIN}/app/kmp.js`, "cached kmp");
    assert.equal((await w404.request(`${ORIGIN}/app/kmp.js`)).body, "cached kmp");

    const off = makeWorker({ network: () => Promise.reject(new TypeError("offline")) });
    seed(off, `${ORIGIN}/app/kmp.js`, "cached kmp");
    assert.equal((await off.request(`${ORIGIN}/app/kmp.js`)).body, "cached kmp");
});

test("a network slower than the wait limit serves the cached copy", async () => {
    const w = makeWorker({ network: () => new Promise(() => {}), instantTimeout: true });
    seed(w, `${ORIGIN}/app/kmp.js`, "cached kmp");
    // The hung fetch never settles, so don't wait on the worker's waitUntil promises here.
    assert.equal((await w.request(`${ORIGIN}/app/kmp.js`, { settle: false })).body, "cached kmp");
});

test("with nothing cached the network response is returned and cached", async () => {
    const w = makeWorker({ network: () => ok(SHELL) });
    assert.equal((await w.request(`${ORIGIN}/app/`, { navigate: true })).body, SHELL);
    assert.equal(await w.store.get(`${ORIGIN}/app/`).text(), SHELL);
});
