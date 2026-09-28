# Stack Research: git-smart-sync CLI

## A. Parent-Dir Walking for `.git` Detection

### Existing infrastructure
`jvmMain` already has **JGit 7.3.0** on the classpath. JGit's `RepositoryBuilder` walks up the tree looking for a `.git` directory automatically — no manual loop needed:

```kotlin
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

val repo = FileRepositoryBuilder()
    .findGitDir(startDir.toFile())   // walks up until .git found or root reached
    .setMustExist(true)
    .build()
// repo.directory = /path/to/.git
// repo.workTree = /path/to/project
```

`setMustExist(true)` throws `RepositoryNotFoundException` (a `IOException` subclass) if nothing is found — wrap it.

### Manual fallback (if JGit is not available on the CLI module's classpath)
Use `java.io.File` — it is always present on JVM, avoids an extra dependency for a CLI tool:

```kotlin
fun findGitRoot(start: File, maxDepth: Int = 10): File? {
    var current: File? = start.canonicalFile
    repeat(maxDepth) {
        if (current == null) return null
        if (File(current, ".git").exists()) return current
        val parent = current!!.parentFile
        if (parent == null || parent == current) return null   // filesystem root
        current = parent
    }
    return null
}
```

**okio `FileSystem`** — already on the classpath transitively through Ktor/Coil — offers the same functionality with a nicer API but adds no correctness benefit for simple directory walking. Prefer `java.io.File` for the CLI module to keep the dependency footprint minimal and avoid bringing in okio explicitly.

### Cross-platform notes
- On Android, paths start under `/data/data/<pkg>` or SAF URIs. `java.io.File` works for internal storage; JGit's `AndroidGitRepositoryBuilder` is the right choice there.
- Symlinks: use `canonicalFile` (resolves symlinks) rather than `absoluteFile` to avoid infinite loops.
- Stop conditions: `parent == null` (Windows drive root) **and** `parent == current` (UNIX `/`) both signal the filesystem root.

---

## B. Anthropic API (HTTP)

### No existing SDK on the classpath
`build.gradle.kts` has no `anthropic-sdk-java`, `anthropic-kotlin`, or any Anthropic package. The project does have **Ktor 3.1.3** (`ktor-client-core`, `ktor-client-okhttp` on JVM) and `kotlinx-serialization-json:1.10.0` — use these directly.

### Endpoint
```
POST https://api.anthropic.com/v1/messages
```

### Required headers
```
x-api-key: <YOUR_API_KEY>
anthropic-version: 2023-06-01
content-type: application/json
```

### Minimal non-streaming request body
```json
{
  "model": "claude-opus-4-5",
  "max_tokens": 1024,
  "messages": [
    { "role": "user", "content": "Summarise these git changes: ..." }
  ]
}
```

### Minimal response shape (success)
```json
{
  "id": "msg_...",
  "type": "message",
  "role": "assistant",
  "content": [{ "type": "text", "text": "..." }],
  "model": "claude-opus-4-5",
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 42, "output_tokens": 80 }
}
```
Extract result via `response.content[0].text`.

### Ktor + kotlinx.serialization implementation sketch
```kotlin
// Shared data classes (annotate with @Serializable)
@Serializable data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<Message>
)
@Serializable data class Message(val role: String, val content: String)
@Serializable data class AnthropicResponse(val content: List<ContentBlock>)
@Serializable data class ContentBlock(val type: String, val text: String)

// Client setup (reuse across calls — OkHttp maintains a connection pool)
val client = HttpClient(OkHttp) {
    install(ContentNegotiation) { json() }
}

// Call
val response: AnthropicResponse = client.post("https://api.anthropic.com/v1/messages") {
    header("x-api-key", apiKey)
    header("anthropic-version", "2023-06-01")
    contentType(ContentType.Application.Json)
    setBody(AnthropicRequest("claude-opus-4-5", 1024, listOf(Message("user", prompt))))
}.body()
val text = response.content.firstOrNull { it.type == "text" }?.text ?: ""
```

### Error handling
4xx/5xx: Ktor throws `ResponseException`; `response.status.value` gives the HTTP code.
Rate-limit: `429` — check `retry-after` header or back off exponentially.

---

## C. Ollama HTTP API

### Endpoint
```
POST http://localhost:11434/api/chat
```
(Default port 11434; configurable via `OLLAMA_HOST` env var.)

### Non-streaming request body
```json
{
  "model": "llama3",
  "stream": false,
  "messages": [
    { "role": "user", "content": "Summarise these git changes: ..." }
  ]
}
```
`"stream": false` is the key field — omitting it causes Ollama to return newline-delimited JSON chunks (streaming) instead of a single response object.

### Non-streaming response body
```json
{
  "model": "llama3",
  "created_at": "2024-07-21T12:00:00Z",
  "message": {
    "role": "assistant",
    "content": "Here is the summary..."
  },
  "done": true,
  "done_reason": "stop",
  "total_duration": 1234567890
}
```
Extract result via `response.message.content`.

