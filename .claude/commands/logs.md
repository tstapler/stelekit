---
description: Search and filter stelekit app logs. Shows log file location, format, filtering patterns, and how the logging system works.
---

## Stelekit Logging System

### Architecture

The app uses a two-layer logging system defined in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/logging/Logger.kt`:

- **`Logger(tag)`** — per-component logger (e.g. `Logger("GraphLoader")`)
- **`LogManager`** — singleton: buffers up to 1000 entries in-memory (for the in-app Logs screen) and fans out to registered sinks
- **`FileLogSink`** (JVM only, `kmp/src/jvmMain/kotlin/.../logging/FileLogSink.kt`) — writes daily-rotating log files

### Log File Location

```
~/.stelekit/logs/stelekit-YYYY-MM-DD.log
```

Today's log:
```bash
cat ~/.stelekit/logs/stelekit-$(date +%F).log
```

If the file doesn't exist, the app is likely running via `./gradlew run` — logs go to stdout/stderr in that terminal.

### Log Format

```
HH:MM:SS.mmm [LEVEL] TAG: message
```

Example:
```
19:32:14.021 [INFO] GraphLoader: Starting progressive graph load from: /home/tstapler/Documents/personal-wiki
19:32:14.105 [INFO] GraphLoader: Phase 1 complete: Loaded 10 journals in 84ms
19:32:14.110 [INFO] JournalService: Today's journal ready: 2026-04-11
```

Levels: `DEBUG`, `INFO`, `WARN`, `ERROR`

### Common Search Patterns

**Show only warnings and errors:**
```bash
grep -E '\[(WARN|ERROR)\]' ~/.stelekit/logs/stelekit-$(date +%F).log
```

**Follow log in real time:**
```bash
tail -f ~/.stelekit/logs/stelekit-$(date +%F).log
```

**Filter by component tag:**
```bash
grep 'GraphLoader:' ~/.stelekit/logs/stelekit-$(date +%F).log
grep 'JournalService:' ~/.stelekit/logs/stelekit-$(date +%F).log
grep 'JournalsViewModel:' ~/.stelekit/logs/stelekit-$(date +%F).log
grep 'BlockStateManager:' ~/.stelekit/logs/stelekit-$(date +%F).log
```

**Search for a specific journal date:**
```bash
grep '2026-04-11\|2026_04_11' ~/.stelekit/logs/stelekit-$(date +%F).log
```

**Show graph loading sequence:**
```bash
grep -E 'GraphLoader:|JournalService:|Phase [12]' ~/.stelekit/logs/stelekit-$(date +%F).log
```

**Show page load skips (optimization check hits):**
```bash
grep 'Skipping\|already up to date\|unchanged' ~/.stelekit/logs/stelekit-$(date +%F).log
```

**Show file watcher events:**
```bash
grep -E 'New file detected|File modification detected|File deletion detected|watching graph' \
  ~/.stelekit/logs/stelekit-$(date +%F).log
```

**Show parse errors:**
```bash
grep -E 'Failed to parse|ERROR.*GraphLoader' ~/.stelekit/logs/stelekit-$(date +%F).log
```

### In-App Logs Screen

Click **Logs** in the left sidebar to see the in-memory log buffer (last 1000 entries). This works even when the file sink isn't available.

### Key Tags and What They Mean

| Tag | Component | What to look for |
|-----|-----------|-----------------|
| `GraphLoader` | File loading & watcher | Skipped files, parse errors, new/changed file events |
| `JournalService` | Journal creation/deduplication | Duplicate merges, "today's journal ready" |
| `JournalsViewModel` | UI journal display | Page load triggers |
| `BlockStateManager` | Block state & lazy load | `loadFullPage` calls |
| `DesktopMain` | App startup | Log file path, graph path |
| `FileLogSink` | (no tag — errors go to stdout) | If sink fails, only stdout output exists |

### Inspecting the SQLite Database Directly

The database lives at `~/.local/share/logseq/logseq-graph-<graphId>.db`.
Find it with:
```bash
ls ~/.local/share/logseq/*.db
```

**Check a journal page's DB state:**
```bash
DB=~/.local/share/logseq/logseq-graph-d8f8a832cc0a475d.db

# Get page row
sqlite3 "$DB" "SELECT uuid, name, updated_at, is_content_loaded, journal_date FROM pages WHERE journal_date = '2026-04-11';"

# Count blocks for that page UUID
sqlite3 "$DB" "SELECT COUNT(*) FROM blocks WHERE page_uuid = '<uuid>';"

# Confirm DB updated_at matches file mtime (they should match if file was parsed)
python3 -c "import datetime; print(datetime.datetime.fromtimestamp(<updated_at_ms>/1000))"
stat ~/Documents/personal-wiki/logseq/journals/2026_04_11.md | grep Modify
```

**Red flag**: `is_content_loaded=1` with `COUNT(*) = 0` blocks = page was partially saved (page row written, block processing failed or returned 0 blocks).

**Fix**: Re-index via Advanced Settings, or force re-parse:
```bash
sqlite3 "$DB" "UPDATE pages SET is_content_loaded = 0 WHERE journal_date = '2026-04-11';"
```

### Diagnosing a Missing/Empty Journal

Run these in order to trace the load path:

```bash
LOG=~/.stelekit/logs/stelekit-$(date +%F).log

# 1. Was the file detected?
grep -E 'New file|modification detected|2026_04_11' "$LOG"

# 2. Was loading skipped by the optimization?
grep 'Skipping\|already up to date' "$LOG"

# 3. Was today's journal ensured?
grep 'ensureTodayJournal\|Today.*journal ready\|JournalService' "$LOG"

# 4. Were there parse errors?
grep 'ERROR.*GraphLoader\|Failed to parse' "$LOG"

# 5. Were there duplicate page merges?
grep 'duplicate\|Deleted duplicate\|Re-parented block' "$LOG"
```

### When Logs Aren't Available

If `~/.stelekit/logs/` doesn't exist, the app was likely launched via Gradle:

```bash
./gradlew kmp:run 2>&1 | tee /tmp/stelekit-run.log
```

Then search `/tmp/stelekit-run.log` with the patterns above.
