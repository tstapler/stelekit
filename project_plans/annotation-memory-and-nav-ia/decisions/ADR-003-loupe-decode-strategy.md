# ADR-003: Loupe decode strategy is chosen by a measured three-way spike

**Status**: Proposed (decision after spike A.5a) | **Date**: 2026-11-08

## Context
Current loupe composes a second Coil `AsyncImage` per press at up to 2048 px long edge (12.6 MB, architecture.md A4) rendered in a 3x-canvas `requiredSize` child inside `clip(CircleShape)` (features.md K4). Measured: disabling the loupe lowers CPU 142 -> 99 ms/press (about 30%, cpu.md section 3 VERIFIED). Memory stayed flat on the emulator (pitfalls.md Part B VERIFIED). build-vs-buy.md section 2-3 recommends bounded region decode, with a cached single bitmap and `Modifier.magnifier` as alternatives.

## Options
1. **Keep decode-per-press, quantize request size** (round canvas to 256 px steps; hoist loader). Strength: smallest change. Weakness: still 12.6 MB per distinct size.
2. **Single cached loupe bitmap per editor session, crop in `drawWithContent`** (no oversized child). Strength: no per-press decode, simple. Weakness: holds up to 12.6 MB for the session.
3. **`BitmapRegionDecoder` window (~0.5 MB), injected `LoupeSource` interface** (Android region decoder, optional JVM `ImageIO` source region). Strength: bounded and real source pixels. Weakness: per-drag-frame decode CPU unless tiled; some formats/EXIF orientations need extra handling (INFERRED).
4. **`Modifier.magnifier`** (Android only, API 28/29). Strength: ~zero app memory. Weakness: screen-resolution fidelity, not testable in the Compose tree, forks desktop behaviour.

## Decision
Run spike A.5a measuring options 1-3 (and 4 only if 2 and 3 fail the budget) with `ResourceProbe`: CPU ms/press, main-thread CPU, p95 frame time, max decode bytes, graphics PSS. Selection rule, fixed in advance: pick the option with the lowest CPU ms/press that keeps max single decode <= 2 MB, or option 2 if option 3 costs more than 1.2x its CPU. If measurements show no difference outside the +-10% noise band (cpu.md section 3), keep option 1 plus the screen-scope offset fix (A.4a) and record that the loupe was not the cause.

## Consequences
- ADR is amended with the measured table when A.5a completes. Option 3 adds a commonMain `fun interface LoupeSource` in `commonMain/platform/image/` (no `expect` is declared), injected from the platform shell like `ImageBoundsReader` (ADR-004); the Android implementation is `AM/platform/image/AndroidLoupeSource.kt`, a JVM implementation is optional, and iOS/wasmJs receive no implementation: the default cached-bitmap strategy (option 2) is used there.
- Amended 2026-11-08 (Repair 2) to match plan A.5 / A.5b. Facts behind the choice: the annotation editor lives in `commonMain` (`ui/annotate/`) and is compiled for every target, while the only existing `expect` in `platform/image/` (`expect object PlatformImageDownscaler`, `ImageDownscaler.kt:17`) already forces an explicit `.ios.kt` and `.wasmJs.kt` actual; an `expect` here would add four actuals (two would be "unsupported -> option 2" stubs) for a feature only Android needs. An injected interface keeps the seam testable with a fake in commonTest. If the team later prefers `expect/actual`, all four actuals must be listed and a `BitmapRegionDecoder` vs ImageIO EXIF parity test added (A.5b).
