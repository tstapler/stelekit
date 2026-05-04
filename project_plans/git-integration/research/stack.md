# Stack Research — Git Library for KMP (Android + Desktop JVM + iOS)

_Research date: 2025-05-02_

---

## 1. JGit (Eclipse Foundation)

**What it is:** Pure-Java implementation of Git. Used by Eclipse IDE, Gerrit, and many JVM tooling projects.

**GitHub:** https://github.com/eclipse-jgit/jgit  
**Latest version:** 7.6.0.202603022253-r (Maven: `org.eclipse.jgit:org.eclipse.jgit`)  
**License:** Eclipse Distribution License (BSD-style)

### JVM / Desktop
Works well on Desktop JVM. Mature API covering clone, fetch, merge, push, diff, log, status, stash, cherry-pick, and rebase. All SteleKit requirements (FR-1 through FR-6) can be satisfied on Desktop with JGit alone.

### Android
JGit **does run on Android** but with significant constraints:

- **Java version mismatch:** JGit 6.x requires Java 11; JGit 7.x (2024+) requires Java 17. Android's JVM runtime (Art) implements a different subset. Desugaring via AGP (`coreLibraryDesugar`) can bridge some gaps, but not all JGit 7.x features are safe.
- **Tested compatibility:** JGit 5.8 is reported compatible with Android 12 (API 31). JGit 6.8 is reported incompatible with Android without additional shims.
- **Older fork works:** Several Android apps (Android Password Store, Orgzly) use JGit up to version ~5.x via `org.eclipse.jgit:org.eclipse.jgit:5.13.x` with `minSdk 21`.
- **SSH issues (see §6 below):** JGit's default JSch transport is unmaintained. The `org.eclipse.jgit.ssh.jsch` module has known algorithm failures with modern OpenSSH servers.

### iOS
JGit **does NOT run on iOS**. It is JVM-only. There is no Kotlin/Native path for JGit.

### KMP Wrapper: KGit
- **GitHub:** https://github.com/sya-ri/KGit  
- **Version:** 1.1.0 (October 2024)  
- **What it does:** Idiomatic Kotlin wrapper around JGit (null-safety, lambdas, DSL-style config)
- **Limitation:** JVM-only — same constraints as JGit. No iOS target.

### Verdict on JGit
JGit is viable for Android + Desktop JVM as a "two-platform" solution, but it cannot serve iOS.

---

## 2. libgit2 (Native C Library)

**What it is:** A portable, pure-C re-entrant Git implementation designed to be embedded into other applications. Official language bindings exist for many languages.

**GitHub:** https://github.com/libgit2/libgit2  
**Supported natively:** Linux, macOS, iOS, Windows — fully tested by the libgit2 project.

### kgit2 — Kotlin Native Bindings

**GitHub:** https://github.com/kgit2/kgit2 (organization: https://github.com/kgit2)  
**Status:** Active development as of January 2025. ~71 commits on main.  
**Dependencies:** libgit2 v1.5.0+, libssh2, OpenSSL3  
**Composition:** ~80% Kotlin, rest is C/Rust/FreeMarker for interop scaffolding  

The kgit2 organization also maintains:
- `kgit2/c-interop-klib` — C interop KLib tooling
- `kgit2/Kommand` — Kotlin Native child process launcher

**Platform coverage:**
- iOS: Yes (via Kotlin/Native → libgit2 C interop)
- macOS: Yes (via Kotlin/Native)
- Android: Possible via JNI to the C library OR via Kotlin/Native Android target; **unclear if kgit2 explicitly targets Android NDK — this is UNRESOLVED**
- Desktop JVM: NOT via Kotlin/Native; would need JNI bridge

**Key risk:** kgit2 has low community adoption (small GitHub star count). No production case studies found for KMP apps using it on both Android and iOS simultaneously.

### Alternative: Use libgit2 via JNI on Android, Kotlin/Native on iOS

A split-platform approach where:
- Android: JNI wrapper around libgit2 `.so` (compiled per-ABI)
- iOS: Kotlin/Native via kgit2 or `cinterop` directly
- Desktop: JNI or a separate JVM wrapper

