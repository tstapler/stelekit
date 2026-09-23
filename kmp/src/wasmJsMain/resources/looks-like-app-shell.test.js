// Zero-dependency test using Node's built-in test runner: `node --test looks-like-app-shell.test.js`.
// Wired into CI in .github/workflows/pages.yml, right before the site build that would
// otherwise ship whatever this predicate lets through.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { looksLikeAppShell } = require("./looks-like-app-shell.js");

test("recognizes the real app shell", () => {
    const real = `<!DOCTYPE html>
<html lang="en">
<head><title>SteleKit — Try in Browser</title></head>
<body>
    <div id="loading"><p>Loading SteleKit…</p></div>
    <script src="kmp.js"></script>
</body>
</html>`;
    assert.equal(looksLikeAppShell(real), true);
});

test("rejects the 'coming soon' placeholder — this is the production incident it guards against", () => {
    // Actual content observed live at stelekit.stapler.dev/app/ during the
    // pages.yml cache-eviction regression this session — a valid 200 response
    // that is not the app.
    const comingSoon = `<!DOCTYPE html>
<html lang="en">
<head><title>SteleKit — Web App</title></head>
<body>
    <h1>SteleKit in Your Browser</h1>
    <p>The web app is coming soon.</p>
    <p><a href="https://github.com/tstapler/stelekit">View on GitHub</a> to build and run the desktop app.</p>
</body>
</html>`;
    assert.equal(looksLikeAppShell(comingSoon), false);
});

test("rejects non-string input", () => {
    assert.equal(looksLikeAppShell(undefined), false);
    assert.equal(looksLikeAppShell(null), false);
    assert.equal(looksLikeAppShell(""), false);
});
