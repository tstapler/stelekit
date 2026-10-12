# Stack Research: JVM/Desktop MCP Server for SteleKit

Commit used for all repo citations: `b62aca8a` (`b62aca8a4afad0ea6ecef396f637f4f6028bdea3`).

## 1. Official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`)

**Current version (VERIFIED via Maven Central metadata, 2026-09-14): `0.15.0`**, released
2026-07-28. WebSearch initially surfaced a stale `0.8.3` from an indexed page snippet —
confirmed wrong by querying the live metadata directly:

```
curl -s https://repo1.maven.org/maven2/io/modelcontextprotocol/kotlin-sdk/maven-metadata.xml
# <latest>0.15.0</latest> <release>0.15.0</release>, lastUpdated 20260728160456
```

Published by Anthropic, maintained in collaboration with JetBrains
([modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)).
License: Apache 2.0 for new contributions, MIT for pre-existing code (per repo POM
`<licenses>` block and README).

### Minimum Kotlin/JVM version

READMEbadges state **Kotlin 2.2+** and **JVM 11+**. The repo pins Kotlin
**2.3.21** (`settings.gradle.kts:9-13`) and a JDK 21 toolchain for JVM execution
(`kmp/build.gradle.kts:1051-1052`) — both comfortably satisfy the SDK's stated minimums.

**Caveat found during dependency inspection, not stated in the README**: the
0.15.0 artifacts' POMs declare a transitive `org.jetbrains.kotlin:kotlin-stdlib:2.4.0`
dependency (compile scope, `kotlin-sdk-core-jvm-0.15.0.pom` and
`kotlin-sdk-server-jvm-0.15.0.pom`) — newer than the repo's pinned Kotlin Gradle
plugin (2.3.21). Gradle's default conflict resolution will bump the resolved
`kotlin-stdlib` on the classpath to 2.4.0, which is a different version than the
2.3.21 compiler. This is usually forward-compatible for plain JVM bytecode linkage,
but has not been build-tested here — **flagged as an implementation-phase risk**:
run `./gradlew :kmp:dependencies --configuration jvmRuntimeClasspath` after adding
the SDK and watch for Kotlin metadata-version warnings/errors, or bump the repo's
Kotlin plugin to 2.4.x if it lands cleanly.

### Artifacts and modeling of tools/resources/transport

Three artifacts (`kotlin-sdk` is an umbrella of the other two):
- `io.modelcontextprotocol:kotlin-sdk` — client + server
- `io.modelcontextprotocol:kotlin-sdk-client` — client-only
- `io.modelcontextprotocol:kotlin-sdk-server` — server-only (this is what a
  SteleKit MCP server needs)

**Tools** (`server.addTool(name, description, inputSchema) { request -> CallToolResult(...) }`)
map cleanly onto the requirements.md v1 tool list (search pages/blocks, read a page,
list backlinks, propose create/update) — each is one `addTool` registration whose
lambda body calls into a `PageRepository`/`BlockSearchRepository`/etc. `Either`
result and folds it into a `CallToolResult`.

**Resources** (`server.addResource(uri, name, description, mimeType) { request -> ReadResourceResult(...) }`)
map onto the requirements.md "pages addressable by name/UUID for direct reads" —
one resource registration per page, or a templated resource URI scheme
(`note://<uuid>`), fetched on demand from `PageRepository`.

**Approval-gated writes**: the SDK has no built-in concept of "propose, don't
apply" — that's exactly what requirements.md's Epic-7-reuse-or-co-build decision
is about. A `propose_*` tool handler would construct a pending-suggestion record
and return a `CallToolResult` describing what it queued, never calling
`DatabaseWriteActor`/`GraphWriter` directly — this is an app-level pattern layered
on top of the SDK's `addTool`, not something the SDK provides.

**Transport modeling**: all transports (stdio, Streamable HTTP, SSE, WebSocket,
in-memory `ChannelTransport` for tests) share one `Server`/session API — you pick
the transport at the point you call `server.connect(transport)` /
`server.createSession(transport)`. For stdio:

```kotlin
val transport = StdioServerTransport(
    inputStream = System.`in`.asSource().buffered(),
    outputStream = System.out.asSink().buffered(),
)
server.connect(transport)
```

`ChannelTransport` (in-memory, coroutine-channel-based, no process/network) is
directly useful for the "search/read call returns real graph data end-to-end
against a local SteleKit graph, exercised by an automated test" success metric —
it lets a `businessTest` drive a real `Server` instance without spawning a
subprocess or touching stdin/stdout.

### Dependency footprint (JVM, resolved from Maven Central POMs for 0.15.0)

The README claims "does not add Ktor engine dependencies transitively" — true for
the *engine* (Netty/CIO), but **`kotlin-sdk-server-jvm` does pull in Ktor server
core at compile scope**, confirmed by fetching and reading the published POMs:

| Module | Scope | Dependency | Version |
|---|---|---|---|
| `kotlin-sdk-core-jvm` | compile | `kotlinx-serialization-json-jvm` | 1.11.0 |
| `kotlin-sdk-core-jvm` | compile | `kotlinx-coroutines-core-jvm` | 1.11.0 |
| `kotlin-sdk-core-jvm` | compile | `kotlinx-io-core-jvm` | 0.9.1 |
| `kotlin-sdk-core-jvm` | compile | `kotlinx-collections-immutable-jvm` | 0.5.1 |
| `kotlin-sdk-core-jvm` | runtime | `ktor-server-websockets-jvm` | 3.5.1 |
| `kotlin-sdk-core-jvm` | runtime | `kotlin-logging-jvm` (io.github.oshai) | 8.0.4 |
| `kotlin-sdk-server-jvm` | compile | `kotlin-sdk-core-jvm` | 0.15.0 |
| `kotlin-sdk-server-jvm` | compile | `ktor-server-core-jvm` | 3.5.1 |
| `kotlin-sdk-server-jvm` | compile | `ktor-server-sse-jvm` | 3.5.1 |
| `kotlin-sdk-server-jvm` | runtime | `ktor-server-content-negotiation-jvm` | 3.5.1 |
| `kotlin-sdk-server-jvm` | runtime | `ktor-serialization-kotlinx-json-jvm` | 3.5.1 |

`StdioServerTransport` itself lives in `kotlin-sdk-server-jvm`
(confirmed by inspecting the published jar:
`unzip -l kotlin-sdk-server-jvm-0.15.0.jar | grep -i stdio` →
`io/modelcontextprotocol/kotlin/sdk/server/StdioServerTransport*.class`), **not**
in `kotlin-sdk-core-jvm`. So even a pure-stdio server needs `kotlin-sdk-server`
(or the umbrella `kotlin-sdk`), which drags `ktor-server-core` + `ktor-server-sse`
onto the compile classpath — no Ktor *engine* artifact (`ktor-server-netty`,
`ktor-server-cio`, etc.) is required since stdio never binds a socket, but the
Ktor server framework classes themselves ship in the app's JAR either way. This is
new surface area for the repo (see §2) — a real footprint cost worth naming
explicitly for the planning phase, not a "just add one dependency" change.

## 2. Reusable pieces already in `kmp/build.gradle.kts` (jvmMain)

No MCP or Ktor **server** dependency exists in the repo today
(`grep -n ktor kmp/build.gradle.kts` finds only *client* artifacts). Relevant
existing pins, all in [`kmp/build.gradle.kts`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/kmp/build.gradle.kts):

