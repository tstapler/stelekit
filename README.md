# SteleKit

**Your knowledge, carved in stone.**

<!-- TODO: add logo here -->

A local-first outliner that keeps your notes as plain markdown on your disk — forever — and runs natively on every platform you use.

[![License: Elastic-2.0](https://img.shields.io/badge/license-Elastic--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-purple.svg)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose_Multiplatform-1.7.3-brightgreen.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)

---

## What is SteleKit

SteleKit is an outliner-first personal knowledge management system. You write in blocks, link pages bidirectionally, and journal daily — all in plain markdown files on your disk. There is no sync service, no proprietary format, no company that can take your notes away. The files are yours.

Logseq pioneered this model and built a community around it. SteleKit makes different technical bets: Kotlin Multiplatform instead of Electron, persistent SQLite instead of in-memory re-scan, Compose Multiplatform instead of a web renderer wrapped in a desktop shell. The result is a native feel on every target — including Android, where the original is genuinely compromised.

SteleKit exists because of Logseq's ideas, not in spite of them. Your existing Logseq graph opens in SteleKit without migration. The markdown files are unchanged.

### How they differ

|  | Logseq | SteleKit |
|--|--------|----------|
| Runtime | Electron / ClojureScript | Kotlin Multiplatform / Compose |
| Mobile | Compromised | Native-first |
| Graph storage | In-memory re-scan on launch | Persistent SQLite — instant open |
| Business model | Cloud sync (paid) | Source-available, free to use |
| Plugin system | JS plugins | Kotlin-native (early-stage) |
| Long-term bet | Platform + hosted services | Local-first, always |

---

## Quick Start

```bash
git clone https://github.com/tstapler/stelekit
cd stelekit
./gradlew :kmp:runApp
```

Point it at your existing Logseq graph directory. It reads the same markdown files.

**Prerequisites:** JDK 17+. Android SDK required for Android builds. Xcode required for iOS (macOS only).

---

## Architecture

SteleKit is a single Kotlin Multiplatform project. Shared business logic, data layer, and Compose UI live in `kmp/src/commonMain`. Platform-specific wiring is minimal.

| Layer | Technology |
|-------|------------|
| UI | Compose Multiplatform — one shared UI for all targets |
| Data | SQLDelight + SQLite — persistent, reactive, no cold-start re-scan |
| File format | Logseq-compatible markdown — your files are never locked in |
| Parsing | Custom Kotlin Markdown + Org-mode parser |
| Sync (roadmap) | CRDT-based, binary-compatible with Logseq sync format |

### Platform targets

| Platform | Status | Notes |
|----------|--------|-------|
| Desktop (JVM) | Working | Primary development target |
| Android | Working | Native Compose, SQLDelight persistent |
| Web (JS) | Working | SQLDelight web-worker driver + SQL.js |
| iOS | Planned | Disabled — Ivy repository issues |

---

## Project Status

The core loop — open a graph, read and write blocks, navigate pages, search, follow backlinks — works on Desktop and Android. The parser handles real-world Logseq graphs.

What's missing or early-stage:
- **Advanced queries** — no Datalog engine yet; basic full-text search via SQLite FTS5
- **Whiteboards** — not implemented
- **Plugin system** — scaffolding only
- **iOS** — disabled, build issues under investigation

See [`TODO.md`](TODO.md) for the full roadmap and known issues.

---

## Contributing

SteleKit is source-available under the [Elastic License 2.0](LICENSE). You can use it freely — including commercially — but you cannot fork it and sell it as a competing product.

Contributions are welcome, especially from Kotlin Multiplatform and Compose developers. Good places to start:

- Feature parity gaps (see [`docs/tasks/`](docs/tasks/))
- Test coverage on edge cases in the parser and outliner
- Android and iOS platform polish

---

## GitHub Topics

`kotlin-multiplatform` · `logseq` · `pkm` · `zettelkasten` · `compose-multiplatform` · `sqldelight` · `outliner` · `knowledge-management` · `local-first` · `note-taking` · `android` · `desktop`