### Ktor implementation sketch
```kotlin
@Serializable data class OllamaRequest(
    val model: String,
    val stream: Boolean = false,
    val messages: List<Message>
)
@Serializable data class OllamaResponse(val message: Message)

val response: OllamaResponse = client.post("http://localhost:11434/api/chat") {
    contentType(ContentType.Application.Json)
    setBody(OllamaRequest(model, false, listOf(Message("user", prompt))))
}.body()
val text = response.message.content
```

### Availability check
Before making calls, probe `GET http://localhost:11434/api/tags` — returns 200 with model list if Ollama is running, `ConnectException` if not.

---

## D. JVM CLI with Coroutines

### Basic pattern
```kotlin
fun main(args: Array<String>) = runBlocking {
    // suspend funs work directly here
    val result = someAsyncOperation()
    println(result)
}
```
`runBlocking` creates a `BlockingEventLoop` on the calling thread and blocks until the coroutine completes. It is the correct entry point for a CLI; avoid it inside library code.

### SIGINT (Ctrl+C) handling
The JVM installs a default SIGINT handler that calls `System.exit(0)`. This runs JVM shutdown hooks but does **not** cancel in-flight coroutines unless you wire it up:

```kotlin
fun main(args: Array<String>) {
    val job = Job()
    Runtime.getRuntime().addShutdownHook(Thread {
        job.cancel()               // cancels all coroutines in the scope
        runBlocking { job.join() } // wait for clean shutdown
    })

    runBlocking(job) {
        try {
            doWork()
        } catch (e: CancellationException) {
            // normal shutdown path — log if desired, then exit cleanly
        }
    }
}
```

Key points:
- `runBlocking(job)` creates a child coroutine of `job`; cancelling `job` propagates cancellation into `runBlocking`.
- `job.join()` inside the shutdown hook blocks the hook thread until the coroutine finishes, giving in-flight network/file IO a chance to close cleanly.
- HTTP clients (Ktor OkHttp engine) close their connection pools in response to the underlying engine being shut down; calling `client.close()` inside the cancellation handler ensures sockets are released.
- Keep the shutdown hook short — JVM forces termination after ~10 s if hooks do not complete.

### Structured concurrency for parallel API calls
```kotlin
val (summary, tags) = coroutineScope {
    val s = async { callAnthropicApi(diff) }
    val t = async { extractTags(diff) }
    s.await() to t.await()
}
```
`coroutineScope` propagates cancellation in both directions; a failure in either async block cancels the other.

---

## E. Packaging

### Option 1 — Gradle `application` plugin (fat jar + shell script)
Produces `build/install/<name>/bin/<name>` (shell) + `build/install/<name>/lib/*.jar`.

```kotlin
// in the CLI subproject's build.gradle.kts
plugins {
    kotlin("jvm")
    application
}
application {
    mainClass.set("dev.stapler.gitsmartsyncd.MainKt")
}
```

**Pros:**
- No UI runtime required — does not pull in Compose/Skiko/AWT.
- `./gradlew installDist` produces a runnable tree in seconds.
- `./gradlew distTar` / `distZip` produces a redistributable archive.
- Works on any JVM 21+ without bundling a JRE.

**Cons:**
- Requires JVM on target machine.
- Does not produce a single self-contained binary.

### Option 2 — `packageDistributionForCurrentOS` (Compose Desktop)
Produces `.dmg` / `.deb` / `.rpm` / `.msi` with a **bundled JRE** (~200–300 MB).

**Verdict for a CLI-only binary: do NOT use this.**
- Requires the `org.jetbrains.compose` plugin and a `compose.desktop { application { } }` block.
- Bundles the entire Compose/Skiko runtime even for a headless tool.
- Produces a GUI installer artifact, not a standalone CLI binary.
- Build time is significantly longer (~2–5 min vs ~10 s for `installDist`).

### Recommendation
Use the `application` plugin in a dedicated `cliApp` (or `gitSyncCli`) Gradle subproject. Keep it **separate from `kmp/`** to avoid pulling in the Compose/Skiko/AWT dependencies. The `kmp/` module can expose a pure-Kotlin API (no Compose); the CLI subproject depends on that API and wires up the `main()`.

If a single-file native binary is eventually required, GraalVM `native-image` is the path — configure via the `org.graalvm.buildtools.native` Gradle plugin. This is a future concern; `application` is sufficient for the initial CLI.

---

## Dependency Matrix Summary

| Concern | What's already on classpath | What to add |
|---|---|---|
| Git operations | JGit 7.3.0 (jvmMain) | Nothing |
| `.git` root detection | JGit `RepositoryBuilder` | Nothing |
| HTTP client | Ktor 3.1.3 + OkHttp engine (jvmMain) | Nothing (reuse) |
| JSON serialization | `kotlinx-serialization-json:1.10.0` | Nothing |
| Anthropic API | — (HTTP only, no SDK) | Nothing (use Ktor) |
| Ollama API | — | Nothing (use Ktor) |
| Coroutines | `kotlinx-coroutines-core:1.10.2` | Nothing |
| CLI packaging | — | `application` plugin in CLI subproject |
