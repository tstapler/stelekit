# ADR-016: SyncTransport — Pluggable Sync Transport Abstraction

## Status
Accepted

## Context

SteleKit currently syncs via two tightly-coupled mechanisms:

- **Desktop (JVM/Android)**: `GitManager` interface over `ProcessBuilder` (`ADR-012`)
- **WASM (browser)**: GitHub REST API + OPFS (`ADR-013`) and GitHub Git Data API write-back (`ADR-015`)

Both implementations leak git-specific semantics into the `GitManager` interface (`commit`, `status`, `isDirty`). Every future transport must fake these methods or add adapters. The interface also assumes a GitHub-style remote, which prevents swapping in LAN-direct, WebRTC, or a bastion server without restructuring callers.

Three near-term transport needs exist:

1. **LAN-HTTP direct sync** — two devices on the same WiFi network should sync without a cloud round-trip (covers the 80% desktop↔desktop and desktop↔phone case)
2. **Bastion server** — a lightweight relay for WASM write-back and self-hosted graphs (simpler alternative to the five-step GitHub Git Data API sequence in ADR-015)
3. **NFC pairing handshake** — tap a work device to a personal device to bootstrap sync credentials and endpoint URL; bulk transfer follows via LAN-HTTP

Future transports on the horizon: WebRTC data channels (WASM↔WASM browser-to-browser), Electric SQL row-level sync (deferred to Q4 2026 pending KMP support).

### Constraints

- `GitManager` callers (`GitSyncService`, `StelekitViewModel`, sync status UI) must not change API shape per transport
- Section filtering (`SectionFilter`, `SectionAwareSyncService`) must remain above the transport layer — transports receive active path prefixes as parameters, they do not implement section logic
- All transports must support `Either<DomainError, T>` error returns (Arrow)
- WASM transports must be implementable with Ktor `HttpClient` only (no JNI, no git binary)
- Transport selection is per-graph (not per-session); the graph config determines which transport is active

## Decision

Replace `GitManager` with a `SyncTransport` interface that expresses sync operations in transport-neutral terms. The existing git-backed implementations become `GitSyncTransport` (JVM) and `GitHubRestTransport` (WASM), both implementing `SyncTransport`.

### Interface

```kotlin
// commonMain: platform/SyncTransport.kt

interface SyncTransport {
    val id: String
    val capabilities: TransportCapabilities

    /** Connect or validate the endpoint. Called once at graph open. */
    suspend fun connect(endpoint: SyncEndpoint, credentials: SyncCredentials?): Either<DomainError, Unit>

    /** List remote files under the given path prefixes (Active sections only). */
    suspend fun listRemote(sectionPrefixes: Set<String>): Either<DomainError, List<RemoteFileEntry>>

    /** Pull the given file paths from remote to local storage. */
    suspend fun pullFiles(paths: Set<String>): Either<DomainError, List<SyncedFile>>

    /** Push local files to remote, creating a logical commit with the given message. */
    suspend fun pushFiles(files: List<LocalFile>, message: String): Either<DomainError, SyncVersion>

    /** Return the remote's current version token. Used to detect incoming changes. */
    suspend fun remoteVersion(): Either<DomainError, SyncVersion>

    /** True if remote has advanced past the given known version. */
    suspend fun hasRemoteChanges(knownVersion: SyncVersion): Either<DomainError, Boolean>

    fun disconnect()
}

data class TransportCapabilities(
    /** Can the transport serve file content without a network round-trip? (git blobs, OPFS cache) */
    val supportsOfflineRead: Boolean,
    /** Does this transport require a server/relay? (false: LAN-HTTP, WebRTC peer-to-peer) */
    val requiresServer: Boolean,
    /** Maximum file pulls that may be in-flight simultaneously. */
    val maxConcurrentPulls: Int,
    /**
     * True for pairing-only transports (e.g. NFC). A bootstrap transport yields a SyncEndpoint
     * then signals the caller to hand off to a bulk transport (typically LAN-HTTP).
     */
    val isBootstrapOnly: Boolean,
)

sealed class SyncEndpoint {
    data class GitRemote(val url: String, val branch: String) : SyncEndpoint()
    data class LanPeer(val host: String, val port: Int, val peerToken: String) : SyncEndpoint()
    data class WebRtcPeer(val peerId: String, val signalingUrl: String) : SyncEndpoint()
    data class BastionServer(val url: String) : SyncEndpoint()
}

/** Opaque version token — SHA for git, manifest hash for bastion/R2, vector clock for CRDT. */
@JvmInline value class SyncVersion(val opaque: String)

data class RemoteFileEntry(val path: String, val version: SyncVersion, val sizeBytes: Long)
data class SyncedFile(val path: String, val content: ByteArray, val version: SyncVersion)
data class LocalFile(val path: String, val content: ByteArray)
```