| Dependency | Repo pin | Location | SDK 0.15.0 wants | Delta |
|---|---|---|---|---|
| `kotlinx-serialization-json` | 1.10.0 | [`kmp/build.gradle.kts:96`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/kmp/build.gradle.kts#L96) | 1.11.0 | SDK newer; Gradle will resolve up |
| `kotlinx-coroutines-core` | 1.10.2 | [`kmp/build.gradle.kts:94`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/kmp/build.gradle.kts#L94) | 1.11.0 | SDK newer; Gradle will resolve up |
| `ktor-client-*` | 3.1.3 | [`kmp/build.gradle.kts:120-122,167`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/kmp/build.gradle.kts#L120-L122) | `ktor-server-*` 3.5.1 (different artifacts, same Ktor release train) | No conflict — client vs. server artifacts don't collide, but this confirms **no ktor-server dependency exists yet**; it would be new |
| `kotlinx-io-core` | *(absent)* | — | 0.9.1 | New transitive dependency for the repo |

None of the existing Ktor usage is a server — it's all HTTP *clients* for LLM
providers (`ClaudeLlmFormatterProvider.kt`, `GeminiLlmFormatterProvider.kt`,
`OpenAiLlmFormatterProvider.kt`, `GoogleApiClient.kt`, etc., all under
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/`) plus Coil's image loading
(`coil-network-ktor3`, [`kmp/build.gradle.kts:117-122`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/kmp/build.gradle.kts#L117-L122)). None of it is reusable for standing up the MCP
*server* side — that requires the new `kotlin-sdk-server` + Ktor server-core
dependency discussed in §1.

### Closest existing headless-process pattern: `SyncMain.kt`

[`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncMain.kt`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncMain.kt)
is the right template to imitate for an MCP server `main()`:

- `main(args)` creates a root `Job()`, registers a JVM shutdown hook that cancels
  it and joins with a timeout, then calls `runBlocking(rootJob) { ... }`
  (`SyncMain.kt:23-36`) — the same shape an MCP stdio server needs, except the
  server variant would *not* exit after one operation; it would `server.connect(transport)`
  and then suspend indefinitely serving requests until stdin closes / the process
  is killed.
- Opens its own `GraphManager(settings, DriverFactory(), fileSystem)` directly
  against a graph path (`SyncMain.kt:69,89-91`) rather than attaching to a running
  desktop app's instance — this directly informs requirements.md open question 3
  (independent process vs. shared app process): `SyncMain` already establishes the
  "standalone process opens its own `GraphManager`/DB connection" pattern as
  precedent in this codebase.
- Uses `try { ... } finally { graphManager.shutdown() }` (`SyncMain.kt:72-78`) to
  guarantee clean DB shutdown — the same pattern the MCP server needs for its
  shutdown-hook path.
- JSON output goes through a small `SyncOutput` helper
  (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncOutput.kt`), analogous to
  how MCP tool responses would serialize `CallToolResult`/`TextContent` via
  `kotlinx.serialization`.
- Wired into Gradle as a `JavaExec` task pinned to a real JDK 21 launcher (not the
  Gradle daemon's JVM) — `kmp/build.gradle.kts:1056-1066` (`runSync` task) — an
  MCP server task (`runMcpServer`, say) would follow the identical
  `JavaExec`-with-`jdk21Launcher` shape.
- **Not yet ported to Bazel**: `grep -n "SyncMain" kmp/BUILD.bazel` finds nothing;
  only `desktop_app` has a Bazel target (`kmp/BUILD.bazel:30-31`,
  `//kmp/src/jvmMain/kotlin:desktop_app`). An MCP server binary would need a new
  Bazel `kt_jvm_binary` target created from scratch — there's no existing
  CLI-binary Bazel target to copy structurally, only the Gradle `JavaExec` one.

## 3. Kotlin/KMP/Gradle versions pinned in this repo

From [`settings.gradle.kts:9-13`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/settings.gradle.kts#L9-L13) and
[`gradle/wrapper/gradle-wrapper.properties`](https://github.com/tstapler/stelekit/blob/b62aca8a4afad0ea6ecef396f637f4f6028bdea3/gradle/wrapper/gradle-wrapper.properties):

| Tool | Version |
|---|---|
| Kotlin (multiplatform/jvm/android/compose/serialization plugins) | 2.3.21 |
| Gradle wrapper | 9.5.0 |
| Android Gradle Plugin | 8.13.2 |
| Compose Multiplatform | 1.10.3 |
| SQLDelight | 2.3.2 |
| JVM toolchain (used by `runSync`/would-be `runMcpServer`) | 21 |

Compatible with the MCP SDK's stated **Kotlin 2.2+ / JVM 11+** minimums. The one
open item is the kotlin-stdlib 2.4.0 transitive-dependency delta noted in §1 —
worth a real build+`./gradlew :kmp:dependencies` check in the implementation
phase rather than assuming it's a non-issue.

## 4. Stdio transport dependency requirements

Confirmed directly from the published artifacts (§1): stdio transport needs
**`io.modelcontextprotocol:kotlin-sdk-server`** (not just `kotlin-sdk-client` or a
hypothetical stdio-only artifact — there isn't one), because `StdioServerTransport`
is compiled into the `server` module alongside the HTTP/SSE/WebSocket transports.
That, in turn, requires `ktor-server-core` + `ktor-server-sse` at compile time.

No additional dependency beyond that is needed to *run* stdio specifically — no
Ktor engine (Netty/CIO), no separate stdio library. `System.in`/`System.out` are
wrapped via `kotlinx-io`'s `asSource()`/`asSink()` extensions
(`kotlinx-io-core-jvm:0.9.1`, already a transitive dependency of
`kotlin-sdk-core-jvm`), so no manual kotlinx-io dependency declaration is needed
either — it comes in transitively.

## Summary for planning

1. Add `io.modelcontextprotocol:kotlin-sdk-server:0.15.0` (JVM-only, in
   `kmp/build.gradle.kts`'s `jvmMain` source set, not `commonMain` — v1 is
   Desktop/JVM-only per requirements.md) as a *new* dependency; it transitively
   pulls Ktor server-core/SSE 3.5.1, `kotlinx-io-core` 0.9.1, and bumps
   `kotlinx-serialization-json`/`kotlinx-coroutines-core` resolution to 1.11.0 —
   none of which currently exist as server-side dependencies in this repo (only
   Ktor *client* is used today, for LLM provider HTTP calls).
2. Model `main()` on `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/cli/SyncMain.kt`
   (`Job()` + shutdown hook + `runBlocking` + own `GraphManager` +
   `finally { graphManager.shutdown() }`), wired as a new Gradle `JavaExec` task
   the same way `runSync` is (`kmp/build.gradle.kts:1056-1066`); there is no Bazel
   precedent for a CLI/server binary target yet, so Bazel support would be new
   work, not a port.
3. Before implementation, run a real `./gradlew :kmp:dependencies` after adding
   the SDK to confirm the Kotlin 2.3.21-plugin / kotlin-stdlib-2.4.0-transitive
   delta resolves cleanly — this was found by reading POMs, not by a build, and
   is the one unverified compatibility claim in this document.
