# Coverage wave B: `parser`, `vault`, `domain` (JVM row, files with the most missed lines left after wave A)

Source and method as in `wave-A.md`. These packages already meet their tier targets on the `jvm` row (parser and vault T1, domain T2); wave B closes the largest remaining pure-logic files rather than a target shortfall, because no other T1 package is below target outside `db`/`repository` (owned by `db-repository-test-coverage`).

| File | Baseline | After (new tests alone added) |
|---|---|---|
| `parser/MarkdownParser.kt` | 155/185 | 170/185 |
| `domain/HtmlBlockConverter.kt` | 0/37 | 20/37 |
| `vault/VaultHeader.kt` | 37/50 | 50/50 |

Tests: `MarkdownParserInlineStorageTest`, `HtmlBlockConverterTest`, `VaultHeaderModelTest`.

Not done: `mcp` (`McpServerController`, `McpLauncherMain`, tool classes) and Robolectric-only `android`-row packages (`tags`, `voice`, `llm`, `capture`); waves C and D are deferred.