### Credential model

```kotlin
sealed class SyncCredentials {
    data class Token(val value: String) : SyncCredentials()          // GitHub PAT, bastion token
    data class PreSharedKey(val value: String) : SyncCredentials()   // LAN-HTTP peer token
    data object None : SyncCredentials()
}
```

### Transport implementations (current and planned)

| Transport | Endpoint type | Platform | Status |
|---|---|---|---|
| `GitSyncTransport` | `GitRemote` | JVM/Android | Refactor from `GitManager` (ADR-012) |
| `GitHubRestTransport` | `GitRemote` | WASM | Refactor from ADR-013 + ADR-015 |
| `LanHttpTransport` | `LanPeer` | JVM/Android | Planned — Epic 6 |
| `BastionTransport` | `BastionServer` | All | Planned — Epic 7 (replaces ADR-015 write-back complexity) |
| `NfcPairingTransport` | → yields `LanPeer` | Android | Planned — Epic 8 (bootstrap only) |
| `WebRtcTransport` | `WebRtcPeer` | WASM | Future — after Electric SQL evaluation (Q4 2026) |

### SectionAwareSyncService (service layer — above transport)

Section filtering stays above the transport. `SectionAwareSyncService` reads the device profile, resolves active section path prefixes, and passes them to `transport.listRemote(activePrefixes)`. Transports contain no section logic.

```kotlin
class SectionAwareSyncService(
    private val transport: SyncTransport,
    private val deviceProfile: DeviceProfile,
    private val sectionManifest: SectionManifest,
) {
    suspend fun sync(): Either<DomainError, SyncResult> {
        val activePrefixes = deviceProfile.activeSectionIds
            .mapNotNull { id -> sectionManifest.findById(id) }
            .flatMap { listOf(it.pagePathPrefix, it.journalPathPrefix) }
            .toSet()
            .plus("") // global pages (no section prefix)

        return transport.listRemote(activePrefixes).flatMap { entries ->
            // … pull missing, push dirty, resolve conflicts …
        }
    }
}
```

### Migration from GitManager

`GitManager` is renamed `SyncTransport`. The existing JVM/Android implementation (`JvmGitManager`) becomes `GitSyncTransport`. The WASM stub (`JsGitManager`) becomes `GitHubRestTransport` once ADR-013 and ADR-015 are implemented; until then it remains a stub returning `DomainError.NetworkError.HttpError(501, …)`.

`GitSyncService` is renamed `SyncCoordinator` and updated to call `SyncTransport` methods instead of git-specific methods. All callers of `GitSyncService` (ViewModel, UI sync status) are updated in the same pass.

The rename is a mechanical refactor — no behavioral change. It unblocks LAN-HTTP and bastion implementations without touching callers.

## Rationale

### Why transport-neutral interface semantics?

`GitManager.commit(message)` / `GitManager.status()` are git primitives. A LAN-HTTP transport has no concept of a commit — it pushes files with a version token. A bastion server accepts a PUT and returns a new version hash. Forcing these into `commit`/`status`/`isDirty` requires every transport to either return nonsensical values or implement a fake local git store. `pushFiles` / `remoteVersion` / `hasRemoteChanges` are transport-neutral equivalents that map cleanly to all targets.

### Why LAN-HTTP as the first non-git transport?

- Ktor server (Netty) is likely already on the JVM classpath via Ktor client
- JmDNS (~250KB pure Java) handles mDNS service advertisement and discovery
- WiFi throughput: 100+ Mbps — a 300-page section (~30 MB) syncs in under 3 seconds
- No relay server, no cloud account, no NAT traversal
- Covers the high-value case (two personal devices on the same network) with ~500 LOC