This is the approach used by GitJournal (Flutter) via `dart-git` and would require significant native build infrastructure in SteleKit.

---

## 3. Isomorphic-git (JavaScript)

**Relevance:** Only relevant if WASM/JS target is enabled (`enableJs=true` in `gradle.properties`). Pure JS implementation, no native dependencies. Good for browser-based or Electron use cases. **Not relevant to the current Android + Desktop JVM + iOS scope.**

---

## 4. Shelling Out to System `git`

**Approach:** Use `ProcessBuilder` (JVM/Android) to exec `git` binary on the host.

### Desktop JVM
Works well. Git is available on virtually all developer machines. Straightforward API via `ProcessBuilder`. No additional dependencies.

### Android
Works **if the device has git installed** — this is true on rooted devices, devices with Termux, or in-app Termux environments, but NOT on a stock Android device. Unsuitable for general users.

### iOS
**Impossible.** iOS has no writable `PATH` and the sandbox prevents exec of external binaries.

### Verdict
Shell-out is useful as a Desktop-only fallback or dev-mode tool, but cannot be the primary solution for a cross-platform app.

---

## 5. State of the Art Summary

| Option | Android | Desktop JVM | iOS | Notes |
|---|---|---|---|---|
| JGit (v5.x) | Yes (limited) | Yes (full) | No | SSH issues with modern keys; no iOS |
| JGit (v7.x) | Risky (Java 17 gap) | Yes (full) | No | Java 17 req. may block Android |
| kgit2 (libgit2/KN) | Unclear (UNRESOLVED) | No (needs JNI) | Yes | Early stage; low adoption |
| Shell-out git | Only with git installed | Yes | No | Not viable for mobile |
| isomorphic-git | No | No | No | JS/WASM only |

---

## 6. Recommendation

### Recommended Approach: Hybrid — JGit on JVM, kgit2/libgit2 on iOS

The most pragmatic path for SteleKit is a **platform-split architecture**:

```
commonMain    → expect interface: GitRepository (clone, fetch, merge, push, status, log)
androidMain   → actual: JGit 5.x via actuals (SSH via mwiede/jsch fork)
jvmMain       → actual: JGit 7.x (full features, no Java version constraints)
iosMain       → actual: kgit2 (libgit2 via Kotlin/Native)
```

**Why JGit for JVM/Android:**
- Mature, battle-tested, pure-Java — no native build pipeline needed
- Available on Maven Central, trivial Gradle dependency
- Already used in production Android apps (Android Password Store, Orgzly)
- SSH can be fixed with `com.github.mwiede:jsch` fork (see §7)

**Why kgit2/libgit2 for iOS:**
- libgit2 is the only viable pure-C git library with iOS support
- kgit2 provides the Kotlin/Native glue
- Risk: early stage; may require contributing fixes upstream

**Alternative (lower risk, lower feature completeness):** Use JGit on Android+Desktop and ship iOS without git features in the first version, then add kgit2 when it matures.

---

## 7. SSH Key Handling on Android

### JSch (Original)
- Bundled with JGit by default (`org.eclipse.jgit.ssh.jsch` module)
- **No longer maintained** by JGit team
- **Known issues:** Does not support `diffie-hellman-group14-sha256`, does not support ED25519 or ECDSA keys in OpenSSH new format, fails with modern GitHub/GitLab SSH servers
- JGit recommends migrating to Apache MINA SSHD

### mwiede/jsch Fork
- **GitHub:** https://github.com/mwiede/jsch  
- **Version:** 0.2.x (active as of 2024)  
- Drop-in replacement for `com.jcraft:jsch`  
- Adds: ED25519, ECDSA, modern key exchange algorithms, OpenSSH private key format  
- Used by several Android git apps to fix the algorithm negotiation failures
- **Android compatibility:** Works on Android if the provider is compatible; some issues with Android's BouncyCastle version

