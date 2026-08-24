// Raises Mocha's default per-test timeout (2000ms) for wasmJs browser tests. Several
// tests in dev.stapler.stelekit.platform intentionally exercise real, wall-clock-bound
// browser APIs (Web Locks API via navigator.locks.request, large (8,030-file) synchronous
// reconciliation walks) that legitimately exceed 2s — see HostDirectoryPollerBenchmarkTest
// and HostDirectorySyncReconciliationBenchmarkTest. Karma deep-merges config.set() calls
// (lodash mergeWith), so this does not clobber the "client.args" test filter set elsewhere.
config.set({
    client: {
        mocha: {
            timeout: 60000
        }
    }
});