WebRTC requires a signaling server and has no viable JVM binding without a 50MB JNI blob. LAN-HTTP is simpler and faster for the desktop case; WebRTC is deferred to the WASM path.

### Why NFC is bootstrap-only?

NFC Type 4 realistic throughput: ~80 KB/s. A 300-page section (30 MB) would take 6+ minutes — unacceptable. The correct model: NFC carries a 512-byte pairing payload (device name, pre-shared key, LAN-HTTP endpoint URL). Bulk transfer follows via LAN-HTTP. On iOS, CoreNFC only supports reading, not HCE — iOS users use QR code pairing instead. `isBootstrapOnly: true` communicates this constraint to the caller so it can immediately hand off to `LanHttpTransport`.

### Why does the bastion replace ADR-015?

The GitHub Git Data API write-back (ADR-015) requires five sequential HTTPS calls per push (blob → tree → commit → ref-check → ref-update). A `BastionTransport` backed by a Cloudflare Worker + R2 reduces this to a single `PUT /sync/{token}/{path}` per file. The bastion also supports non-GitHub remotes (self-hosted graphs, GitLab, Gitea) without per-platform dispatch logic. ADR-015 remains the implementation until the bastion ships in Epic 7; at that point `GitHubRestTransport` is deprecated for write-back and `BastionTransport` takes over.

### Why not Scuttlebutt / libp2p / Matrix?

- **Scuttlebutt**: append-only log is architecturally mismatched with mutable Markdown files. Edits require a CRDT operation log on top. Not the right data model.
- **libp2p**: transport abstraction *pattern* borrowed (the `SyncTransport` interface is structurally similar to libp2p's `Transport`), library not adopted (8–12MB JAR, actively changing API surface).
- **Matrix**: federation protocol for 2-device personal sync is extreme overhead. Conduit homeserver = 50MB Rust binary + TLS setup + room state management. LAN-HTTP is 500 LOC.

### Why section filtering above the transport?

If filtering were inside the transport, each transport implementation would have to re-implement section state logic. The `SectionAwareSyncService` owns that mapping once. Transports receive resolved path prefixes, making them independently testable with simple fixtures. A future "sync all sections" admin mode requires only a service-layer change, not transport changes.

## Alternatives Rejected

### Keep GitManager, add per-transport method overloads
Accumulates transport-specific methods on a single interface (e.g. `lanPush()`, `bastionPush()`). Callers must switch on transport type. Rejected: violates the point of abstraction.

### CRDT-based storage as the sync layer
Automerge/Yjs would make conflicts merge-free but add ~500KB to the WASM bundle and replace Markdown as the canonical format. Deferred: the existing `operations` + `logical_clock` tables cover 80% of the conflict-resolution need. See storage format research — revisit with Electric SQL in Q4 2026.

### isomorphic-git for WASM write-back
ADR-015 already rejected this: push requires git smart HTTP protocol (GitHub blocks CORS on this), +483KB bundle, requires a second virtual filesystem alongside OPFS. Still rejected.

## Consequences

- `GitManager` is renamed `SyncTransport`; existing JVM and WASM implementations are refactored to implement it. This is a breaking rename but all sites are internal.
- `GitSyncService` is renamed `SyncCoordinator` in the same pass.
- New `SyncEndpoint` sealed class replaces free-form remote URL strings in graph config.
- `LanHttpTransport` (Epic 6) requires `ktor-server-netty` on the JVM classpath and `jmdns:3.5.9` as a new dependency.
- `BastionTransport` (Epic 7) requires a deployed Cloudflare Worker or Ktor server; no new client dependencies (Ktor `HttpClient` already present).
- `NfcPairingTransport` (Epic 8) requires `android.nfc.NfcAdapter` (Android only); iOS falls back to QR code.
- WebRTC transport deferred until Electric SQL KMP support is evaluated (Q4 2026).
- `SectionAwareSyncService` is a new class in `commonMain`; `SectionFilter` (Epic 1) is its input, not its internal logic.
