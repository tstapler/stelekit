# Migration Plan: Logseq Sync & E2EE

## 1. Discovery & Requirements
Logseq Sync is a paid service that synchronizes graphs across devices using End-to-End Encryption (E2EE).

### Existing Artifacts
- `src/main/frontend/components/e2ee.cljs`: Encryption logic.
- `src/main/frontend/components/server.cljs`: WebSocket/API client.

### Functional Requirements
- **Authentication**: Login with Logseq account.
- **Encryption**: Encrypt data on client before sending. Decrypt on receive. User owns the keys.
- **Sync Protocol**: WebSocket-based real-time sync.
- **Conflict Resolution**: Handle concurrent edits.

### Non-Functional Requirements
- **Security**: Zero-knowledge architecture. Server never sees plain text.
- **Reliability**: Retry on network failure.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **SyncService**: Manages WebSocket connection (Ktor).
- **CryptoService**: Handles encryption/decryption.
    - *Library*: **Krypto** or **Bouncy Castle** (via expect/actual).
    - *Algorithm*: AES-GCM for content, RSA/ECC for key exchange (match legacy implementation).
- **SyncQueue**: Persist pending updates to disk in case of crash.

### UI Layer (Compose Multiplatform)
- **Component**: `SyncStatusIndicator` (Green/Yellow/Red dot).
- **Component**: `LoginScreen`.
- **Component**: `PasswordPrompt` (for unlocking E2EE key).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Security: Weak Randomness [SEVERITY: Critical]
- **Description**: Using `Math.random()` or weak generators for IVs/Keys compromises encryption.
- **Mitigation**: MUST use `SecureRandom` (JVM) and `SecRandomCopyBytes` (iOS). Use a verified crypto library.

### 🐛 Logic: Sync Loops [SEVERITY: High]
- **Description**: Client A sends update -> Server echoes -> Client A treats as new change -> Sends update.
- **Mitigation**: Tag updates with `clientId`. Ignore updates originating from self.

## 4. Implementation Roadmap

### Phase 1: Crypto
- [ ] Port encryption logic to Kotlin.
- [ ] Verify compatibility with existing CLJS encrypted data (Unit tests with test vectors).

### Phase 2: Networking
- [ ] Implement Ktor WebSocket client.
- [ ] Implement Auth flow.

### Phase 3: Sync Logic
- [ ] Implement "Diff" calculation.
- [ ] Implement "Apply Patch" logic.

## 5. Migration Checklist
- [ ] **Security**: Encryption matches legacy (can decrypt old data).
- [ ] **Logic**: Login works.
- [ ] **Logic**: Real-time sync works between KMP and Legacy app.
- [ ] **Reliability**: Offline changes sync when online.