### SSHJ (Apache MINA SSHD direction)
- JGit's own `org.eclipse.jgit.ssh.apache` module uses Apache MINA SSHD
- Preferred for Desktop JVM (Java 11+)
- **Android:** MINA SSHD requires Java 11 native APIs (NIO) that may not be fully available on older Android; newer Android (API 26+) is mostly OK
- There is an [open issue in Orgzly](https://github.com/orgzly/orgzly-android/issues/904) tracking the JSch → Apache MINA SSHD migration

### Recommendation for SSH on Android
Use the `com.github.mwiede:jsch` fork for Android (max compatibility, tested on Android). Use Apache MINA SSHD (JGit's built-in) for Desktop JVM.

### SSH Key Path Configuration
Android apps must let users configure the SSH key file path (FR-5.4), since keys may live in:
- Termux's `~/.ssh/`
- App-private storage (copied in by user)
- System keychain (rare)

JGit with JSch supports `setSshKeyPath()` via a custom `JschConfigSessionFactory`.

---

## 8. Secure Credential Storage (KMP)

### Android

**EncryptedSharedPreferences** (AndroidX Security Crypto)
- Backed by Android Keystore
- **Deprecated as of 2024** — official guidance is to use Jetpack DataStore + Keystore
- Still functional; widely used in production apps
- For SSH key bytes: not ideal (large binary); better to store key path + passphrase

**Android Keystore directly**
- Stores cryptographic keys in hardware-backed keystore
- Best for storing derived keys, not raw SSH private key bytes (which are user-supplied)
- Suitable for wrapping a symmetric key that encrypts stored credentials

**Recommended Android pattern:** Store HTTPS tokens and SSH passphrases using `EncryptedSharedPreferences` (or DataStore + EncryptedFile for larger blobs), referencing user-configured SSH key file path.

### iOS

**Keychain Services**
- Native secure storage
- KVault (`com.liftric:kvault`) provides a KMP wrapper: https://github.com/Liftric/KVault
- KVault maps to iOS Keychain on iOS and `EncryptedSharedPreferences` on Android
- **Known limitation:** KVault does NOT support Desktop targets (JVM/macOS) as of 2024

**multiplatform-settings** (Touchlab)
- `KeychainSettings` implementation available
- Annotated `@ExperimentalSettingsImplementation` but stable in practice
- Broader platform support than KVault (includes JVM desktop)

### Desktop JVM

No system keychain abstraction in JVM standard library. Options:
- **Java Keystore (JKS):** Password-protected file keystore — suitable for SSH passphrases
- **OS-level:** macOS Keychain via JNA/JNI, GNOME Keyring on Linux — complex
- **Simplest:** Encrypted file in app data directory using Android-style key wrapping

### Recommendation

Use `multiplatform-settings` with `KeychainSettings` on iOS and `EncryptedSharedPreferencesSettings` on Android. For Desktop JVM, implement a simple `EncryptedFileCredentialStore` using javax.crypto. Wrap behind a `expect class CredentialStore` in commonMain.

---

## Open Questions / Unresolved Items

- **UNRESOLVED:** Does kgit2 support Android NDK targets (in addition to iOS)? If yes, kgit2 alone could cover all platforms.
- **UNRESOLVED:** kgit2 production readiness — no known production apps using it as of research date.
- **UNRESOLVED:** Apache MINA SSHD on Android API 26 minimum — needs integration test.
- **UNRESOLVED:** JGit 7.x on Android with AGP desugaring — Java 17 API gaps need full audit.

---

## Sources

- [kgit2 GitHub organization](https://github.com/kgit2)
- [libgit2 GitHub](https://github.com/libgit2/libgit2)
- [KGit (JGit Kotlin wrapper)](https://github.com/sya-ri/KGit)
- [mwiede/jsch fork](https://github.com/mwiede/jsch)
- [JGit Eclipse project](https://github.com/eclipse-jgit/jgit)
- [JSch vs MINA SSHD issue in Orgzly](https://github.com/orgzly/orgzly-android/issues/904)
- [KVault secure storage](https://github.com/Liftric/KVault)
- [Touchlab Encrypted KMP Storage](https://dev.to/touchlab/encrypted-key-value-store-in-kotlin-multiplatform-2hnk)
- [Android SSH auth issue in Android Password Store](https://github.com/android-password-store/Android-Password-Store/issues/568)
- [JGit Java 17 upgrade issue](https://github.com/eclipse-jgit/jgit/issues/52)
