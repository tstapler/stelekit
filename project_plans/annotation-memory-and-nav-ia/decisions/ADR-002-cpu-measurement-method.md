# ADR-002: CPU is measured with direct `dispatchTouchEvent` injection; the background phase is part of the protocol

**Status**: Proposed | **Date**: 2026-11-08

## Context
cpu.md section 3 (VERIFIED): `performTouchInput` inflates CPU 5-7x (705 vs 142 ms/press for the same scenario). cpu.md section 1 (VERIFIED from AOSP android14): the stock excessive-CPU kill only applies to processes at `PROCESS_STATE_HOME` or cached (>= ~25% of one core over a ~5 minute window); foreground apps are exempt. The emulator showed 2-4% idle CPU after bursts and no runaway.

## Options
1. `performTouchInput` for everything (matches #560 `LoupeAlignmentE2ETest`). Weakness: wrong CPU numbers.
2. Direct `MotionEvent` to the root view for CPU scenarios; `performTouchInput` only for functional/E2E assertions.
3. UiAutomator `swipe` from outside the process. Weakness: new dependency, coarse timing, no per-event control.

## Decision
Option 2. CPU scenarios use `DirectTouchInjector` (posts `MotionEvent`s with `SOURCE_TOUCHSCREEN` to the root view, 8 ms move cadence, 50 ms hold, as in cpu.md appendix). Each scenario: 3 warm-up presses discarded, then measurement. A **background phase** follows each burst: the activity is moved to the background (UiAutomator `pressHome()`, test-only dependency `androidx.test.uiautomator:uiautomator:2.3.0` already used by `macrobenchmark/`), then process CPU is sampled for 60 s and reported against the proposed 10% idle budget (amended Repair 2: `report-only` until the B.7 calibration soak promotes it; if positive control A.3d shows the process never reaches cached/HOME importance, the phase is renamed "idle CPU after burst"). The 5-minute stock-kill window is NOT run per PR; it is a manual or scheduled soak (wall-clock blocker, owner Tyler).

## Consequences
- Owner acceptance (2026-11-08, OD-5): Success Metric 1's "reproduces the pre-fix failure" is replaced by probe-proven gates, the positive control and the wording "mitigated and monitored" (requirements.md Amendment 2); the post-release outcome is Success Metric 6.
- Emulator RenderThread cost is unrepresentative of a phone GPU (cpu.md section 3 caveat); budgets are emulator-relative and used for regression detection only.
- Honest limitation: if the owner's `importance=` shows FOREGROUND, the stock-kill hypothesis is falsified and an OEM watchdog must be investigated on the owner's phone; no CI harness can reproduce that.
