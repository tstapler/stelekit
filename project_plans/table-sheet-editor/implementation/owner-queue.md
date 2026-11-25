# Owner queue (PR-A)

Items an agent could not complete headlessly. Each needs the owner (Tyler) or a device/browser.

| Task | Status | What is needed |
|---|---|---|
| 0.7a baseline | BLOCKED-OWNER | The table-count command in requirements.md (Baseline) needs the owner's graph path; no Logseq graph exists on this machine (`~/Documents/notes` is absent). Also the stopwatch protocol and the five persona/platform questions. Baseline stays UNMEASURED. |
| 0.3a / 0.3b Wasm spike | BLOCKED-OWNER, not attempted | Needs owner-machine Chrome paste (Ctrl+V and context-menu) and a dead-key or IME sequence on a wasm build. No PR-A task depends on it; it gates 7.1b and the Wasm part of 5.3a (PR-C). |
| 0.4a Android spike (Pixel_Fold) | NOT ATTEMPTED | Emulator pass on a shared machine already under heavy Gradle load; no PR-A task depends on it. Gates 4.2a and 5.2b (PR-B/PR-C). Go/no-go rule in plan.md Task 0.4a applies. |
| 0.1b web baseline | NOT ATTEMPTED | Needs the wasm dev server and a browser screenshot at 390 px and desktop width. Phone baseline PNGs (Task 0.1a) exist. |
| 8.4b Logseq untouched fixtures | BLOCKED-OWNER | Open `implementation/logseq-compat/*.md` in Logseq and fill the results table in `logseq-compat/README.md`. |
