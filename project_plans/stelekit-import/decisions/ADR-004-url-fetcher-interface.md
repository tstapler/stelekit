# ADR-004: UrlFetcher expect/actual Interface for Offline-First URL Fetching

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The URL import path requires fetching HTML from a user-provided URL and converting it to plain text. The requirements specify offline-first behavior: paste import must work without internet, and URL fetching must degrade gracefully on failure rather than blocking the paste flow.

SteleKit already declares `ktor-client-core` as a transitive dependency (via `coil-network-ktor3`) and per-platform engines in every target (`ktor-client-okhttp` on JVM/Android, `ktor-client-darwin` on iOS). No new HTTP dependency is needed.

The design question is how to abstract the URL fetching so it is:
1. Mockable in unit tests without real network calls
2. Degradable — returns a typed failure rather than throwing when the network is unavailable
3. Deferred on platforms not yet implementing it (iOS v1 scope)

Two options were evaluated:

**Option A — `UrlFetcher` interface in `commonMain` with a production implementation in `jvmMain`**: Define a `UrlFetcher` interface and a `FetchResult` sealed class in `commonMain`. Provide `UrlFetcherJvm` in `jvmMain` using ktor-client + ksoup. Inject the interface into `ImportViewModel` so tests use a fake.

**Option B — Direct ktor-client call inside `ImportViewModel`**: No interface abstraction. The ViewModel calls `httpClient.get(url)` directly. Simpler, fewer files, but not mockable without ktor's `MockEngine` and harder to substitute on other platforms.

---

## Decision

**Option A: `UrlFetcher` interface in `commonMain` + `UrlFetcherJvm` in `jvmMain`.**

```kotlin
// commonMain
interface UrlFetcher {
    suspend fun fetch(url: String): FetchResult
}

sealed class FetchResult {
    data class Success(val text: String, val pageTitle: String?) : FetchResult()
    sealed class Failure : FetchResult() {
        object Timeout : Failure()
        object NetworkUnavailable : Failure()
        data class HttpError(val code: Int) : Failure()
        object ParseError : Failure()
        object TooLarge : Failure()
    }
}
```

A `NoOpUrlFetcher` in `commonMain` returns `FetchResult.Failure.NetworkUnavailable` immediately — used in tests and as the default on platforms without a real implementation.

---

## Rationale

1. **Offline-first by design**: The `FetchResult` sealed class makes the failure path explicit and typed. `ImportViewModel` handles each failure variant with a specific UI message. There is no uncaught exception path that could crash or silently corrupt state.

2. **Testability without network**: Injecting `UrlFetcher` as an interface allows `ImportViewModelTest` to use a fake that returns any desired `FetchResult` without starting an HTTP server or using `MockEngine`. This keeps the ViewModel test suite in `businessTest` (no JVM-specific test infrastructure).

3. **Platform deferral**: iOS, Android, and Web implementations can be added independently without touching `commonMain` or `ImportViewModel`. The `NoOpUrlFetcher` placeholder keeps the app functional on unimplemented platforms (paste import is always available; URL import degrades to a "not supported on this platform" message).

4. **Typed error model over exceptions**: Mapping ktor exceptions (`HttpRequestTimeoutException`, `UnresolvedAddressException`, etc.) to a sealed class at the boundary of `UrlFetcherJvm` keeps the rest of the codebase free of ktor-specific exception types. This is the same pattern used by `GraphWriter.savePageInternal` which returns `Result<Unit>` rather than throwing.

5. **URL scheme validation**: The interface contract requires implementors to validate the URL scheme. Only `http://` and `https://` are permitted. `file://`, `jar://`, and other schemes must return `FetchResult.Failure.HttpError(0)`. This is documented in the interface KDoc.

6. **Reject Option B**: A direct ktor-client call in the ViewModel couples the ViewModel to a specific HTTP library, requires `MockEngine` in ViewModel tests (a JVM-only test dependency), and makes it harder to add platform-specific implementations later without refactoring the ViewModel.

---

## Consequences

**Positive**:
- `ImportViewModel` has no knowledge of HTTP libraries; it only sees `FetchResult`
- Adding Android or iOS implementations is an additive change (new file in platform source set)
- `FetchResult.Failure` variants drive specific UI copy in `ImportScreen` without a string-parsing step

**Negative / Risks**:
- One additional interface file (`UrlFetcher.kt`) and one additional implementation file (`UrlFetcherJvm.kt`) versus the inline approach
- `UrlFetcherJvm` must create and manage an `HttpClient` instance; lifecycle management (close on graph unload) needs to be handled — the client is lightweight and can be created per-import or held in the ViewModel scope
- iOS v1 ships with `NoOpUrlFetcher`; URL import is silently unavailable on iOS until `UrlFetcherIos.kt` is implemented (documented in v2 backlog)
